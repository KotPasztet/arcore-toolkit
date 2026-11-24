/* ARCore C API shim overrides: AprilTag marker tracking.
 * Included at the end of the generated shim.c (real_* pointers visible).
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <math.h>
#include <stdio.h>
#include <stdarg.h>
#include <fcntl.h>
#include <unistd.h>
#include "apriltag.h"
#include "tag36h11.h"
#include "apriltag_pose.h"
#include "common/image_u8.h"

/* Target application: its package name defines where the flags and logs live.
 * Override with -DTARGET_PKG=\"com.example.app\" at build time. */
#ifndef TARGET_PKG
#define TARGET_PKG "com.kotpasztet.apace"
#endif
#define APP_DIR "/sdcard/Android/data/" TARGET_PKG "/files/"

#define MAGIC_LIST   0x4C495354u
#define MAGIC_TRACK  0x54524143u
#define MAGIC_ANCHOR 0x414E4348u
#define MAGIC_CLOUD  0x434C4F44u
#define MAGIC_POSE   0x504F5345u

/* Printed tag: outer black square 150 mm, print scale assumed 0.99. */
#define TAG_SIZE_M 0.1485f

typedef struct { uint32_t magic; int32_t size; } FakeList;
typedef struct { uint32_t magic; float m[16]; } FakeAnchor;
typedef struct { uint32_t magic; float m[16]; } FakePose;

static apriltag_detector_t *g_td;
static apriltag_family_t *g_tf;
static volatile int g_tracking;
static volatile int g_seen;
static float g_view[16];
static float g_cam[16];
static int64_t g_last_ns;

static int64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static int g_log_fd = -1;
static volatile int g_display_rotation = 0;
static void shim_log(const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, "ARCORE_SHIM", "%s", buf);
    if (g_log_fd < 0) {
        g_log_fd = open(APP_DIR "shim.log",
                        O_WRONLY | O_CREAT | O_TRUNC, 0666);
        if (g_log_fd < 0) return;
        const char *hdr = "# shim log started\n";
        (void)!write(g_log_fd, hdr, strlen(hdr));
    }
    char line[640];
    int n = snprintf(line, sizeof(line), "%lld %d %s\n", (long long)now_ns(), (int)gettid(), buf);
    if (n > 0) (void)!write(g_log_fd, line, (size_t)n);
}

static int g_trace_fd = -1;
static void trace(const char *name) {
    if (g_trace_fd < 0) {
        g_trace_fd = open(APP_DIR "trace.log",
                          O_WRONLY | O_CREAT | O_TRUNC, 0666);
        if (g_trace_fd < 0) return;
    }
    char b[128];
    int n = snprintf(b, sizeof(b), "%lld %d %s\n", (long long)now_ns(), (int)gettid(), name);
    if (n > 0) (void)!write(g_trace_fd, b, (size_t)n);
}

static void marker_init(void) {
    if (g_td) return;
    g_tf = tag36h11_create();
    g_td = apriltag_detector_create();
    apriltag_detector_add_family(g_td, g_tf);
    g_td->quad_decimate = 1.0f;
    g_td->nthreads = 2;
    g_td->refine_edges = 1;
}

static void load_tuning(void);

static volatile int g_freeze;
static int g_call_count;

static void poll_freeze(void) {
    if ((g_call_count++ & 31) != 0) return;
    load_tuning();
    int v = 0;
    int fd = open(APP_DIR "freeze", O_RDONLY);
    if (fd >= 0) { v = 1; close(fd); }
    if (v != g_freeze) {
        g_freeze = v;
        shim_log("freeze=%d", v);
    }
}


/* ================= tracking quality improvements ================= */


/* Defaults: distortion from the Kalibr camera calibration (radtan: k1,k2,p1,p2),
 * tag size 150 mm printed at 99 % scale, One Euro filter enabled. */
typedef struct {
    int dist_on;
    double k1, k2, p1, p2;
    float tag_size;
    float min_margin;
    float max_px;
    int filter_on;
    float f_min;
    float beta_pos;
    float beta_rot;
    float max_jump_m;
    float max_ang_deg;
    int predict_on;
    float lead;
} Tuning;

static Tuning g_tune = {
    1, 0.14243204, -0.20529187, 0.00432335, -0.01933716,
    0.1485f, 30.0f, 2.0f, 1, 0.5f, 25.0f, 8.0f, 0.35f, 40.0f, 1, 0.8f
};

static int app_file_exists(const char *name) {
    char p[256];
    snprintf(p, sizeof(p), "%s%s", APP_DIR, name);
    int fd = open(p, O_RDONLY);
    if (fd >= 0) { close(fd); return 1; }
    return 0;
}

static int app_read_doubles(const char *name, double *out, int n) {
    char p[256];
    snprintf(p, sizeof(p), "%s%s", APP_DIR, name);
    FILE *f = fopen(p, "r");
    if (!f) return 0;
    int got = 0;
    for (int i = 0; i < n; i++) {
        if (fscanf(f, "%lf", &out[i]) == 1) got++;
        else break;
    }
    fclose(f);
    return got == n;
}

