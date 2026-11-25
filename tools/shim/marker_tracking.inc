/* AprilTag tracking engine for the ARCore shim.
 *
 * This file is included by overrides.inc after the logging helpers and the
 * display-rotation variable.  It contains the complete marker pipeline:
 *
 *   ArSession_update
 *     -> mt_capture()          copy the camera Y plane (cheap)
 *     -> worker thread         AprilTag detection + pose (off the GL thread)
 *     -> mt_pose_at()          filtered pose + short-term prediction for
 *                              every ArCamera_getPose/getViewMatrix call
 *
 * Design notes:
 *  - Detection runs asynchronously.  A slow frame never stalls rendering; the
 *    worker always consumes the newest queued frame and older frames are
 *    dropped.
 *  - A planar tag has two valid PnP solutions.  Both are evaluated and the one
 *    closest to the previous/predicted pose wins, which removes the classic
 *    "pose flip" jitter.  The reprojection error is only a tie-breaker.
 *  - The detector runs at a lower resolution while a tag is tracked and at full
 *    resolution while searching, so acquisition stays reliable but tracking
 *    stays fast.
 *  - One Euro filtering (position and rotation) plus constant-velocity
 *    prediction keeps the view smooth and responsive between detections.
 */
#ifndef MARKER_TRACKING_INC
#define MARKER_TRACKING_INC

#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <stdio.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/resource.h>

#include "apriltag.h"
#include "tag36h11.h"
#include "apriltag_pose.h"
#include "common/image_u8.h"

#define MT_QUEUE_BUFFERS 3

typedef struct {
    int dist_on;
    double k1, k2, p1, p2;

    float tag_size;             /* metres */
    float min_margin;
    int max_hamming;
    float max_px;               /* reprojection RMS gate, pixels */
    int min_area;               /* detected quad area, square pixels */

    int filter_on;
    float f_min;                /* One Euro minimum cutoff, Hz */
    float beta_pos;             /* position speed gain, Hz per m/s */
    float beta_rot;             /* rotation speed gain, Hz per rad/s */

    float max_jump_m;
    float max_ang_deg;

    int predict_on;
    float lead;
    int predict_ms;             /* maximum extrapolation window */
    int latency_ms;             /* fallback camera pipeline latency */

    int hold_ms;                /* keep tracking this long after last detection */
    int reset_ms;               /* filter reset if measurements are this far apart */

    int tag_id;                 /* -1: any tag */
    int lock_tag;               /* keep the current tag id when possible */
    int world_mode;             /* 1: Y-up world, 0: raw tag frame */

    float decimate_track;
    float decimate_search;
    int refine_edges;
    int threads;
} MarkerTuning;

static MarkerTuning g_mt = {
    .dist_on = 1,
    .k1 = 0.14243204,
    .k2 = -0.20529187,
    .p1 = 0.00432335,
    .p2 = -0.01933716,
    .tag_size = 0.1485f,
    .min_margin = 30.0f,
    .max_hamming = 0,
    .max_px = 2.0f,
    .min_area = 30,
    .filter_on = 1,
    .f_min = 0.7f,
    .beta_pos = 25.0f,
    .beta_rot = 8.0f,
    .max_jump_m = 0.35f,
    .max_ang_deg = 40.0f,
    .predict_on = 1,
    .lead = 0.8f,
    .predict_ms = 150,
    .latency_ms = 25,
    .hold_ms = 350,
    .reset_ms = 1000,
    .tag_id = -1,
    .lock_tag = 1,
    .world_mode = 1,
    .decimate_track = 1.0f,
    .decimate_search = 1.0f,
    .refine_edges = 1,
    .threads = 2,
};

static pthread_mutex_t g_mt_tune_lock = PTHREAD_MUTEX_INITIALIZER;

typedef struct {
    int id;
    double area;
    double margin;
    int hamming;
    int strong;
    double c[3];
    double q[4];
    double rms_px;
    double cost;
} MarkerCandidate;

typedef struct {
    pthread_mutex_t lock;
    int valid;
    int tracking;
    double c[3];
    double q[4];
    double v[3];
    double w[3];
    int64_t stamp_ns;           /* capture time of the last accepted frame */
    int64_t last_det_ns;        /* time the last frame was accepted */
    int last_id;
    double last_area;
    int reject_run;
    int miss_run;

    /* metrics */
    int64_t stat_ns;
    double raw_c[3], raw_q[4];
    int raw_valid;
    int64_t raw_ns;
    double out_c[3], out_q[4];
    int out_valid;
    double raw_pos2, raw_rot2, out_pos2, out_rot2;
    double lag_pos, lag_rot;
    double sum_rms, sum_margin;
    int measurements;
    int dropped;
    int64_t proc_ns;
    int64_t proc_max_ns;
    int proc_n;
} MarkerState;

static MarkerState g_ms = {
    .lock = PTHREAD_MUTEX_INITIALIZER,
    .last_id = -1,
};

typedef struct {
    uint8_t *data;
    int capacity;
    int w, h;
    float fx, fy, cx, cy;
    int64_t capture_ns;
    uint64_t seq;
    int state;                  /* 0 free, 1 ready, 2 worker owns it */
} MarkerFrame;

static MarkerFrame g_mq[MT_QUEUE_BUFFERS];
static pthread_mutex_t g_mq_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_mq_cond = PTHREAD_COND_INITIALIZER;
static uint64_t g_mq_seq;
static int g_mq_worker;
static volatile int g_mt_freeze;
static int g_mt_calls;
static int64_t g_mt_last_frame_ts;
static int g_mt_have_frame_ts;
static int g_mt_ts_logged;

static apriltag_detector_t *g_mt_td;
static apriltag_family_t *g_mt_tf;
static int64_t g_mt_log_gate_ns;

static int mt_log_gate(void) {
    int64_t now = now_ns();
    if (now - g_mt_log_gate_ns < 1000000000LL) return 0;
    g_mt_log_gate_ns = now;
    return 1;
}

/* ------------------------------------------------------------------ */
/* basic helpers                                                       */
/* ------------------------------------------------------------------ */

static int mt_file_exists(const char *name) {
    char path[256];
    snprintf(path, sizeof(path), "%s%s", APP_DIR, name);
    int fd = open(path, O_RDONLY);
    if (fd >= 0) {
        close(fd);
        return 1;
    }
    return 0;
}

static int mt_read_doubles(const char *name, double *out, int n) {
    char path[256];
    snprintf(path, sizeof(path), "%s%s", APP_DIR, name);
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    int got = 0;
    for (int i = 0; i < n; i++) {
        if (fscanf(f, "%lf", &out[i]) == 1) got++;
        else break;
    }
    fclose(f);
    return got == n;
}

static int mt_read_int(const char *name, int *out) {
    double v;
    if (!mt_read_doubles(name, &v, 1)) return 0;
    *out = (int)llround(v);
    return 1;
}

