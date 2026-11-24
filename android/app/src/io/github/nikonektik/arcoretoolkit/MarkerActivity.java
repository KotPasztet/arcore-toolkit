package io.github.nikonektik.arcoretoolkit;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.widget.FrameLayout;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point;
import org.opencv.core.Point3;
import org.opencv.objdetect.ArucoDetector;
import org.opencv.objdetect.DetectorParameters;
import org.opencv.objdetect.Objdetect;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Marker-AR prototype: AprilTag 36h11 + solvePnP + projected cube.
 * The camera Y plane is rendered directly as a Bitmap, so the preview,
 * detection and overlay share exactly the same coordinate system.
 */
public class MarkerActivity extends Activity {
    private static final String TAG = "MARKER";

    static {
        System.loadLibrary("opencv_java4");
    }

    private static final int DET_W = 640;
    private static final int DET_H = 480;
    // Printed tag: outer black square 150 mm, print scale assumed 0.99.
    private static final double TAG_SIZE = 0.150 * 0.99;

    private FrameLayout previewBox;
    private OverlayView overlay;

    private HandlerThread bgThread;
    private Handler bgHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader reader;
    private String cameraId = "0";
    private int sensorOrientation = 90;

    private final ExecutorService detectorPool = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final byte[] yBuf = new byte[DET_W * DET_H];
    private final int[] argb = new int[DET_W * DET_H];
    private final Bitmap frameBitmap = Bitmap.createBitmap(DET_W, DET_H, Bitmap.Config.ARGB_8888);
    private final Object frameLock = new Object();

    private final Mat K = new Mat(3, 3, CvType.CV_64F);
    private final MatOfDouble D = new MatOfDouble(0, 0, 0, 0, 0);
    private ArucoDetector aruco;

    private long frames = 0;
    private long detected = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        K.put(0, 0,
                464.56, 0, 320.0,
                0, 464.56, 240.0,
                0, 0, 1);