static void load_tuning(void) {
    double v[4];
    if (app_read_doubles("dist", v, 4)) {
        g_tune.k1 = v[0]; g_tune.k2 = v[1]; g_tune.p1 = v[2]; g_tune.p2 = v[3];
    }
    g_tune.dist_on = !app_file_exists("dist_off");
    g_tune.filter_on = !app_file_exists("filter_off");
    if (app_read_doubles("tag_size", v, 1) && v[0] > 50.0 && v[0] < 1000.0) {
        g_tune.tag_size = (float)(v[0] / 1000.0);
    }
    if (app_read_doubles("margin", v, 1)) g_tune.min_margin = (float)v[0];
    if (app_read_doubles("max_px", v, 1) && v[0] > 0.0) g_tune.max_px = (float)v[0];
    if (app_read_doubles("max_jump", v, 1) && v[0] > 0.0) g_tune.max_jump_m = (float)v[0];
    if (app_read_doubles("max_ang", v, 1) && v[0] > 0.0) g_tune.max_ang_deg = (float)v[0];
    if (app_read_doubles("euro", v, 3)) {
        if (v[0] > 0.05) g_tune.f_min = (float)v[0];
        if (v[1] >= 0.0) g_tune.beta_pos = (float)v[1];
        if (v[2] >= 0.0) g_tune.beta_rot = (float)v[2];
    }
    g_tune.predict_on = !app_file_exists("predict_off");
    if (app_read_doubles("lead", v, 1) && v[0] >= 0.0 && v[0] <= 2.0) g_tune.lead = (float)v[0];
    static int logged;
    if (!logged) {
        logged = 1;
        shim_log("tune dist=%d(%.4f,%.4f,%.4f,%.4f) tag=%.4f margin=%.0f maxpx=%.1f "
                 "filter=%d jump=%.2f/%.0f predict=%d lead=%.2f",
                 g_tune.dist_on, g_tune.k1, g_tune.k2, g_tune.p1, g_tune.p2,
                 g_tune.tag_size, g_tune.min_margin, g_tune.max_px,
                 g_tune.filter_on, g_tune.max_jump_m, g_tune.max_ang_deg,
                 g_tune.predict_on, g_tune.lead);
    }
}

/* Inverse of the OpenCV/Kalibr radtan model, so the pinhole solvePnP sees
 * undistorted corners. */
static void undistort_px(float u, float v, float fx, float fy, float cx, float cy,
                         float *out_u, float *out_v) {
    if (!g_tune.dist_on || fx <= 0.0f || fy <= 0.0f) { *out_u = u; *out_v = v; return; }
    double xd = ((double)u - cx) / fx;
    double yd = ((double)v - cy) / fy;
    double x = xd, y = yd;
    for (int i = 0; i < 8; i++) {
        double r2 = x * x + y * y;
        double radial = 1.0 + g_tune.k1 * r2 + g_tune.k2 * r2 * r2;
        double dx = 2.0 * g_tune.p1 * x * y + g_tune.p2 * (r2 + 2.0 * x * x);
        double dy = g_tune.p1 * (r2 + 2.0 * y * y) + 2.0 * g_tune.p2 * x * y;
        x = (xd - dx) / radial;
        y = (yd - dy) / radial;
    }
    *out_u = (float)(fx * x + cx);
    *out_v = (float)(fy * y + cy);
}

static void mat_to_quat(const double R[3][3], double q[4]) {
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
}

static void quat_to_mat(const double q[4], double R[3][3]) {
    double x = q[0], y = q[1], z = q[2], w = q[3];
    R[0][0] = 1.0 - 2.0 * (y * y + z * z);
    R[0][1] = 2.0 * (x * y - z * w);
    R[0][2] = 2.0 * (x * z + y * w);
    R[1][0] = 2.0 * (x * y + z * w);
    R[1][1] = 1.0 - 2.0 * (x * x + z * z);
    R[1][2] = 2.0 * (y * z - x * w);
    R[2][0] = 2.0 * (x * z - y * w);
    R[2][1] = 2.0 * (y * z + x * w);
    R[2][2] = 1.0 - 2.0 * (x * x + y * y);
}

static void quat_mul(const double a[4], const double b[4], double out[4]) {
    out[0] = a[3]*b[0] + a[0]*b[3] + a[1]*b[2] - a[2]*b[1];
    out[1] = a[3]*b[1] - a[0]*b[2] + a[1]*b[3] + a[2]*b[0];
    out[2] = a[3]*b[2] + a[0]*b[1] - a[1]*b[0] + a[2]*b[3];
    out[3] = a[3]*b[3] - a[0]*b[0] - a[1]*b[1] - a[2]*b[2];
}

/* Pose prediction between detections: the view matrix is queried every render
 * frame, so extrapolating the last pose by the smoothed camera velocity removes
 * both the 20 Hz staircase and most of the filter lag. */
static int g_pred_valid;
static double g_pred_c[3], g_pred_v[3];
static double g_pred_q[4], g_pred_w[3];
static int64_t g_pred_ns;

static void predict_pose(double c[3], double q[4]) {
    memcpy(c, g_pred_c, sizeof(g_pred_c));
    memcpy(q, g_pred_q, sizeof(g_pred_q));
    if (!g_tune.predict_on || !g_pred_valid || g_tune.lead <= 0.0f) return;
    double dt = (double)(now_ns() - g_pred_ns) / 1e9;
    if (dt <= 0.0 || dt > 0.09) return;
    double g = g_tune.lead;
    for (int i = 0; i < 3; i++) c[i] += g_pred_v[i] * dt * g;
    double wn = sqrt(g_pred_w[0]*g_pred_w[0] + g_pred_w[1]*g_pred_w[1] + g_pred_w[2]*g_pred_w[2]);
    if (wn > 0.05) {
        double ang = wn * dt * g;
        double axis[3] = {g_pred_w[0]/wn, g_pred_w[1]/wn, g_pred_w[2]/wn};
        double s2 = sin(ang * 0.5);
        double dq[4] = {axis[0]*s2, axis[1]*s2, axis[2]*s2, cos(ang * 0.5)};
        double qn[4];
        quat_mul(dq, q, qn);
        double n = sqrt(qn[0]*qn[0]+qn[1]*qn[1]+qn[2]*qn[2]+qn[3]*qn[3]);
        if (n > 1e-6) { for (int i = 0; i < 4; i++) q[i] = qn[i] / n; }
    }
}

static void view_from_cq(const double c[3], const double q[4], float *m) {
    double Rc[3][3], Rg[3][3], tg[3];
    quat_to_mat(q, Rc);
    for (int r = 0; r < 3; r++)
        for (int k = 0; k < 3; k++) Rg[r][k] = Rc[k][r];
    for (int r = 0; r < 3; r++)
        tg[r] = -(Rg[r][0]*c[0] + Rg[r][1]*c[1] + Rg[r][2]*c[2]);
    m[0] = (float)Rg[0][0]; m[1] = (float)Rg[1][0]; m[2] = (float)Rg[2][0]; m[3] = 0.0f;
    m[4] = (float)Rg[0][1]; m[5] = (float)Rg[1][1]; m[6] = (float)Rg[2][1]; m[7] = 0.0f;
    m[8] = (float)Rg[0][2]; m[9] = (float)Rg[1][2]; m[10] = (float)Rg[2][2]; m[11] = 0.0f;
    m[12] = (float)tg[0]; m[13] = (float)tg[1]; m[14] = (float)tg[2]; m[15] = 1.0f;
}