static void mt_tuning_snapshot(MarkerTuning *out) {
    pthread_mutex_lock(&g_mt_tune_lock);
    *out = g_mt;
    pthread_mutex_unlock(&g_mt_tune_lock);
}

static void mt_load_tuning(void) {
    MarkerTuning t;
    pthread_mutex_lock(&g_mt_tune_lock);
    t = g_mt;
    pthread_mutex_unlock(&g_mt_tune_lock);

    double v[4];
    if (mt_read_doubles("dist", v, 4)) {
        t.k1 = v[0]; t.k2 = v[1]; t.p1 = v[2]; t.p2 = v[3];
    }
    t.dist_on = !mt_file_exists("dist_off");
    t.filter_on = !mt_file_exists("filter_off");
    t.predict_on = !mt_file_exists("predict_off");

    if (mt_read_doubles("tag_size", v, 1) && v[0] > 30.0 && v[0] < 1000.0)
        t.tag_size = (float)(v[0] / 1000.0);
    if (mt_read_doubles("margin", v, 1))
        t.min_margin = (float)v[0];
    if (mt_read_doubles("max_px", v, 1) && v[0] > 0.1)
        t.max_px = (float)v[0];
    if (mt_read_doubles("max_jump", v, 1) && v[0] > 0.0)
        t.max_jump_m = (float)v[0];
    if (mt_read_doubles("max_ang", v, 1) && v[0] > 0.0)
        t.max_ang_deg = (float)v[0];
    if (mt_read_doubles("euro", v, 3)) {
        if (v[0] > 0.05) t.f_min = (float)v[0];
        if (v[1] >= 0.0) t.beta_pos = (float)v[1];
        if (v[2] >= 0.0) t.beta_rot = (float)v[2];
    }
    if (mt_read_doubles("lead", v, 1) && v[0] >= 0.0 && v[0] <= 3.0)
        t.lead = (float)v[0];

    int n;
    if (mt_read_int("hold_ms", &n) && n >= 50 && n <= 5000)
        t.hold_ms = n;
    if (mt_read_int("predict_ms", &n) && n >= 0 && n <= 1000)
        t.predict_ms = n;
    if (mt_read_int("latency_ms", &n) && n >= 0 && n <= 200)
        t.latency_ms = n;
    if (mt_read_int("reset_ms", &n) && n >= 100 && n <= 10000)
        t.reset_ms = n;
    if (mt_read_int("hamming", &n) && n >= 0 && n <= 2)
        t.max_hamming = n;
    if (mt_read_int("min_area", &n) && n >= 0 && n <= 10000)
        t.min_area = n;
    if (mt_read_int("threads", &n) && n >= 1 && n <= 8)
        t.threads = n;
    if (mt_read_int("refine", &n))
        t.refine_edges = n ? 1 : 0;
    if (mt_read_int("tag_id", &n) && n >= -1 && n <= 999)
        t.tag_id = n;
    if (mt_read_int("lock_tag", &n))
        t.lock_tag = n ? 1 : 0;
    if (mt_read_int("world_mode", &n) && n >= 0 && n <= 1)
        t.world_mode = n;

    if (mt_read_doubles("decimate", v, 2)) {
        if (v[0] >= 0.25 && v[0] <= 4.0) t.decimate_track = (float)v[0];
        if (v[1] >= 0.25 && v[1] <= 4.0) t.decimate_search = (float)v[1];
    } else if (mt_read_doubles("decimate", v, 1) && v[0] >= 0.25 && v[0] <= 4.0) {
        t.decimate_track = t.decimate_search = (float)v[0];
    }

    static int logged;
    pthread_mutex_lock(&g_mt_tune_lock);
    g_mt = t;
    if (!logged) {
        logged = 1;
        shim_log("tune dist=%d(%.4f,%.4f,%.4f,%.4f) tag=%.4f margin=%.0f maxpx=%.1f "
                 "filter=%d jump=%.2f/%.0f predict=%d lead=%.2f hold=%dms "
                 "latency=%dms decimate=%.1f/%.1f tag_id=%d lock=%d",
                 t.dist_on, t.k1, t.k2, t.p1, t.p2, t.tag_size, t.min_margin,
                 t.max_px, t.filter_on, t.max_jump_m, t.max_ang_deg,
                 t.predict_on, t.lead, t.hold_ms, t.latency_ms,
                 t.decimate_track, t.decimate_search, t.tag_id, t.lock_tag);
    }
    pthread_mutex_unlock(&g_mt_tune_lock);
}

static int mt_poll_freeze(void) {
    if ((g_mt_calls++ & 15) != 0) return g_mt_freeze;
    int v = mt_file_exists("freeze") ? 1 : 0;
    if (v != g_mt_freeze) {
        g_mt_freeze = v;
        shim_log("freeze=%d", v);
    }
    return g_mt_freeze;
}

static void mt_quat_normalize(double q[4]) {
    double n = sqrt(q[0]*q[0] + q[1]*q[1] + q[2]*q[2] + q[3]*q[3]);
    if (n < 1e-12) {
        q[0] = q[1] = q[2] = 0.0;
        q[3] = 1.0;
        return;
    }
    for (int i = 0; i < 4; i++) q[i] /= n;
}

static void mt_quat_mul(const double a[4], const double b[4], double out[4]) {
    out[0] = a[3]*b[0] + a[0]*b[3] + a[1]*b[2] - a[2]*b[1];
    out[1] = a[3]*b[1] - a[0]*b[2] + a[1]*b[3] + a[2]*b[0];
    out[2] = a[3]*b[2] + a[0]*b[1] - a[1]*b[0] + a[2]*b[3];
    out[3] = a[3]*b[3] - a[0]*b[0] - a[1]*b[1] - a[2]*b[2];
}

static void mt_quat_conj(const double q[4], double out[4]) {
    out[0] = -q[0];
    out[1] = -q[1];
    out[2] = -q[2];
    out[3] = q[3];
}

static void mt_quat_slerp(const double a[4], const double b_in[4], double t, double out[4]) {
    double b[4] = {b_in[0], b_in[1], b_in[2], b_in[3]};
    double dot = a[0]*b[0] + a[1]*b[1] + a[2]*b[2] + a[3]*b[3];
    if (dot < 0.0) {
        dot = -dot;
        for (int i = 0; i < 4; i++) b[i] = -b[i];
    }
    if (dot > 1.0) dot = 1.0;
    if (dot > 0.9995) {
        for (int i = 0; i < 4; i++) out[i] = a[i] + t * (b[i] - a[i]);
        mt_quat_normalize(out);
        return;
    }
    double theta = acos(dot);
    double s = sin(theta);
    double wa = sin((1.0 - t) * theta) / s;
    double wb = sin(t * theta) / s;
    for (int i = 0; i < 4; i++) out[i] = wa * a[i] + wb * b[i];
    mt_quat_normalize(out);
}