        DetectorParameters params = new DetectorParameters();
        params.set_markerBorderBits(1);
        params.set_cornerRefinementMethod(Objdetect.CORNER_REFINE_SUBPIX);
        aruco = new ArucoDetector(Objdetect.getPredefinedDictionary(Objdetect.DICT_APRILTAG_36h11), params);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int boxW, boxH;
        // portrait 3:4 box for the rotated camera image
        if (sw * 4 <= sh * 3) {
            boxW = sw;
            boxH = sw * 4 / 3;
        } else {
            boxH = sh;
            boxW = sh * 3 / 4;
        }
        previewBox = new FrameLayout(this);
        overlay = new OverlayView(this, frameBitmap, frameLock);
        previewBox.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(boxW, boxH, Gravity.CENTER);
        root.addView(previewBox, boxLp);
        setContentView(root);

        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);


        bgThread = new HandlerThread("marker-cam");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
        } else {
            openCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        }
    }

    private void openCamera() {
        if (cameraDevice != null) return;
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }
            reader = ImageReader.newInstance(DET_W, DET_H, android.graphics.ImageFormat.YUV_420_888, 4);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override public void onImageAvailable(ImageReader r) { onFrame(r); }
            }, bgHandler);

            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createSession();
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }
                @Override public void onError(CameraDevice camera, int error) {
                    Log.e(TAG, "camera error " + error);
                    camera.close();
                    cameraDevice = null;
                }
            }, bgHandler);
        } catch (Throwable t) {
            Log.e(TAG, "openCamera failed", t);
        }
    }

    private void createSession() {
        try {
            final Surface target = reader.getSurface();
            cameraDevice.createCaptureSession(Arrays.asList(target), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(target);
                        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(30, 30));
                        b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
                        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
                        session.setRepeatingRequest(b.build(), null, bgHandler);
                        Log.i(TAG, "session configured " + DET_W + "x" + DET_H);
                    } catch (Throwable t) {
                        Log.e(TAG, "request failed", t);
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    Log.e(TAG, "configure failed");
                }
            }, bgHandler);
        } catch (Throwable t) {
            Log.e(TAG, "createSession failed", t);
        }
    }

    private void onFrame(ImageReader r) {
        Image image = null;
        try {
            image = r.acquireLatestImage();
            if (image == null) return;
            if (!busy.compareAndSet(false, true)) return;
            Image.Plane y = image.getPlanes()[0];
            ByteBuffer buf = y.getBuffer();
            int rowStride = y.getRowStride();
            int pixStride = y.getPixelStride();
            if (pixStride == 1 && rowStride == DET_W) {
                buf.get(yBuf, 0, DET_W * DET_H);
            } else {
                for (int row = 0; row < DET_H; row++) {
                    int base = row * rowStride;
                    int dst = row * DET_W;
                    for (int col = 0; col < DET_W; col++) {
                        yBuf[dst + col] = buf.get(base + col * pixStride);
                    }
                }
            }
            detectorPool.execute(new Runnable() {
                @Override public void run() {
                    try {
                        detect();
                    } catch (Throwable t) {
                        Log.e(TAG, "detect failed", t);
                    } finally {
                        busy.set(false);
                    }
                }
            });
        } catch (Throwable t) {
            busy.set(false);
            Log.e(TAG, "onFrame failed", t);
        } finally {
            if (image != null) image.close();
        }
    }

    private void detect() {
        frames++;
        Mat gray = new Mat(DET_H, DET_W, CvType.CV_8UC1);
        gray.put(0, 0, yBuf);

        // preview bitmap (grayscale)
        for (int i = 0; i < argb.length; i++) {
            int v = yBuf[i] & 0xFF;
            argb[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }

        List<Mat> corners = new ArrayList<>();
        Mat ids = new Mat();
        aruco.detectMarkers(gray, corners, ids);
        gray.release();

        if (ids.total() == 0 || corners.isEmpty()) {
            synchronized (frameLock) {
                frameBitmap.setPixels(argb, 0, DET_W, 0, 0, DET_W, DET_H);
            }
            overlay.setNoMarker(frames, detected);
            overlay.postInvalidate();
            ids.release();
            return;
        }

        int best = -1;
        double bestArea = -1;
        for (int i = 0; i < corners.size(); i++) {
            float[] v = new float[8];
            corners.get(i).get(0, 0, v);
            double area = Math.abs((v[2] - v[0]) * (v[5] - v[1]) - (v[4] - v[0]) * (v[3] - v[1]));
            if (area > bestArea) { bestArea = area; best = i; }
        }

        float[] v = new float[8];
        corners.get(best).get(0, 0, v);
        Point[] mc = new Point[4];
        for (int j = 0; j < 4; j++) mc[j] = new Point(v[2 * j], v[2 * j + 1]);

        MatOfPoint2f img = new MatOfPoint2f(mc[0], mc[1], mc[2], mc[3]);
        MatOfPoint3f obj = new MatOfPoint3f(
                new Point3(0, 0, 0), new Point3(TAG_SIZE, 0, 0),
                new Point3(TAG_SIZE, TAG_SIZE, 0), new Point3(0, TAG_SIZE, 0));
        Mat rvec = new Mat();
        Mat tvec = new Mat();
        boolean ok = Calib3d.solvePnP(obj, img, K, D, rvec, tvec);
        if (!ok) {
            synchronized (frameLock) {
                frameBitmap.setPixels(argb, 0, DET_W, 0, 0, DET_W, DET_H);
            }
            overlay.setNoMarker(frames, detected);
            overlay.postInvalidate();
            ids.release();
            return;
        }

        MatOfPoint2f reproj = new MatOfPoint2f();
        Calib3d.projectPoints(obj, rvec, tvec, K, D, reproj);
        Point[] rp = reproj.toArray();
        double err = 0;
        for (int j = 0; j < 4; j++) err += Math.hypot(rp[j].x - mc[j].x, rp[j].y - mc[j].y);
        err /= 4;

        double s = TAG_SIZE * 0.6;
        double x0 = (TAG_SIZE - s) / 2, x1 = x0 + s;
        MatOfPoint3f cubeObj = new MatOfPoint3f(
                new Point3(x0, x0, 0), new Point3(x1, x0, 0), new Point3(x1, x1, 0), new Point3(x0, x1, 0),
                new Point3(x0, x0, -s), new Point3(x1, x0, -s), new Point3(x1, x1, -s), new Point3(x0, x1, -s));
        MatOfPoint2f cubeImg = new MatOfPoint2f();
        Calib3d.projectPoints(cubeObj, rvec, tvec, K, D, cubeImg);
        Point[] cp = cubeImg.toArray();

        int[] idv = new int[1];
        ids.get(best, 0, idv);
        int id = idv[0];
        double dist = Math.sqrt(tvec.get(0, 0)[0] * tvec.get(0, 0)[0]
                + tvec.get(1, 0)[0] * tvec.get(1, 0)[0]
                + tvec.get(2, 0)[0] * tvec.get(2, 0)[0]);
        detected++;
        Log.i(TAG, "id=" + id + " dist=" + String.format("%.3f", dist)
                + " t=(" + String.format("%.3f", tvec.get(0,0)[0]) + ","
                + String.format("%.3f", tvec.get(1,0)[0]) + ","
                + String.format("%.3f", tvec.get(2,0)[0]) + ") err=" + String.format("%.2f", err));

        synchronized (frameLock) {
            frameBitmap.setPixels(argb, 0, DET_W, 0, 0, DET_W, DET_H);
        }
        overlay.setPose(id, dist, err, mc, cp, frames, detected);
        overlay.postInvalidate();

        ids.release();
        rvec.release();
        tvec.release();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (captureSession != null) { captureSession.close(); captureSession = null; }
        if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
        if (reader != null) { reader.close(); reader = null; }
    }

    @Override
    protected void onDestroy() {
        detectorPool.shutdownNow();
        if (bgThread != null) bgThread.quitSafely();
        super.onDestroy();
    }

    static class OverlayView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Bitmap frame;
        private final Object lock;
        private final RectF dst = new RectF();
        private final Matrix frameMatrix = new Matrix();
        private volatile Point[] marker;
        private volatile Point[] cube;
        private volatile int id = -1;
        private volatile double dist, err;
        private volatile long frames, detections;
        private volatile boolean has = false;

        OverlayView(Context c, Bitmap frame, Object lock) {
            super(c);
            this.frame = frame;
            this.lock = lock;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4f);
            paint.setColor(Color.rgb(0, 255, 120));
            text.setColor(Color.WHITE);
            text.setTextSize(34f);
            text.setShadowLayer(4f, 2f, 2f, Color.BLACK);
        }

        void setNoMarker(long f, long d) {
            has = false;
            frames = f;
            detections = d;
        }

        void setPose(int id, double dist, double err, Point[] marker, Point[] cube, long f, long d) {
            this.id = id;
            this.dist = dist;
            this.err = err;
            this.marker = marker;
            this.cube = cube;
            this.frames = f;
            this.detections = d;
            this.has = true;
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float vw = getWidth(), vh = getHeight();
            frameMatrix.reset();
            frameMatrix.setRotate(90);
            frameMatrix.postTranslate(DET_H, 0);
            frameMatrix.postScale(vw / DET_H, vh / DET_W);
            synchronized (lock) {
                c.drawBitmap(frame, frameMatrix, null);
            }
            // image (640x480) -> rotated (480x640) -> view
            float sx = vw / DET_H;
            float sy = vh / DET_W;
            if (has && marker != null) {
                android.graphics.Path path = new android.graphics.Path();
                for (int i = 0; i < 4; i++) {
                    float x = (DET_H - (float) marker[i].y) * sx;
                    float y = (float) marker[i].x * sy;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close();
                Paint red = new Paint(paint);
                red.setColor(Color.RED);
                c.drawPath(path, red);
                for (int i = 0; i < 4; i++) {
                    Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
                    dot.setColor(Color.RED);
                    c.drawCircle((DET_H - (float) marker[i].y) * sx, (float) marker[i].x * sy, 10f, dot);
                }
                if (cube != null) {
                    float[] cx = new float[8], cy = new float[8];
                    for (int i = 0; i < 8; i++) {
                        cx[i] = (DET_H - (float) cube[i].y) * sx;
                        cy[i] = (float) cube[i].x * sy;
                    }
                    android.graphics.Path fp = new android.graphics.Path();
                    Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
                    face.setStyle(Paint.Style.FILL);
                    face.setColor(0xBB00FF78);
                    fp.moveTo(cx[4], cy[4]); fp.lineTo(cx[5], cy[5]);
                    fp.lineTo(cx[6], cy[6]); fp.lineTo(cx[7], cy[7]); fp.close();
                    c.drawPath(fp, face);
                    face.setColor(0x7700B050);
                    int[][] sides = {{0,1,5,4},{1,2,6,5},{2,3,7,6},{3,0,4,7}};
                    for (int[] f : sides) {
                        fp.reset();
                        fp.moveTo(cx[f[0]], cy[f[0]]); fp.lineTo(cx[f[1]], cy[f[1]]);
                        fp.lineTo(cx[f[2]], cy[f[2]]); fp.lineTo(cx[f[3]], cy[f[3]]); fp.close();
                        c.drawPath(fp, face);
                    }
                    int[][] edges = {{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},
                            {0,4},{1,5},{2,6},{3,7}};
                    for (int[] e : edges) {
                        c.drawLine(cx[e[0]], cy[e[0]], cx[e[1]], cy[e[1]], paint);
                    }
                }
                c.drawText("ID " + id + "  dist " + String.format("%.2f", dist) + " m"
                        + "  err " + String.format("%.2f", err) + " px", 24, getHeight() - 70, text);
            } else {
                c.drawText("No marker", 24, getHeight() - 70, text);
            }
            c.drawText("frames " + frames + "  detected " + detections, 24, getHeight() - 24, text);
        }
    }
}