static void pose_from_cq(const double c[3], const double q[4], float *m) {
    double Rc[3][3];
    quat_to_mat(q, Rc);
    m[0] = (float)Rc[0][0]; m[1] = (float)Rc[1][0]; m[2] = (float)Rc[2][0]; m[3] = 0.0f;
    m[4] = (float)Rc[0][1]; m[5] = (float)Rc[1][1]; m[6] = (float)Rc[2][1]; m[7] = 0.0f;
    m[8] = (float)Rc[0][2]; m[9] = (float)Rc[1][2]; m[10] = (float)Rc[2][2]; m[11] = 0.0f;
    m[12] = (float)c[0]; m[13] = (float)c[1]; m[14] = (float)c[2]; m[15] = 1.0f;
}

/* One Euro filter: adaptive low-pass, heavy smoothing when still, almost none
 * when moving. State is per-component (3 for position, 4 for quaternion). */
typedef struct { double x[4]; double dx[4]; int init; } Euro;

static void euro_reset(Euro *e) { memset(e, 0, sizeof(*e)); }

static void euro_filter(Euro *e, double *x, int n, double dt,
                        double f_min, double beta, double d_cut) {
    if (!e->init || dt <= 0.0 || dt > 1.0) {
        for (int i = 0; i < n; i++) { e->x[i] = x[i]; e->dx[i] = 0.0; }
        e->init = 1;
        return;
    }
    double a_d = dt / (dt + 1.0 / (2.0 * M_PI * d_cut));
    double speed2 = 0.0;
    for (int i = 0; i < n; i++) {
        double d = (x[i] - e->x[i]) / dt;
        e->dx[i] += a_d * (d - e->dx[i]);
        speed2 += e->dx[i] * e->dx[i];
    }
    double cutoff = f_min + beta * sqrt(speed2);
    double a = dt / (dt + 1.0 / (2.0 * M_PI * cutoff));
    for (int i = 0; i < n; i++) {
        e->x[i] += a * (x[i] - e->x[i]);
        x[i] = e->x[i];
    }
}

static double vec3_dist(const double a[3], const double b[3]) {
    double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
    return sqrt(dx * dx + dy * dy + dz * dz);
}

static double quat_angle_deg(const double a[4], const double b[4]) {
    double dot = a[0]*b[0] + a[1]*b[1] + a[2]*b[2] + a[3]*b[3];
    if (dot < 0.0) dot = -dot;
    if (dot > 1.0) dot = 1.0;
    return 2.0 * acos(dot) * 180.0 / M_PI;
}

static Euro g_euro_pos, g_euro_rot;
static double g_prev_c[3], g_prev_q[4];
static int g_prev_valid, g_reject_run;
static int64_t g_prev_ns;

/* metrics: what the game actually sees (filtered output) */
static double g_m_prev_c[3], g_m_prev_q[4];
static double g_m_raw_prev_c[3], g_m_raw_prev_q[4];
static int g_m_raw_valid, g_m_raw_n;
static double g_m_raw_pos2, g_m_raw_rot2;
static double g_m_lag, g_m_lag_ang;
static int64_t g_metric_world_ns;
static int64_t g_log_gate_ns;
static int log_gate(void) {
    int64_t t = now_ns();
    if (t - g_log_gate_ns < 1000000000LL) return 0;
    g_log_gate_ns = t;
    return 1;
}
static int g_m_valid, g_m_n;
static double g_m_jit_pos2, g_m_jit_rot2, g_m_rms, g_m_margin;
static int64_t g_m_last_ns;
static int g_m_dets;