static void mt_quat_apply_world(const double w[3], double dt, double q[4]) {
    double wn = sqrt(w[0]*w[0] + w[1]*w[1] + w[2]*w[2]);
    if (wn < 1e-6 || dt <= 0.0) return;
    double ang = wn * dt;
    double s = sin(ang * 0.5) / wn;
    double dq[4] = {w[0]*s, w[1]*s, w[2]*s, cos(ang * 0.5)};
    double out[4];
    mt_quat_mul(dq, q, out);
    mt_quat_normalize(out);
    memcpy(q, out, sizeof(out));
}

static double mt_quat_angle_deg(const double a[4], const double b[4]) {
    double dot = a[0]*b[0] + a[1]*b[1] + a[2]*b[2] + a[3]*b[3];
    if (dot < 0.0) dot = -dot;
    if (dot > 1.0) dot = 1.0;
    return 2.0 * acos(dot) * 180.0 / M_PI;
}

static double mt_vec3_dist(const double a[3], const double b[3]) {
    double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
    return sqrt(dx*dx + dy*dy + dz*dz);
}

static double mt_vec3_norm(const double a[3]) {
    return sqrt(a[0]*a[0] + a[1]*a[1] + a[2]*a[2]);
}

static void mt_mat_to_quat(const double R[3][3], double q[4]) {
    double tr = R[0][0] + R[1][1] + R[2][2];
    if (tr > 0.0) {
        double s = sqrt(tr + 1.0) * 2.0;
        q[3] = 0.25 * s;
        q[0] = (R[2][1] - R[1][2]) / s;
        q[1] = (R[0][2] - R[2][0]) / s;
        q[2] = (R[1][0] - R[0][1]) / s;
    } else if (R[0][0] > R[1][1] && R[0][0] > R[2][2]) {
        double s = sqrt(1.0 + R[0][0] - R[1][1] - R[2][2]) * 2.0;
        q[3] = (R[2][1] - R[1][2]) / s;
        q[0] = 0.25 * s;
        q[1] = (R[0][1] + R[1][0]) / s;
        q[2] = (R[0][2] + R[2][0]) / s;
    } else if (R[1][1] > R[2][2]) {
        double s = sqrt(1.0 + R[1][1] - R[0][0] - R[2][2]) * 2.0;
        q[3] = (R[0][2] - R[2][0]) / s;
        q[0] = (R[0][1] + R[1][0]) / s;
        q[1] = 0.25 * s;
        q[2] = (R[1][2] + R[2][1]) / s;
    } else {
        double s = sqrt(1.0 + R[2][2] - R[0][0] - R[1][1]) * 2.0;
        q[3] = (R[1][0] - R[0][1]) / s;
        q[0] = (R[0][2] + R[2][0]) / s;
        q[1] = (R[1][2] + R[2][1]) / s;
        q[2] = 0.25 * s;
    }
    mt_quat_normalize(q);
}

static void mt_quat_to_mat(const double q[4], double R[3][3]) {
    double x = q[0], y = q[1], z = q[2], w = q[3];
    R[0][0] = 1.0 - 2.0 * (y*y + z*z);
    R[0][1] = 2.0 * (x*y - z*w);
    R[0][2] = 2.0 * (x*z + y*w);
    R[1][0] = 2.0 * (x*y + z*w);
    R[1][1] = 1.0 - 2.0 * (x*x + z*z);
    R[1][2] = 2.0 * (y*z - x*w);
    R[2][0] = 2.0 * (x*z - y*w);
    R[2][1] = 2.0 * (y*z + x*w);
    R[2][2] = 1.0 - 2.0 * (x*x + y*y);
}

static void mt_view_from_cq(const double c[3], const double q[4], float *m) {
    double Rc[3][3], Rg[3][3], tg[3];
    mt_quat_to_mat(q, Rc);
    for (int r = 0; r < 3; r++)
        for (int k = 0; k < 3; k++) Rg[r][k] = Rc[k][r];
    for (int r = 0; r < 3; r++)
        tg[r] = -(Rg[r][0]*c[0] + Rg[r][1]*c[1] + Rg[r][2]*c[2]);
    m[0] = (float)Rg[0][0]; m[1] = (float)Rg[1][0]; m[2] = (float)Rg[2][0]; m[3] = 0.0f;
    m[4] = (float)Rg[0][1]; m[5] = (float)Rg[1][1]; m[6] = (float)Rg[2][1]; m[7] = 0.0f;
    m[8] = (float)Rg[0][2]; m[9] = (float)Rg[1][2]; m[10] = (float)Rg[2][2]; m[11] = 0.0f;
    m[12] = (float)tg[0]; m[13] = (float)tg[1]; m[14] = (float)tg[2]; m[15] = 1.0f;
}

static void mt_pose_from_cq(const double c[3], const double q[4], float *m) {
    double Rc[3][3];
    mt_quat_to_mat(q, Rc);
    m[0] = (float)Rc[0][0]; m[1] = (float)Rc[1][0]; m[2] = (float)Rc[2][0]; m[3] = 0.0f;
    m[4] = (float)Rc[0][1]; m[5] = (float)Rc[1][1]; m[6] = (float)Rc[2][1]; m[7] = 0.0f;
    m[8] = (float)Rc[0][2]; m[9] = (float)Rc[1][2]; m[10] = (float)Rc[2][2]; m[11] = 0.0f;
    m[12] = (float)c[0]; m[13] = (float)c[1]; m[14] = (float)c[2]; m[15] = 1.0f;
}

static void mt_identity(float *m) {
    memset(m, 0, 16 * sizeof(float));
    m[0] = m[5] = m[10] = m[15] = 1.0f;
}

/* Inverse of the OpenCV/Kalibr radtan model, so the pinhole PnP sees
 * undistorted corners. */
static void mt_undistort_px(float u, float v, float fx, float fy,
                            float cx, float cy, const MarkerTuning *t,
                            float *out_u, float *out_v) {
    if (!t->dist_on || fx <= 0.0f || fy <= 0.0f) {
        *out_u = u;
        *out_v = v;
        return;
    }
    double xd = ((double)u - cx) / fx;
    double yd = ((double)v - cy) / fy;
    double x = xd, y = yd;
    for (int i = 0; i < 8; i++) {
        double r2 = x*x + y*y;
        double radial = 1.0 + t->k1*r2 + t->k2*r2*r2;
        if (fabs(radial) < 1e-6) break;
        double dx = 2.0*t->p1*x*y + t->p2*(r2 + 2.0*x*x);
        double dy = t->p1*(r2 + 2.0*y*y) + 2.0*t->p2*x*y;
        x = (xd - dx) / radial;
        y = (yd - dy) / radial;
    }
    *out_u = (float)(fx * x + cx);
    *out_v = (float)(fy * y + cy);
}

