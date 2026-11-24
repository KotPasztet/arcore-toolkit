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
#include <pthread.h>
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

static int64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static int g_log_fd = -1;
static pthread_mutex_t g_log_lock = PTHREAD_MUTEX_INITIALIZER;
static volatile int g_display_rotation = 0;

static void shim_log(const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, "ARCORE_SHIM", "%s", buf);

    pthread_mutex_lock(&g_log_lock);
    if (g_log_fd < 0) {
        g_log_fd = open(APP_DIR "shim.log",
                        O_WRONLY | O_CREAT | O_TRUNC, 0666);
        if (g_log_fd < 0) {
            pthread_mutex_unlock(&g_log_lock);
            return;
        }
        const char *hdr = "# shim log started\n";
        (void)!write(g_log_fd, hdr, strlen(hdr));
    }
    char line[640];
    int n = snprintf(line, sizeof(line), "%lld %d %s\n", (long long)now_ns(), (int)gettid(), buf);
    if (n > 0) (void)!write(g_log_fd, line, (size_t)n);
    pthread_mutex_unlock(&g_log_lock);
}

/* Per-call tracing is very expensive (a write syscall on the GL thread for
 * every ARCore call).  It is opt-in: create a file named "trace_on" next to
 * shim.log to enable it.  The flag is re-checked every two seconds. */
static int g_trace_fd = -1;
static int g_trace_on = -1;
static int64_t g_trace_check_ns;
static pthread_mutex_t g_trace_lock = PTHREAD_MUTEX_INITIALIZER;

static int trace_enabled(void) {
    int64_t now = now_ns();
    if (g_trace_on < 0 || now - g_trace_check_ns > 2000000000LL) {
        g_trace_check_ns = now;
        int fd = open(APP_DIR "trace_on", O_RDONLY);
        g_trace_on = fd >= 0;
        if (fd >= 0) close(fd);
    }
    return g_trace_on;
}

static void trace(const char *name) {
    if (!trace_enabled()) return;
    pthread_mutex_lock(&g_trace_lock);
    if (g_trace_fd < 0) {
        g_trace_fd = open(APP_DIR "trace.log",
                          O_WRONLY | O_CREAT | O_TRUNC, 0666);
        if (g_trace_fd < 0) {
            pthread_mutex_unlock(&g_trace_lock);
            return;
        }
    }
    char b[128];
    int n = snprintf(b, sizeof(b), "%lld %d %s\n", (long long)now_ns(), (int)gettid(), name);
    if (n > 0) (void)!write(g_trace_fd, b, (size_t)n);
    pthread_mutex_unlock(&g_trace_lock);
}

#include "marker_tracking.inc"

void ArSession_setDisplayGeometry(ArSession *session, int32_t rotation,
                                  int32_t width, int32_t height) {
    static int32_t last_rotation = -1, last_width = -1, last_height = -1;
    g_display_rotation = rotation;
    if (rotation != last_rotation || width != last_width || height != last_height) {
        last_rotation = rotation;
        last_width = width;
        last_height = height;
        shim_log("display rotation=%d %dx%d", rotation, width, height);
    }
    real_ArSession_setDisplayGeometry(session, rotation, width, height);
}

ArStatus ArSession_update(ArSession *session, ArFrame *out_frame) {
    trace("ArSession_update");
    ArStatus st = real_ArSession_update(session, out_frame);
    if (st == AR_SUCCESS) {
        /* Capture and queue the newest camera frame.  Detection happens on a
         * worker thread, so this call only copies the Y plane. */
        mt_capture(session, out_frame);
    }
    return st;
}

void ArCamera_getViewMatrix(const ArSession *session, const ArCamera *camera,
                            float *out_col_major_4x4) {
    trace("ArCamera_getViewMatrix");
    double c[3], q[4];
    int tracking = 0;
    if (mt_pose_at(now_ns(), c, q, &tracking)) {
        mt_view_from_cq(c, q, out_col_major_4x4);
    } else {
        mt_identity(out_col_major_4x4);
    }
}

void ArCamera_getPose(const ArSession *session, const ArCamera *camera, ArPose *out_pose) {
    FakePose *p = (FakePose *)out_pose;
    if (!p || p->magic != MAGIC_POSE) return;
    double c[3], q[4];
    int tracking = 0;
    if (mt_pose_at(now_ns(), c, q, &tracking)) {
        mt_pose_from_cq(c, q, p->m);
    } else {
        mt_identity(p->m);
    }
}

void ArCamera_getTrackingState(const ArSession *session, const ArCamera *camera,
                               ArTrackingState *out_tracking_state) {
    *out_tracking_state = mt_is_tracking() ? AR_TRACKING_STATE_TRACKING
                                           : AR_TRACKING_STATE_PAUSED;
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
        *out_size = mt_is_tracking() ? 1 : 0;
    } else {
        real_ArTrackableList_getSize(session, list, out_size);
    }
}

void ArTrackableList_acquireItem(const ArSession *session, const ArTrackableList *list,
                                 int32_t index, ArTrackable **out_trackable) {
    FakeList *l = (FakeList *)list;
    if (l && l->magic == MAGIC_LIST) {
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
        l->size = (filter == AR_TRACKABLE_PLANE && mt_is_tracking()) ? 1 : 0;
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
        static int mode = 2;
        static int64_t next_check_ns;
        static int last_mode = -1;
        int64_t now = now_ns();
        if (now >= next_check_ns) {
            next_check_ns = now + 1000000000LL;
            FILE *mf = fopen(APP_DIR "plane_mode", "r");
            if (mf) {
                if (fscanf(mf, "%d", &mode) != 1) mode = 2;
                fclose(mf);
            }
            if (mode < 0 || mode > 4) mode = 2;
            if (mode != last_mode) {
                shim_log("plane mode=%d", mode);
                last_mode = mode;
            }
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
    FakePose *p = (FakePose *)out_pose;
    if (!p || p->magic != MAGIC_POSE) return;
    double c[3], q[4];
    int tracking = 0;
    if (mt_pose_at(now_ns(), c, q, &tracking)) {
        mt_pose_from_cq(c, q, p->m);
    } else {
        mt_identity(p->m);
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