static void update_marker(const ArSession *session, const ArFrame *frame) {
    marker_init();
    poll_freeze();
    if (g_freeze) return;
    shim_log("update_marker enter");

    float fx = 464.56f, fy = 464.56f, cx = 320.0f, cy = 240.0f;
    ArCamera *camera = NULL;
    real_ArFrame_acquireCamera(session, frame, &camera);
    if (!camera) return;
    ArCameraIntrinsics *intr = NULL;
    real_ArCameraIntrinsics_create(session, &intr);
    if (intr) {
        real_ArCamera_getImageIntrinsics(session, camera, intr);
        real_ArCameraIntrinsics_getFocalLength(session, intr, &fx, &fy);
        real_ArCameraIntrinsics_getPrincipalPoint(session, intr, &cx, &cy);
        real_ArCameraIntrinsics_destroy(intr);
    }
    real_ArCamera_release(camera);

    ArImage *image = NULL;
    if (real_ArFrame_acquireCameraImage((ArSession *)session, (ArFrame *)frame, &image) != AR_SUCCESS || !image) return;

    int32_t w = 0, h = 0, row_stride = 0, pix_stride = 1;
    real_ArImage_getWidth(session, image, &w);
    real_ArImage_getHeight(session, image, &h);
    real_ArImage_getPlaneRowStride(session, image, 0, &row_stride);
    real_ArImage_getPlanePixelStride(session, image, 0, &pix_stride);
    const uint8_t *data = NULL;
    int32_t data_len = 0;
    real_ArImage_getPlaneData(session, image, 0, &data, &data_len);
    if (w <= 0 || h <= 0 || !data) {
        real_ArImage_release(image);
        return;
    }

    uint8_t *buf = (uint8_t *)malloc((size_t)w * h);
    if (!buf) { real_ArImage_release(image); return; }
    if (pix_stride == 1 && row_stride == w) {
        memcpy(buf, data, (size_t)w * h);
    } else {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                buf[y * w + x] = data[y * row_stride + x * pix_stride];
            }
        }
    }
    image_u8_t im = { .width = w, .height = h, .stride = w, .buf = buf };
    zarray_t *dets = apriltag_detector_detect(g_td, &im);
    shim_log("dets=%d", zarray_size(dets));
    free(buf);

    apriltag_detection_t *best = NULL;
    apriltag_detection_t *fallback = NULL;
    double best_area = -1.0, fallback_area = -1.0;
    for (int i = 0; i < zarray_size(dets); i++) {
        apriltag_detection_t *det;
        zarray_get(dets, i, &det);
        double area = 0;
        for (int j = 0; j < 4; j++) {
            int k = (j + 1) % 4;
            area += det->p[j][0] * det->p[k][1] - det->p[k][0] * det->p[j][1];
        }
        area = fabs(area) * 0.5;
        if (area > fallback_area) { fallback_area = area; fallback = det; }
        if (det->hamming == 0 && det->decision_margin >= g_tune.min_margin &&
            area > best_area) {
            best_area = area;
            best = det;
        }
    }
    if (!best && fallback) {
        best = fallback;   /* never lose the tag because of a strict margin */
        if (log_gate()) shim_log("margin low %.0f, using tag anyway", fallback->decision_margin);
    }

    if (best) {
        /* Undistort the corners: estimate_tag_pose only knows a pinhole model. */
        apriltag_detection_t det = *best;
        if (g_tune.dist_on) {
            for (int j = 0; j < 4; j++) {
                float u = 0.0f, v = 0.0f;
                undistort_px(best->p[j][0], best->p[j][1], fx, fy, cx, cy, &u, &v);
                det.p[j][0] = u;
                det.p[j][1] = v;
            }
        }

        apriltag_detection_info_t info;
        info.det = &det;
        info.tagsize = g_tune.tag_size;
        info.fx = fx;
        info.fy = fy;
        info.cx = cx;
        info.cy = cy;
        apriltag_pose_t pose;
        double err = estimate_tag_pose(&info, &pose);
        /* err is a sum of squared errors in normalised coordinates. */
        double rms_px = (fx > 0.0f) ? sqrt(err / 4.0) * fx : 0.0;

        static int bad_run;
        int pose_ok = (pose.R && pose.t && rms_px <= g_tune.max_px);
        if (!pose_ok && pose.R && pose.t) {
            bad_run++;
            if (bad_run >= 5 || !g_seen) {
                pose_ok = 1;
                bad_run = 0;
                if (log_gate()) shim_log("rms high, accepted %.2fpx", rms_px);
            }
        } else if (pose_ok) {
            bad_run = 0;
        }

        if (pose_ok) {
            double R[3][3], t[3];
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) R[r][c] = matd_get(pose.R, r, c);
                t[r] = matd_get(pose.t, r, 0);
            }
            const double F[3] = {1.0, -1.0, -1.0};
            double Rg[3][3];
            for (int r = 0; r < 3; r++)
                for (int c = 0; c < 3; c++) Rg[r][c] = F[r] * R[r][c];
            double tg[3] = {F[0] * t[0], F[1] * t[1], F[2] * t[2]};

            /* The camera image is in sensor orientation; rotate the camera frame
             * around its optical axis to match the display orientation. */
            double theta = 0.0;
            if (g_display_rotation == 0) theta = -M_PI / 2.0;
            else if (g_display_rotation == 2) theta = M_PI / 2.0;
            else if (g_display_rotation == 1) theta = 0.0;
            else if (g_display_rotation == 3) theta = M_PI;
            double cc = cos(theta), ss = sin(theta);
            double Rz[3][3] = {{cc, -ss, 0}, {ss, cc, 0}, {0, 0, 1}};
            double Rf[3][3];
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    Rf[r][c] = Rz[r][0] * Rg[0][c] + Rz[r][1] * Rg[1][c] + Rz[r][2] * Rg[2][c];
                }
            }
            double tf[3];
            for (int r = 0; r < 3; r++) {
                tf[r] = Rz[r][0] * tg[0] + Rz[r][1] * tg[1] + Rz[r][2] * tg[2];
            }
            memcpy(Rg, Rf, sizeof(Rf));
            memcpy(tg, tf, sizeof(tf));

            /* Optional world frame change. ARCore and the game assume +Y is up,
             * but the tag frame has its up direction along -Z. world_mode=1
             * re-bases the world: y' = -z, z' = y. */
            int wmode = 1;
            {
                FILE *wf = fopen(APP_DIR "world_mode", "r");
                if (wf) { if (fscanf(wf, "%d", &wmode) != 1) wmode = 1; fclose(wf); }
                if (wmode < 0 || wmode > 1) wmode = 1;
            }
            if (wmode == 1) {
                static const double A_T[3][3] = {
                    {1, 0, 0},
                    {0, 0, 1},
                    {0, -1, 0}
                };
                double RW[3][3];
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) {
                        RW[r][c] = Rg[r][0] * A_T[0][c]
                                 + Rg[r][1] * A_T[1][c]
                                 + Rg[r][2] * A_T[2][c];
                    }
                }
                memcpy(Rg, RW, sizeof(RW));
            }

            /* Camera pose in the world = inverse of the view matrix. */
            double Rc[3][3];
            for (int r = 0; r < 3; r++)
                for (int c = 0; c < 3; c++) Rc[r][c] = Rg[c][r];
            double cpos[3];
            for (int r = 0; r < 3; r++) {
                cpos[r] = -(Rc[r][0] * tg[0] + Rc[r][1] * tg[1] + Rc[r][2] * tg[2]);
            }
            double q[4];
            mat_to_quat(Rc, q);

            int finite = isfinite(cpos[0]) && isfinite(cpos[1]) && isfinite(cpos[2]) &&
                         isfinite(q[0]) && isfinite(q[1]) && isfinite(q[2]) && isfinite(q[3]);
            if (!finite) {
                if (log_gate()) shim_log("non-finite pose rejected");
            }
            int64_t now = now_ns();
            double dt = g_prev_valid ? (double)(now - g_prev_ns) / 1e9 : 0.0;
            int reset = (!g_prev_valid || !g_seen || dt > 1.0);
            if (reset) {
                euro_reset(&g_euro_pos);
                euro_reset(&g_euro_rot);
                g_reject_run = 0;
            }

            int outlier = 0;
            if (g_prev_valid && !reset) {
                double d = vec3_dist(cpos, g_prev_c);
                double ang = quat_angle_deg(q, g_prev_q);
                if (d > g_tune.max_jump_m || ang > g_tune.max_ang_deg) {
                    g_reject_run++;
                    if (g_reject_run < 3) {
                        outlier = 1;
                        if (log_gate()) shim_log("reject jump d=%.3f ang=%.1f", d, ang);
                    } else {
                        euro_reset(&g_euro_pos);
                        euro_reset(&g_euro_rot);
                        g_reject_run = 0;
                        shim_log("jump accepted after 3 rejects d=%.3f ang=%.1f", d, ang);
                    }
                } else {
                    g_reject_run = 0;
                }
            }

            if (!outlier && finite) {
                double raw_c[3], raw_q[4];
                memcpy(raw_c, cpos, sizeof(raw_c));
                memcpy(raw_q, q, sizeof(raw_q));
                if (g_m_raw_valid) {
                    double rp = vec3_dist(raw_c, g_m_raw_prev_c);
                    double ra = quat_angle_deg(raw_q, g_m_raw_prev_q);
                    g_m_raw_pos2 += rp * rp;
                    g_m_raw_rot2 += ra * ra;
                    g_m_raw_n++;
                }
                memcpy(g_m_raw_prev_c, raw_c, sizeof(raw_c));
                memcpy(g_m_raw_prev_q, raw_q, sizeof(raw_q));
                g_m_raw_valid = 1;

                if (g_tune.filter_on && dt > 0.0) {
                    euro_filter(&g_euro_pos, cpos, 3, dt,
                                g_tune.f_min, g_tune.beta_pos, 1.0);
                    double dot = q[0]*g_euro_rot.x[0] + q[1]*g_euro_rot.x[1]
                               + q[2]*g_euro_rot.x[2] + q[3]*g_euro_rot.x[3];
                    if (g_euro_rot.init && dot < 0.0) {
                        for (int i = 0; i < 4; i++) q[i] = -q[i];
                    }
                    euro_filter(&g_euro_rot, q, 4, dt,
                                g_tune.f_min, g_tune.beta_rot, 1.0);
                    double n = sqrt(q[0]*q[0] + q[1]*q[1] + q[2]*q[2] + q[3]*q[3]);
                    if (n > 1e-6) { for (int i = 0; i < 4; i++) q[i] /= n; }
                }

                g_m_lag += vec3_dist(cpos, raw_c);
                g_m_lag_ang += quat_angle_deg(q, raw_q);

                /* Rebuild the view matrix from the (possibly filtered) pose. */
                quat_to_mat(q, Rc);
                for (int r = 0; r < 3; r++)
                    for (int c = 0; c < 3; c++) Rg[r][c] = Rc[c][r];
                for (int r = 0; r < 3; r++) {
                    tg[r] = -(Rg[r][0] * cpos[0] + Rg[r][1] * cpos[1] + Rg[r][2] * cpos[2]);
                }

                g_view[0] = (float)Rg[0][0]; g_view[1] = (float)Rg[1][0]; g_view[2] = (float)Rg[2][0]; g_view[3] = 0;
                g_view[4] = (float)Rg[0][1]; g_view[5] = (float)Rg[1][1]; g_view[6] = (float)Rg[2][1]; g_view[7] = 0;
                g_view[8] = (float)Rg[0][2]; g_view[9] = (float)Rg[1][2]; g_view[10] = (float)Rg[2][2]; g_view[11] = 0;
                g_view[12] = (float)tg[0]; g_view[13] = (float)tg[1]; g_view[14] = (float)tg[2]; g_view[15] = 1;

                g_cam[0] = (float)Rc[0][0]; g_cam[1] = (float)Rc[1][0]; g_cam[2] = (float)Rc[2][0]; g_cam[3] = 0;
                g_cam[4] = (float)Rc[0][1]; g_cam[5] = (float)Rc[1][1]; g_cam[6] = (float)Rc[2][1]; g_cam[7] = 0;
                g_cam[8] = (float)Rc[0][2]; g_cam[9] = (float)Rc[1][2]; g_cam[10] = (float)Rc[2][2]; g_cam[11] = 0;
                g_cam[12] = (float)cpos[0]; g_cam[13] = (float)cpos[1]; g_cam[14] = (float)cpos[2]; g_cam[15] = 1;

                if (g_prev_valid && dt > 0.001) {
                    double v[3];
                    for (int i = 0; i < 3; i++) v[i] = (cpos[i] - g_prev_c[i]) / dt;
                    double sp = sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
                    if (sp < 10.0) {   /* ignore absurd spikes */
                        for (int i = 0; i < 3; i++) g_pred_v[i] = 0.5 * v[i] + 0.5 * g_pred_v[i];
                    }
                    double cq[4] = {-g_prev_q[0], -g_prev_q[1], -g_prev_q[2], g_prev_q[3]};
                    double dq[4];
                    quat_mul(q, cq, dq);
                    if (dq[3] < 0.0) { for (int i = 0; i < 4; i++) dq[i] = -dq[i]; }
                    double s2 = sqrt(dq[0]*dq[0] + dq[1]*dq[1] + dq[2]*dq[2]);
                    if (s2 > 1e-9) {
                        double ang = 2.0 * atan2(s2, dq[3]);
                        double w[3] = {dq[0]/s2*ang/dt, dq[1]/s2*ang/dt, dq[2]/s2*ang/dt};
                        for (int i = 0; i < 3; i++) g_pred_w[i] = 0.4 * w[i] + 0.6 * g_pred_w[i];
                    } else {
                        for (int i = 0; i < 3; i++) g_pred_w[i] *= 0.6;
                    }
                } else {
                    for (int i = 0; i < 3; i++) { g_pred_v[i] = 0.0; g_pred_w[i] = 0.0; }
                }
                memcpy(g_pred_c, cpos, sizeof(cpos));
                memcpy(g_pred_q, q, sizeof(q));
                g_pred_ns = now;
                g_pred_valid = 1;

                g_prev_valid = 1;
                g_prev_ns = now;
                memcpy(g_prev_c, cpos, sizeof(cpos));
                memcpy(g_prev_q, q, sizeof(q));

                /* Metrics: jitter of what the game actually renders. */
                if (g_m_valid) {
                    double dp = vec3_dist(cpos, g_m_prev_c);
                    double da = quat_angle_deg(q, g_m_prev_q);
                    g_m_jit_pos2 += dp * dp;
                    g_m_jit_rot2 += da * da;
                    g_m_n++;
                }
                memcpy(g_m_prev_c, cpos, sizeof(cpos));
                memcpy(g_m_prev_q, q, sizeof(q));
                g_m_valid = 1;
                g_m_rms += rms_px;
                g_m_margin += best->decision_margin;
                g_m_dets++;
                if (g_m_last_ns == 0) g_m_last_ns = now;

                if (!g_seen) {
                    shim_log("marker id=%d dist=%.3f rms=%.2fpx margin=%.0f",
                             best->id, sqrt(cpos[0]*cpos[0]+cpos[1]*cpos[1]+cpos[2]*cpos[2]),
                             rms_px, best->decision_margin);
                    shim_log("tracking on");
                }
                g_tracking = 1;
                g_seen = 1;
                g_last_ns = now;

                if (now - g_m_last_ns > 1000000000LL && g_m_dets > 0) {
                    double jp = (g_m_n > 0) ? sqrt(g_m_jit_pos2 / g_m_n) * 1000.0 : 0.0;
                    double jr = (g_m_n > 0) ? sqrt(g_m_jit_rot2 / g_m_n) : 0.0;
                    double rp = (g_m_raw_n > 0) ? sqrt(g_m_raw_pos2 / g_m_raw_n) * 1000.0 : 0.0;
                    double rr = (g_m_raw_n > 0) ? sqrt(g_m_raw_rot2 / g_m_raw_n) : 0.0;
                    shim_log("metrics rms=%.2fpx margin=%.0f raw=%.1fmm/%.2fdeg jit=%.1fmm/%.2fdeg "
                             "lag=%.1fmm/%.2fdeg det=%d",
                             g_m_rms / g_m_dets, g_m_margin / g_m_dets, rp, rr, jp, jr,
                             (g_m_dets > 0) ? g_m_lag / g_m_dets * 1000.0 : 0.0,
                             (g_m_dets > 0) ? g_m_lag_ang / g_m_dets : 0.0, g_m_dets);
                    g_m_jit_pos2 = g_m_jit_rot2 = 0.0;
                    g_m_raw_pos2 = g_m_raw_rot2 = 0.0;
                    g_m_raw_n = 0;
                    g_m_lag = g_m_lag_ang = 0.0;
                    g_m_n = 0;
                    g_m_rms = g_m_margin = 0.0;
                    g_m_dets = 0;
                    g_m_last_ns = now;
                }

                if (now - g_metric_world_ns > 2000000000LL) {
                    g_metric_world_ns = now;
                    shim_log("world=%d camY=%.2f,%.2f,%.2f camPos=%.2f,%.2f,%.2f",
                             wmode, g_cam[4], g_cam[5], g_cam[6],
                             cpos[0], cpos[1], cpos[2]);
                }
            }
        }

        if (pose.R) matd_destroy(pose.R);
        if (pose.t) matd_destroy(pose.t);
    }

    apriltag_detections_destroy(dets);
    real_ArImage_release(image);
    shim_log("update_marker exit");
}