/* ------------------------------------------------------------------ */
/* APRILTAG POSE -> ARCore CAMERA POSE                                  */
/* ------------------------------------------------------------------ */

/* Convert the tag pose returned by AprilTag (tag in camera optical frame) to
 * the camera pose in the shim's world frame.  The display rotation is applied
 * exactly as before so existing games keep the same coordinate convention. */
static int mt_camera_from_tag_pose(const apriltag_pose_t *pose, int world_mode,
                                   double c[3], double q[4]) {
    if (!pose->R || !pose->t) return 0;

    double R[3][3], t[3];
    for (int r = 0; r < 3; r++) {
        for (int k = 0; k < 3; k++) R[r][k] = matd_get(pose->R, r, k);
        t[r] = matd_get(pose->t, r, 0);
    }

    const double F[3] = {1.0, -1.0, -1.0};
    double Rg[3][3];
    for (int r = 0; r < 3; r++)
        for (int k = 0; k < 3; k++) Rg[r][k] = F[r] * R[r][k];
    double tg[3] = {F[0]*t[0], F[1]*t[1], F[2]*t[2]};

    double theta = 0.0;
    if (g_display_rotation == 0) theta = -M_PI / 2.0;
    else if (g_display_rotation == 2) theta = M_PI / 2.0;
    else if (g_display_rotation == 1) theta = 0.0;
    else if (g_display_rotation == 3) theta = M_PI;

    double cc = cos(theta), ss = sin(theta);
    double Rz[3][3] = {{cc, -ss, 0.0}, {ss, cc, 0.0}, {0.0, 0.0, 1.0}};
    double Rf[3][3];
    for (int r = 0; r < 3; r++) {
        for (int k = 0; k < 3; k++) {
            Rf[r][k] = Rz[r][0]*Rg[0][k] + Rz[r][1]*Rg[1][k] + Rz[r][2]*Rg[2][k];
        }
    }
    double tf[3];
    for (int r = 0; r < 3; r++)
        tf[r] = Rz[r][0]*tg[0] + Rz[r][1]*tg[1] + Rz[r][2]*tg[2];
    memcpy(Rg, Rf, sizeof(Rf));
    memcpy(tg, tf, sizeof(tf));

    if (world_mode == 1) {
        /* The tag frame's up is -Z; ARCore/games expect +Y up. */
        static const double A_T[3][3] = {
            {1, 0, 0},
            {0, 0, 1},
            {0, -1, 0}
        };
        double RW[3][3];
        for (int r = 0; r < 3; r++) {
            for (int k = 0; k < 3; k++) {
                RW[r][k] = Rg[r][0]*A_T[0][k] + Rg[r][1]*A_T[1][k] + Rg[r][2]*A_T[2][k];
            }
        }
        memcpy(Rg, RW, sizeof(RW));
    }

    double Rc[3][3];
    for (int r = 0; r < 3; r++)
        for (int k = 0; k < 3; k++) Rc[r][k] = Rg[k][r];
    for (int r = 0; r < 3; r++)
        c[r] = -(Rc[r][0]*tg[0] + Rc[r][1]*tg[1] + Rc[r][2]*tg[2]);
    mt_mat_to_quat(Rc, q);

    return isfinite(c[0]) && isfinite(c[1]) && isfinite(c[2]) &&
           isfinite(q[0]) && isfinite(q[1]) && isfinite(q[2]) && isfinite(q[3]);
}

/* ------------------------------------------------------------------ */
/* FILTER AND PREDICTION                                               */
/* ------------------------------------------------------------------ */

static void mt_tracking_expire_locked(int64_t now, const MarkerTuning *t, int *lost) {
    if (lost) *lost = 0;
    if (g_ms.tracking && now - g_ms.last_det_ns > (int64_t)t->hold_ms * 1000000LL) {
        g_ms.tracking = 0;
        if (lost) *lost = 1;
    }
}

static int mt_pose_at(int64_t now, double c[3], double q[4], int *tracking) {
    MarkerTuning t;
    mt_tuning_snapshot(&t);

    pthread_mutex_lock(&g_ms.lock);
    if (!g_ms.valid) {
        pthread_mutex_unlock(&g_ms.lock);
        if (tracking) *tracking = 0;
        return 0;
    }

    int lost = 0;
    mt_tracking_expire_locked(now, &t, &lost);
    if (lost) shim_log("tracking lost (timeout)");

    memcpy(c, g_ms.c, sizeof(g_ms.c));
    memcpy(q, g_ms.q, sizeof(g_ms.q));
    int tr = g_ms.tracking;
    int64_t stamp = g_ms.stamp_ns;
    double v[3], w[3];
    memcpy(v, g_ms.v, sizeof(v));
    memcpy(w, g_ms.w, sizeof(w));
    pthread_mutex_unlock(&g_ms.lock);

    if (t.predict_on && t.lead > 0.0f && tr) {
        double dt = (double)(now - stamp) / 1e9;
        if (dt < 0.0) dt = 0.0;
        double maxdt = (double)t.predict_ms / 1000.0;
        if (maxdt > 0.0 && dt > maxdt) {
            /* Do not extrapolate indefinitely: keep at most predict_ms of
             * motion, then freeze until the next detection. */
            dt = maxdt;
        }
        double g = (double)t.lead;
        for (int i = 0; i < 3; i++) c[i] += v[i] * dt * g;
        mt_quat_apply_world(w, dt * g, q);
    }

    if (tracking) *tracking = tr;
    return 1;
}

static void mt_filter_reset(const double c[3], const double q[4], int64_t stamp) {
    memcpy(g_ms.c, c, sizeof(g_ms.c));
    memcpy(g_ms.q, q, sizeof(g_ms.q));
    mt_quat_normalize(g_ms.q);
    memset(g_ms.v, 0, sizeof(g_ms.v));
    memset(g_ms.w, 0, sizeof(g_ms.w));
    g_ms.stamp_ns = stamp;
    g_ms.valid = 1;
}