void ArSession_setDisplayGeometry(ArSession *session, int32_t rotation,
                                  int32_t width, int32_t height) {
    g_display_rotation = rotation;
    shim_log("display rotation=%d %dx%d", rotation, width, height);
    real_ArSession_setDisplayGeometry(session, rotation, width, height);
}

ArStatus ArSession_update(ArSession *session, ArFrame *out_frame) {
    trace("ArSession_update");
    ArStatus st = real_ArSession_update(session, out_frame);
    static int counter = 0;
    if ((counter++ & 1) == 0 && st == AR_SUCCESS) {
        update_marker(session, out_frame);
    }
    return st;
}

void ArCamera_getViewMatrix(const ArSession *session, const ArCamera *camera,
                            float *out_col_major_4x4) {
    trace("ArCamera_getViewMatrix");
    if (g_seen && g_pred_valid) {
        double c[3], q[4];
        predict_pose(c, q);
        view_from_cq(c, q, out_col_major_4x4);
        return;
    }
    if (g_seen) {
        memcpy(out_col_major_4x4, g_view, sizeof(g_view));
    } else {
        memset(out_col_major_4x4, 0, 16 * sizeof(float));
        out_col_major_4x4[0] = 1;
        out_col_major_4x4[5] = 1;
        out_col_major_4x4[10] = 1;
        out_col_major_4x4[15] = 1;
    }
}

void ArCamera_getPose(const ArSession *session, const ArCamera *camera, ArPose *out_pose) {
    shim_log("cameraGetPose seen=%d", g_seen);
    FakePose *p = (FakePose *)out_pose;
    if (p && p->magic == MAGIC_POSE) {
        if (g_seen && g_pred_valid) {
            double c[3], q[4];
            predict_pose(c, q);
            pose_from_cq(c, q, p->m);
        } else if (g_seen) {
            memcpy(p->m, g_cam, sizeof(g_cam));
        } else {
            memset(p->m, 0, sizeof(p->m));
            p->m[0] = p->m[5] = p->m[10] = p->m[15] = 1.0f;
        }
    }
}

void ArCamera_getTrackingState(const ArSession *session, const ArCamera *camera,
                               ArTrackingState *out_tracking_state) {
    if (g_seen && now_ns() - g_last_ns > 1000000000LL) {
        g_seen = 0;
        g_tracking = 0;
        shim_log("tracking lost (timeout)");
    }
    shim_log("getTrackingState seen=%d", g_seen);
    *out_tracking_state = g_seen ? AR_TRACKING_STATE_TRACKING : AR_TRACKING_STATE_PAUSED;
}

void ArCamera_getTrackingFailureReason(const ArSession *session, const ArCamera *camera,
                                       ArTrackingFailureReason *out_reason) {
    *out_reason = AR_TRACKING_FAILURE_REASON_NONE;
}