static void mt_filter_update(const MarkerTuning *t, const double c[3],
                             const double q_in[4], int64_t stamp,
                             const double vm_in[3], const double wm_in[3],
                             int have_motion, double motion_dt) {
    double q[4] = {q_in[0], q_in[1], q_in[2], q_in[3]};
    double dt = (double)(stamp - g_ms.stamp_ns) / 1e9;
    if (!g_ms.valid || dt <= 1e-4 || dt > (double)t->reset_ms / 1000.0) {
        mt_filter_reset(c, q, stamp);
        return;
    }

    double dot = q[0]*g_ms.q[0] + q[1]*g_ms.q[1] + q[2]*g_ms.q[2] + q[3]*g_ms.q[3];
    if (dot < 0.0) {
        for (int i = 0; i < 4; i++) q[i] = -q[i];
    }

    /* Velocity comes from raw measurements, not from the filtered pose: using
     * the filtered pose biases the velocity by the filter lag and makes the
     * prediction overshoot/oscillate during motion. */
    double vm[3] = {0.0, 0.0, 0.0};
    double wm[3] = {0.0, 0.0, 0.0};
    if (have_motion) {
        memcpy(vm, vm_in, sizeof(vm));
        memcpy(wm, wm_in, sizeof(wm));
    }
    double speed = mt_vec3_norm(vm);
    if (speed > 10.0) {
        double s = 10.0 / speed;
        for (int i = 0; i < 3; i++) vm[i] *= s;
        speed = 10.0;
    }
    double omega = mt_vec3_norm(wm);
    if (omega > 20.0) {
        double s = 20.0 / omega;
        for (int i = 0; i < 3; i++) wm[i] *= s;
        omega = 20.0;
    }

    double newc[3];
    if (t->filter_on) {
        double cutoff = t->f_min + t->beta_pos * speed;
        double alpha = dt / (dt + 1.0 / (2.0 * M_PI * cutoff));
        for (int i = 0; i < 3; i++) newc[i] = g_ms.c[i] + alpha * (c[i] - g_ms.c[i]);

        /* Smooth the measured velocity.  The time constant is independent of
         * the pose cutoff, so prediction stays stable when the hand shakes. */
        double av = 1.0 - exp(-motion_dt / 0.10);
        if (av > 0.5) av = 0.5;
        if (have_motion) {
            for (int i = 0; i < 3; i++) g_ms.v[i] += av * (vm[i] - g_ms.v[i]);
            for (int i = 0; i < 3; i++) g_ms.w[i] += av * (wm[i] - g_ms.w[i]);
        } else {
            memset(g_ms.v, 0, sizeof(g_ms.v));
            memset(g_ms.w, 0, sizeof(g_ms.w));
        }
    } else {
        memcpy(newc, c, sizeof(newc));
        if (have_motion) {
            memcpy(g_ms.v, vm, sizeof(g_ms.v));
            memcpy(g_ms.w, wm, sizeof(g_ms.w));
        } else {
            memset(g_ms.v, 0, sizeof(g_ms.v));
            memset(g_ms.w, 0, sizeof(g_ms.w));
        }
    }

    double newq[4];
    if (t->filter_on) {
        double cutoff = t->f_min + t->beta_rot * omega;
        double alpha = dt / (dt + 1.0 / (2.0 * M_PI * cutoff));
        mt_quat_slerp(g_ms.q, q, alpha, newq);
    } else {
        memcpy(newq, q, sizeof(newq));
    }
    mt_quat_normalize(newq);

    memcpy(g_ms.c, newc, sizeof(newc));
    memcpy(g_ms.q, newq, sizeof(newq));
    g_ms.stamp_ns = stamp;
}

/* ------------------------------------------------------------------ */
/* POSE CANDIDATE SELECTION                                            */
/* ------------------------------------------------------------------ */

static double mt_candidate_cost(double rms_px, const double c[3], const double q[4],
                                int have_pred, const double pc[3], const double pq[4],
                                const MarkerTuning *t) {
    double cost = rms_px;
    if (have_pred) {
        double max_jump = t->max_jump_m > 0.01f ? t->max_jump_m : 0.01;
        double max_ang = t->max_ang_deg > 1.0f ? t->max_ang_deg : 1.0;
        cost += 2.0 * mt_vec3_dist(c, pc) / max_jump;
        cost += 1.5 * mt_quat_angle_deg(q, pq) / max_ang;
    }
    if (rms_px > t->max_px) {
        cost += 5.0 * (rms_px / t->max_px - 1.0);
    }
    return cost;
}

static double mt_quad_area(const apriltag_detection_t *det) {
    double area = 0.0;
    for (int i = 0; i < 4; i++) {
        int j = (i + 1) % 4;
        area += det->p[i][0] * det->p[j][1] - det->p[j][0] * det->p[i][1];
    }
    return fabs(area) * 0.5;
}

static int mt_make_candidate(const apriltag_detection_t *det, const MarkerTuning *t,
                             float fx, float fy, float cx, float cy,
                             int have_pred, const double pc[3], const double pq[4],
                             MarkerCandidate *out) {
    apriltag_detection_t d = *det;
    if (t->dist_on) {
        for (int i = 0; i < 4; i++) {
            float u, v;
            mt_undistort_px(det->p[i][0], det->p[i][1], fx, fy, cx, cy, t, &u, &v);
            d.p[i][0] = u;
            d.p[i][1] = v;
        }
    }

    apriltag_detection_info_t info;
    info.det = &d;
    info.tagsize = t->tag_size;
    info.fx = fx;
    info.fy = fy;
    info.cx = cx;
    info.cy = cy;

    double err1 = 0.0, err2 = 0.0;
    apriltag_pose_t pose1 = {NULL, NULL};
    apriltag_pose_t pose2 = {NULL, NULL};
    estimate_tag_pose_orthogonal_iteration(&info, &err1, &pose1, &err2, &pose2, 50);

    int found = 0;
    double best_cost = 0.0;
    double best_c[3] = {0, 0, 0}, best_q[4] = {0, 0, 0, 1}, best_rms = 0.0;

    for (int k = 0; k < 2; k++) {
        apriltag_pose_t *pose = k == 0 ? &pose1 : &pose2;
        double err = k == 0 ? err1 : err2;
        if (!pose->R || !pose->t || !isfinite(err) || err < 0.0) continue;

        double rms = (fx > 0.0f) ? sqrt(err / 4.0) * fx : 0.0;
        if (!isfinite(rms)) continue;

        double c[3], q[4];
        if (!mt_camera_from_tag_pose(pose, t->world_mode, c, q)) continue;

        double cost = mt_candidate_cost(rms, c, q, have_pred, pc, pq, t);
        if (!found || cost < best_cost) {
            found = 1;
            best_cost = cost;
            best_rms = rms;
            memcpy(best_c, c, sizeof(best_c));
            memcpy(best_q, q, sizeof(best_q));
        }
    }

    if (pose1.R) matd_destroy(pose1.R);
    if (pose1.t) matd_destroy(pose1.t);
    if (pose2.R) matd_destroy(pose2.R);
    if (pose2.t) matd_destroy(pose2.t);

    if (!found) return 0;

    out->id = det->id;
    out->area = mt_quad_area(det);
    out->margin = det->decision_margin;
    out->hamming = det->hamming;
    out->rms_px = best_rms;
    out->cost = best_cost;
    memcpy(out->c, best_c, sizeof(out->c));
    memcpy(out->q, best_q, sizeof(out->q));
    return 1;
}