void ArTrackableList_create(const ArSession *session, ArTrackableList **out_list) {
    FakeList *l = (FakeList *)calloc(1, sizeof(FakeList));
    l->magic = MAGIC_LIST;
    l->size = 1;
    *out_list = (ArTrackableList *)l;
}

void ArTrackableList_destroy(ArTrackableList *list) {
    FakeList *l = (FakeList *)list;
    if (l && l->magic == MAGIC_LIST) free(l);
}

void ArTrackableList_getSize(const ArSession *session, const ArTrackableList *list,
                             int32_t *out_size) {
    FakeList *l = (FakeList *)list;
    if (l && l->magic == MAGIC_LIST) {
        *out_size = g_seen ? 1 : 0;
    } else {
        real_ArTrackableList_getSize(session, list, out_size);
    }
}

void ArTrackableList_acquireItem(const ArSession *session, const ArTrackableList *list,
                                 int32_t index, ArTrackable **out_trackable) {
    FakeList *l = (FakeList *)list;
    if (l && l->magic == MAGIC_LIST) {
        shim_log("acquireItem %d size=%d", index, l->size);
        if (index < 0 || index >= l->size) {
            *out_trackable = NULL;
        } else {
            *out_trackable = (ArTrackable *)MAGIC_TRACK;
        }
    } else {
        real_ArTrackableList_acquireItem(session, list, index, out_trackable);
    }
}

void ArSession_getAllTrackables(const ArSession *session, ArTrackableType filter,
                                ArTrackableList *list) {
    FakeList *l = (FakeList *)list;
    if (l && l->magic == MAGIC_LIST) {
        l->size = (filter == AR_TRACKABLE_PLANE && g_seen) ? 1 : 0;
        shim_log("getAllTrackables filter=%d size=%d", (int)filter, l->size);
    } else {
        real_ArSession_getAllTrackables(session, filter, list);
    }
}

void ArTrackable_getTrackingState(const ArSession *session, const ArTrackable *trackable,
                                  ArTrackingState *out_state) {
    if ((uintptr_t)trackable == MAGIC_TRACK) {
        *out_state = AR_TRACKING_STATE_TRACKING;
    } else {
        real_ArTrackable_getTrackingState(session, trackable, out_state);
    }
}

void ArTrackable_release(ArTrackable *trackable) {
    /* fake trackables are static */
}

void ArPose_create(const ArSession *session, const float *pose_raw, ArPose **out_pose) {
    FakePose *p = (FakePose *)calloc(1, sizeof(FakePose));
    p->magic = MAGIC_POSE;
    float *m = p->m;
    if (pose_raw) {
        /* pose_raw is 7 floats: qx,qy,qz,qw,tx,ty,tz (NOT a 4x4 matrix). */
        float qx = pose_raw[0], qy = pose_raw[1], qz = pose_raw[2], qw = pose_raw[3];
        float tx = pose_raw[4], ty = pose_raw[5], tz = pose_raw[6];
        if (!isfinite(tx) || !isfinite(ty) || !isfinite(tz) ||
            fabsf(tx) > 1e4f || fabsf(ty) > 1e4f || fabsf(tz) > 1e4f) {
            shim_log("ArPose_create BAD raw q=%.3f,%.3f,%.3f,%.3f t=%.3f,%.3f,%.3f",
                     qx, qy, qz, qw, tx, ty, tz);
        }
        float n = sqrtf(qx*qx + qy*qy + qz*qz + qw*qw);
        if (!(n > 0.0f) || !isfinite(n)) { qx = qy = qz = 0.0f; qw = 1.0f; n = 1.0f; }
        qx /= n; qy /= n; qz /= n; qw /= n;
        float x2 = qx + qx, y2 = qy + qy, z2 = qz + qz;
        float xx = qx * x2, xy = qx * y2, xz = qx * z2;
        float yy = qy * y2, yz = qy * z2, zz = qz * z2;
        float wx = qw * x2, wy = qw * y2, wz = qw * z2;
        /* column-major 4x4 */
        m[0] = 1.0f - (yy + zz); m[1] = xy + wz;         m[2] = xz - wy;         m[3] = 0.0f;
        m[4] = xy - wz;         m[5] = 1.0f - (xx + zz); m[6] = yz + wx;         m[7] = 0.0f;
        m[8] = xz + wy;         m[9] = yz - wx;         m[10] = 1.0f - (xx + yy); m[11] = 0.0f;
        m[12] = tx;             m[13] = ty;             m[14] = tz;             m[15] = 1.0f;
    } else {
        /* Documented behaviour: NULL means the identity pose. */
        m[0] = m[5] = m[10] = m[15] = 1.0f;
    }
    *out_pose = (ArPose *)p;
}

void ArPose_destroy(ArPose *pose) {
    FakePose *p = (FakePose *)pose;
    if (p && p->magic == MAGIC_POSE) free(p);
}

void ArPose_getMatrix(const ArSession *session, const ArPose *pose,
                      float *out_matrix_col_major_4x4) {
    FakePose *p = (FakePose *)pose;
    if (p && p->magic == MAGIC_POSE) {
        memcpy(out_matrix_col_major_4x4, p->m, 16 * sizeof(float));
    } else {
        real_ArPose_getMatrix(session, pose, out_matrix_col_major_4x4);
    }
}

void ArPlane_getCenterPose(const ArSession *session, const ArPlane *plane,
                           ArPose *out_pose) {
    FakePose *p = (FakePose *)out_pose;
    if (p && p->magic == MAGIC_POSE) {
        /* ARCore planes lie in local XZ with +Y as the normal.
         * The marker normal in our world frame is -Z, so rotate the plane
         * frame by 90 degrees: y_plane = (0,0,-1), x_plane = (1,0,0),
         * z_plane = (0,1,0). */
        static const float poses[5][16] = {
            /* 0: identity (y = tag +Y, z = tag +Z) */
            {1, 0, 0, 0,  0, 1, 0, 0,  0, 0, 1, 0,  0, 0, 0, 1},
            /* 1: y = +Z */
            {1, 0, 0, 0,  0, 0, 1, 0,  0, -1, 0, 0, 0, 0, 0, 1},
            /* 2: y = -Z */
            {1, 0, 0, 0,  0, 0, -1, 0, 0, 1, 0, 0,  0, 0, 0, 1},
            /* 3: y = +X */
            {0, -1, 0, 0, 1, 0, 0, 0,  0, 0, 1, 0,  0, 0, 0, 1},
            /* 4: y = -X */
            {0, 1, 0, 0,  -1, 0, 0, 0, 0, 0, 1, 0,  0, 0, 0, 1},
        };
        int mode = 2;
        FILE *mf = fopen(APP_DIR "plane_mode", "r");
        if (mf) { if (fscanf(mf, "%d", &mode) != 1) mode = 2; fclose(mf); }
        if (mode < 0 || mode > 4) mode = 2;
        static int last_mode = -1;
        if (mode != last_mode) {
            shim_log("plane mode=%d", mode);
            last_mode = mode;
        }
        memcpy(p->m, poses[mode], sizeof(poses[mode]));
    }
}

void ArPlane_getExtentX(const ArSession *session, const ArPlane *plane, float *out_extent) {
    *out_extent = 1.0f;
}

void ArPlane_getExtentZ(const ArSession *session, const ArPlane *plane, float *out_extent) {
    *out_extent = 1.0f;
}

void ArPlane_getType(const ArSession *session, const ArPlane *plane, ArPlaneType *out_type) {
    *out_type = AR_PLANE_HORIZONTAL_UPWARD_FACING;
}

void ArPlane_acquireSubsumedBy(const ArSession *session, const ArPlane *plane,
                               ArPlane **out_subsumed_by) {
    if (out_subsumed_by) *out_subsumed_by = NULL;
}

ArStatus ArSession_acquireNewAnchor(ArSession *session, const ArPose *pose,
                                    ArAnchor **out_anchor) {
    shim_log("acquireNewAnchor");
    FakeAnchor *a = (FakeAnchor *)calloc(1, sizeof(FakeAnchor));
    a->magic = MAGIC_ANCHOR;
    FakePose *p = (FakePose *)pose;
    if (p && p->magic == MAGIC_POSE) {
        memcpy(a->m, p->m, 16 * sizeof(float));
    } else {
        real_ArPose_getMatrix(session, pose, a->m);
    }
    *out_anchor = (ArAnchor *)a;
    return AR_SUCCESS;
}

void ArAnchor_getPose(const ArSession *session, const ArAnchor *anchor, ArPose *out_pose) {
    shim_log("anchorGetPose");
    FakeAnchor *a = (FakeAnchor *)anchor;
    FakePose *p = (FakePose *)out_pose;
    if (a && a->magic == MAGIC_ANCHOR && p && p->magic == MAGIC_POSE) {
        memcpy(p->m, a->m, 16 * sizeof(float));
    } else {
        real_ArAnchor_getPose(session, anchor, out_pose);
    }
}

void ArAnchor_getTrackingState(const ArSession *session, const ArAnchor *anchor,
                               ArTrackingState *out_state) {
    *out_state = AR_TRACKING_STATE_TRACKING;
}

void ArAnchor_detach(ArSession *session, ArAnchor *anchor) {
    /* no-op */
}

void ArAnchor_release(ArAnchor *anchor) {
    FakeAnchor *a = (FakeAnchor *)anchor;
    if (a && a->magic == MAGIC_ANCHOR) free(a);
}

ArStatus ArFrame_acquirePointCloud(const ArSession *session, const ArFrame *frame,
                                   ArPointCloud **out_point_cloud) {
    *out_point_cloud = (ArPointCloud *)MAGIC_CLOUD;
    return AR_SUCCESS;
}

void ArPointCloud_getNumberOfPoints(const ArSession *session, const ArPointCloud *cloud,
                                    int32_t *out_count) {
    *out_count = 0;
}

void ArPointCloud_getData(const ArSession *session, const ArPointCloud *cloud,
                          const float **out_data) {
    if (out_data) *out_data = NULL;
}

void ArPointCloud_release(ArPointCloud *cloud) {
    /* static fake */
}


void ArFrame_getAndroidSensorPose(const ArSession *session, const ArFrame *frame,
                                  ArPose *out_pose) {
    shim_log("sensorPose seen=%d", g_seen);
    FakePose *p = (FakePose *)out_pose;
    if (p && p->magic == MAGIC_POSE) {
        if (g_seen && g_pred_valid) {
            double c[3], q[4];
            predict_pose(c, q);
            pose_from_cq(c, q, p->m);
        } else if (g_seen) {
            memcpy(p->m, g_cam, sizeof(g_cam));
        } else {
            memset(p->m, 0, sizeof(p->m));
            p->m[0] = p->m[5] = p->m[10] = p->m[15] = 1.0f;
        }
    }
}

void ArAugmentedImage_getCenterPose(const ArSession *session,
                                    const ArAugmentedImage *image, ArPose *out_pose) {
    FakePose *p = (FakePose *)out_pose;
    if (p && p->magic == MAGIC_POSE) {
        memset(p->m, 0, sizeof(p->m));
        p->m[0] = p->m[5] = p->m[10] = p->m[15] = 1.0f;
    }
}

/* Internal dynamite symbol-table accessor (not in the public header). */
extern void *g_ArCoreApk_getSymbolTable;
void *ArCoreApk_getSymbolTable(void) {
    if (g_ArCoreApk_getSymbolTable) {
        return ((void *(*)(void))g_ArCoreApk_getSymbolTable)();
    }
    return NULL;
}