/* ------------------------------------------------------------------ */
/* ACCEPT / REJECT                                                     */
/* ------------------------------------------------------------------ */

static void mt_accept(const MarkerCandidate *cand, int64_t capture_ns,
                      int64_t accept_ns, const MarkerTuning *t,
                      int have_pred, const double pc[3], const double pq[4],
                      int was_tracking) {
    int outlier = 0;
    int accepted_jump = 0;
    double jump_d = 0.0, jump_ang = 0.0;

    pthread_mutex_lock(&g_ms.lock);

    if (have_pred && was_tracking) {
        jump_d = mt_vec3_dist(cand->c, pc);
        jump_ang = mt_quat_angle_deg(cand->q, pq);
        if (jump_d > t->max_jump_m || jump_ang > t->max_ang_deg) {
            g_ms.reject_run++;
            if (g_ms.reject_run < 3) {
                outlier = 1;
            } else {
                accepted_jump = 1;
                g_ms.reject_run = 0;
            }
        } else {
            g_ms.reject_run = 0;
        }
    } else {
        g_ms.reject_run = 0;
    }

    if (!outlier) {
        int reset = !g_ms.valid || !was_tracking ||
                    (capture_ns - g_ms.stamp_ns) > (int64_t)t->reset_ms * 1000000LL ||
                    accepted_jump;

        double vm[3] = {0.0, 0.0, 0.0};
        double wm[3] = {0.0, 0.0, 0.0};
        double motion_dt = 0.0;
        int have_motion = 0;

        if (!reset && g_ms.raw_valid && g_ms.raw_ns > 0 && capture_ns > g_ms.raw_ns) {
            motion_dt = (double)(capture_ns - g_ms.raw_ns) / 1e9;
            if (motion_dt > 1e-3 && motion_dt < 0.5) {
                for (int i = 0; i < 3; i++) {
                    vm[i] = (cand->c[i] - g_ms.raw_c[i]) / motion_dt;
                }
                double speed = mt_vec3_norm(vm);
                if (speed > 10.0) {
                    double s = 10.0 / speed;
                    for (int i = 0; i < 3; i++) vm[i] *= s;
                }

                double cq[4], dq[4];
                mt_quat_conj(g_ms.raw_q, cq);
                mt_quat_mul(cand->q, cq, dq);
                if (dq[3] < 0.0) {
                    for (int i = 0; i < 4; i++) dq[i] = -dq[i];
                }
                double s2 = sqrt(dq[0]*dq[0] + dq[1]*dq[1] + dq[2]*dq[2]);
                if (s2 > 1e-9) {
                    double ang = 2.0 * atan2(s2, dq[3]);
                    wm[0] = dq[0] / s2 * ang / motion_dt;
                    wm[1] = dq[1] / s2 * ang / motion_dt;
                    wm[2] = dq[2] / s2 * ang / motion_dt;
                }
                have_motion = 1;
            }
        }

        if (reset) {
            mt_filter_reset(cand->c, cand->q, capture_ns);
        } else {
            mt_filter_update(t, cand->c, cand->q, capture_ns,
                             vm, wm, have_motion, motion_dt);
        }

        /* raw and filtered jitter metrics */
        if (g_ms.raw_valid) {
            double d = mt_vec3_dist(cand->c, g_ms.raw_c);
            double a = mt_quat_angle_deg(cand->q, g_ms.raw_q);
            g_ms.raw_pos2 += d * d;
            g_ms.raw_rot2 += a * a;
        }
        memcpy(g_ms.raw_c, cand->c, sizeof(g_ms.raw_c));
        memcpy(g_ms.raw_q, cand->q, sizeof(g_ms.raw_q));
        g_ms.raw_ns = capture_ns;
        g_ms.raw_valid = 1;

        if (g_ms.out_valid) {
            double d = mt_vec3_dist(g_ms.c, g_ms.out_c);
            double a = mt_quat_angle_deg(g_ms.q, g_ms.out_q);
            g_ms.out_pos2 += d * d;
            g_ms.out_rot2 += a * a;
        }
        memcpy(g_ms.out_c, g_ms.c, sizeof(g_ms.out_c));
        memcpy(g_ms.out_q, g_ms.q, sizeof(g_ms.out_q));
        g_ms.out_valid = 1;
        g_ms.lag_pos += mt_vec3_dist(cand->c, g_ms.c);
        g_ms.lag_rot += mt_quat_angle_deg(cand->q, g_ms.q);
        g_ms.sum_rms += cand->rms_px;
        g_ms.sum_margin += cand->margin;
        g_ms.measurements++;

        g_ms.valid = 1;
        g_ms.tracking = 1;
        g_ms.last_det_ns = accept_ns;
        g_ms.last_id = cand->id;
        g_ms.last_area = cand->area;
        g_ms.miss_run = 0;

        int first = !was_tracking;
        pthread_mutex_unlock(&g_ms.lock);

        if (accepted_jump) {
            shim_log("jump accepted after 3 rejects d=%.3f ang=%.1f", jump_d, jump_ang);
        }
        if (first) {
            double dist = mt_vec3_norm(cand->c);
            shim_log("tracking on id=%d dist=%.3f rms=%.2fpx margin=%.0f",
                     cand->id, dist, cand->rms_px, cand->margin);
        }
    } else {
        pthread_mutex_unlock(&g_ms.lock);
        if (mt_log_gate()) {
            shim_log("reject jump d=%.3f ang=%.1f rms=%.2fpx",
                     jump_d, jump_ang, cand->rms_px);
        }
    }
}

static void mt_note_miss(int64_t now) {
    MarkerTuning t;
    mt_tuning_snapshot(&t);
    int lost = 0;
    pthread_mutex_lock(&g_ms.lock);
    g_ms.miss_run++;
    mt_tracking_expire_locked(now, &t, &lost);
    pthread_mutex_unlock(&g_ms.lock);
    if (lost) shim_log("tracking lost (no tag)");
}

static int mt_is_tracking(void) {
    MarkerTuning t;
    mt_tuning_snapshot(&t);
    int lost = 0;
    pthread_mutex_lock(&g_ms.lock);
    mt_tracking_expire_locked(now_ns(), &t, &lost);
    int tr = g_ms.tracking;
    pthread_mutex_unlock(&g_ms.lock);
    if (lost) shim_log("tracking lost (timeout)");
    return tr;
}

/* ------------------------------------------------------------------ */
/* PER-FRAME DETECTION                                                 */
/* ------------------------------------------------------------------ */

static void mt_log_metrics_locked(int64_t now) {
    if (g_ms.measurements <= 0 && g_ms.proc_n <= 0) return;
    if (now - g_ms.stat_ns < 2000000000LL) return;
    g_ms.stat_ns = now;

    double n = (double)g_ms.measurements;
    double raw_p = n > 0.0 ? sqrt(g_ms.raw_pos2 / n) * 1000.0 : 0.0;
    double raw_r = n > 0.0 ? sqrt(g_ms.raw_rot2 / n) : 0.0;
    double out_p = n > 0.0 ? sqrt(g_ms.out_pos2 / n) * 1000.0 : 0.0;
    double out_r = n > 0.0 ? sqrt(g_ms.out_rot2 / n) : 0.0;
    double avg_ms = g_ms.proc_n > 0 ? (double)g_ms.proc_ns / g_ms.proc_n / 1e6 : 0.0;
    double max_ms = (double)g_ms.proc_max_ns / 1e6;
    shim_log("metrics rms=%.2fpx margin=%.0f raw=%.1fmm/%.2fdeg out=%.1fmm/%.2fdeg "
             "lag=%.1fmm/%.2fdeg det=%d worker=%.1fms(max %.1f) drop=%d",
             n > 0.0 ? g_ms.sum_rms / n : 0.0,
             n > 0.0 ? g_ms.sum_margin / n : 0.0,
             raw_p, raw_r, out_p, out_r,
             n > 0.0 ? g_ms.lag_pos / n * 1000.0 : 0.0,
             n > 0.0 ? g_ms.lag_rot / n : 0.0,
             g_ms.measurements, avg_ms, max_ms, g_ms.dropped);

    g_ms.raw_pos2 = g_ms.raw_rot2 = 0.0;
    g_ms.out_pos2 = g_ms.out_rot2 = 0.0;
    g_ms.lag_pos = g_ms.lag_rot = 0.0;
    g_ms.sum_rms = g_ms.sum_margin = 0.0;
    g_ms.measurements = 0;
    g_ms.dropped = 0;
    g_ms.proc_ns = 0;
    g_ms.proc_max_ns = 0;
    g_ms.proc_n = 0;
}

static void mt_process_frame(const MarkerFrame *f) {
    MarkerTuning t;
    mt_tuning_snapshot(&t);

    int64_t now = f->capture_ns;
    double pc[3], pq[4];
    int tracking = 0;
    int have_pred = mt_pose_at(now, pc, pq, &tracking);
    if (!tracking) have_pred = 0;

    int last_id = -1;
    double last_area = 0.0;
    int64_t last_det = 0;
    pthread_mutex_lock(&g_ms.lock);
    last_id = g_ms.last_id;
    last_area = g_ms.last_area;
    last_det = g_ms.last_det_ns;
    pthread_mutex_unlock(&g_ms.lock);

    if (!g_mt_td || !f->data || f->w <= 0 || f->h <= 0) return;
    g_mt_td->nthreads = t.threads;
    g_mt_td->refine_edges = t.refine_edges ? true : false;
    if (tracking && last_area >= 600.0) {
        g_mt_td->quad_decimate = t.decimate_track;
    } else {
        g_mt_td->quad_decimate = t.decimate_search;
    }

    image_u8_t im = {
        .width = f->w,
        .height = f->h,
        .stride = f->w,
        .buf = f->data,
    };
    int64_t proc_start = now_ns();
    zarray_t *dets = apriltag_detector_detect(g_mt_td, &im);
    int64_t proc_time = now_ns() - proc_start;

    MarkerCandidate best, fallback;
    int have_best = 0, have_fallback = 0;
    int ndets = zarray_size(dets);

    for (int i = 0; i < ndets; i++) {
        apriltag_detection_t *det = NULL;
        zarray_get(dets, i, &det);
        if (!det) continue;
        if (t.tag_id >= 0 && det->id != t.tag_id) continue;
        if (tracking && t.lock_tag && last_id >= 0 && det->id != last_id) continue;

        double area = mt_quad_area(det);
        if (area < t.min_area) continue;

        int strong = det->hamming <= t.max_hamming &&
                     det->decision_margin >= t.min_margin;

        MarkerCandidate cand;
        if (!mt_make_candidate(det, &t, f->fx, f->fy, f->cx, f->cy,
                               have_pred, pc, pq, &cand)) {
            continue;
        }
        if (cand.area < t.min_area) continue;
        cand.strong = strong;

        /* Prefer larger tags when the geometric costs are close: a tiny tag
         * far away can have a slightly lower reprojection error but a much
         * noisier pose. */
        double score = cand.cost + 100.0 / (cand.area > 1.0 ? cand.area : 1.0);
        if (!strong) score += 3.0;
        if (last_id >= 0 && cand.id != last_id) score += 0.5;

        if (strong) {
            if (!have_best || score < best.cost) {
                best = cand;
                best.cost = score;
                have_best = 1;
            }
        } else {
            if (!have_fallback || score < fallback.cost) {
                fallback = cand;
                fallback.cost = score;
                have_fallback = 1;
            }
        }
    }

    if (!have_best && have_fallback && (!tracking || now - last_det > 300000000LL)) {
        best = fallback;
        have_best = 1;
        if (mt_log_gate()) {
            shim_log("weak tag id=%d hamming=%d margin=%.0f rms=%.2fpx",
                     best.id, best.hamming, best.margin, best.rms_px);
        }
    }

    if (!have_best) {
        mt_note_miss(now_ns());
    } else if (tracking && best.rms_px > t.max_px * 4.0) {
        /* A visible but geometrically impossible pose must not move the
         * world.  Wait for a better frame. */
        if (mt_log_gate()) shim_log("bad pose rms=%.2fpx ignored", best.rms_px);
        mt_note_miss(now_ns());
    } else {
        mt_accept(&best, f->capture_ns, now_ns(), &t, have_pred, pc, pq, tracking);
    }

    pthread_mutex_lock(&g_ms.lock);
    g_ms.proc_ns += proc_time;
    if (proc_time > g_ms.proc_max_ns) g_ms.proc_max_ns = proc_time;
    g_ms.proc_n++;
    mt_log_metrics_locked(now_ns());
    pthread_mutex_unlock(&g_ms.lock);

    apriltag_detections_destroy(dets);
}

static void mt_detector_init(void) {
    if (g_mt_td) return;
    g_mt_tf = tag36h11_create();
    g_mt_td = apriltag_detector_create();
    apriltag_detector_add_family(g_mt_td, g_mt_tf);
    g_mt_td->quad_decimate = 1.0f;
    g_mt_td->nthreads = 2;
    g_mt_td->refine_edges = true;
}

static void *mt_worker_main(void *arg) {
    (void)arg;
    /* Keep the GL thread ahead of marker detection.  A positive nice value is
     * all Android lets an app set without special privileges. */
    (void)setpriority(PRIO_PROCESS, 0, 10);
    mt_detector_init();
    int64_t last_tune = 0;

    for (;;) {
        pthread_mutex_lock(&g_mq_lock);
        while (1) {
            int idx = -1;
            uint64_t best_seq = 0;
            for (int i = 0; i < MT_QUEUE_BUFFERS; i++) {
                if (g_mq[i].state == 1 && (idx < 0 || g_mq[i].seq > best_seq)) {
                    idx = i;
                    best_seq = g_mq[i].seq;
                }
            }
            if (idx >= 0) {
                g_mq[idx].state = 2;
                pthread_mutex_unlock(&g_mq_lock);
                int64_t now = now_ns();
                if (now - last_tune > 1000000000LL) {
                    last_tune = now;
                    mt_load_tuning();
                }
                mt_process_frame(&g_mq[idx]);
                pthread_mutex_lock(&g_mq_lock);
                g_mq[idx].state = 0;
                break;
            }
            pthread_cond_wait(&g_mq_cond, &g_mq_lock);
        }
        pthread_mutex_unlock(&g_mq_lock);
    }
    return NULL;
}

/* ------------------------------------------------------------------ */
/* CAPTURE (called from ArSession_update, on the GL thread)            */
/* ------------------------------------------------------------------ */

#ifndef MT_NO_CAPTURE
static void mt_capture(const ArSession *session, const ArFrame *frame) {
    if (mt_poll_freeze()) return;

    int64_t frame_ts = 0;
    real_ArFrame_getTimestamp(session, frame, &frame_ts);
    if (g_mt_have_frame_ts && frame_ts != 0 && frame_ts == g_mt_last_frame_ts) {
        return;
    }
    if (frame_ts != 0) {
        g_mt_last_frame_ts = frame_ts;
        g_mt_have_frame_ts = 1;
    }

    float fx = 464.56f, fy = 464.56f, cx = 320.0f, cy = 240.0f;
    ArCamera *camera = NULL;
    real_ArFrame_acquireCamera(session, frame, &camera);
    if (camera) {
        ArCameraIntrinsics *intr = NULL;
        real_ArCameraIntrinsics_create(session, &intr);
        if (intr) {
            real_ArCamera_getImageIntrinsics(session, camera, intr);
            real_ArCameraIntrinsics_getFocalLength(session, intr, &fx, &fy);
            real_ArCameraIntrinsics_getPrincipalPoint(session, intr, &cx, &cy);
            real_ArCameraIntrinsics_destroy(intr);
        }
        real_ArCamera_release(camera);
    }

    ArImage *image = NULL;
    if (real_ArFrame_acquireCameraImage((ArSession *)session, (ArFrame *)frame, &image) != AR_SUCCESS || !image) {
        return;
    }

    int32_t w = 0, h = 0, row_stride = 0, pix_stride = 1;
    real_ArImage_getWidth(session, image, &w);
    real_ArImage_getHeight(session, image, &h);
    real_ArImage_getPlaneRowStride(session, image, 0, &row_stride);
    real_ArImage_getPlanePixelStride(session, image, 0, &pix_stride);
    const uint8_t *data = NULL;
    int32_t data_len = 0;
    real_ArImage_getPlaneData(session, image, 0, &data, &data_len);
    if (w <= 0 || h <= 0 || !data || row_stride <= 0 || pix_stride <= 0) {
        real_ArImage_release(image);
        return;
    }

    pthread_mutex_lock(&g_mq_lock);
    int idx = -1;
    for (int i = 0; i < MT_QUEUE_BUFFERS; i++) {
        if (g_mq[i].state == 0) { idx = i; break; }
    }
    if (idx < 0) {
        /* No free buffer: reuse the oldest ready one, never a busy one. */
        uint64_t oldest = 0;
        for (int i = 0; i < MT_QUEUE_BUFFERS; i++) {
            if (g_mq[i].state == 1 && (idx < 0 || g_mq[i].seq < oldest)) {
                idx = i;
                oldest = g_mq[i].seq;
            }
        }
    }
    if (idx < 0) {
        pthread_mutex_unlock(&g_mq_lock);
        pthread_mutex_lock(&g_ms.lock);
        g_ms.dropped++;
        pthread_mutex_unlock(&g_ms.lock);
        real_ArImage_release(image);
        return;
    }

    size_t need = (size_t)w * (size_t)h;
    if (g_mq[idx].capacity < (int)need) {
        uint8_t *p = (uint8_t *)realloc(g_mq[idx].data, need);
        if (!p) {
            pthread_mutex_unlock(&g_mq_lock);
            real_ArImage_release(image);
            return;
        }
        g_mq[idx].data = p;
        g_mq[idx].capacity = (int)need;
    }

    uint8_t *dst = g_mq[idx].data;
    if (pix_stride == 1 && row_stride == w) {
        memcpy(dst, data, need);
    } else {
        for (int y = 0; y < h; y++) {
            const uint8_t *src = data + (size_t)y * row_stride;
            uint8_t *row = dst + (size_t)y * w;
            for (int x = 0; x < w; x++) row[x] = src[x * pix_stride];
        }
    }

    g_mq[idx].w = w;
    g_mq[idx].h = h;
    g_mq[idx].fx = fx;
    g_mq[idx].fy = fy;
    g_mq[idx].cx = cx;
    g_mq[idx].cy = cy;
    int64_t enqueue_ns = now_ns();
    int64_t capture_ns = enqueue_ns;
    if (frame_ts > 0) {
        int64_t age = enqueue_ns - frame_ts;
        // Same clock base (BOOTTIME) and a sane camera latency: use the actual
        // exposure timestamp.  Otherwise compensate with a conservative
        // pipeline latency so the world does not follow the camera.
        if (age >= 0 && age <= 500000000LL) {
            capture_ns = frame_ts;
        } else {
            MarkerTuning t;
            mt_tuning_snapshot(&t);
            capture_ns = enqueue_ns - (int64_t)t.latency_ms * 1000000LL;
        }
    }
    g_mq[idx].capture_ns = capture_ns;
    if (!g_mt_ts_logged) {
        g_mt_ts_logged = 1;
        shim_log("timestamp frame=%lld enqueue=%lld age=%.1fms capture=%lld",
                 (long long)frame_ts, (long long)enqueue_ns,
                 frame_ts > 0 ? (double)(enqueue_ns - frame_ts) / 1e6 : -1.0,
                 (long long)capture_ns);
    }
    g_mq[idx].seq = ++g_mq_seq;
    g_mq[idx].state = 1;

    if (!g_mq_worker) {
        pthread_t thread;
        if (pthread_create(&thread, NULL, mt_worker_main, NULL) == 0) {
            /* Detached by design: the shim lives for the whole process. */
            pthread_detach(thread);
            g_mq_worker = 1;
        } else {
            shim_log("failed to start marker worker");
        }
    }
    pthread_cond_signal(&g_mq_cond);
    pthread_mutex_unlock(&g_mq_lock);

    real_ArImage_release(image);
}
#endif /* MT_NO_CAPTURE */

#endif /* MARKER_TRACKING_INC */
