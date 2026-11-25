package io.github.nikonektik.arcoretoolkit;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Minimal camera+IMU recorder for offline Kalibr calibration.
 *
 * Saves under files/calib/<session>/:
 *   frames/000000_<timestamp_ns>.jpg
 *   frames.csv   (index,timestamp_ns,file)
 *   imu.csv      (timestamp_ns,type,x,y,z)   type=gyro|accel
 *   meta.txt     (camera characteristics + session info)
 *
 * Export (from a debug build):
 *   adb exec-out run-as io.github.nikonektik.arcoretoolkit tar -c files/calib > calib.tar
 */
public class CalibrationActivity extends Activity
        implements TextureView.SurfaceTextureListener, SensorEventListener {

    private static final String TAG = "CALIB";

    private TextureView preview;
    private TextView instructions;
    private TextView status;
    private MaterialButton startStop;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Surface previewSurface;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private Size frameSize = new Size(1280, 960);
    private Range<Integer> fpsRange;
    private Integer afMode;
    private boolean oisOff;
    private boolean vsOff;
    private String cameraId = "0";
    private int sensorOrientation = 90;
    private boolean cameraFound;
    private volatile boolean opening;

    private SensorManager sensorManager;
    private Sensor gyro;
    private Sensor accel;

    private boolean recording = false;
    private long recordStartNs = 0;
    private int frameIndex = 0;
    private int imuSamples = 0;
    private long lastSavedNs = 0;

    private static final long FRAME_INTERVAL_NS = 50_000_000L; // ~20 fps on disk

    private File sessionDir;
    private File framesDir;
    private BufferedWriter framesWriter;
    private BufferedWriter imuWriter;
    private final StringBuilder imuBuffer = new StringBuilder();

    private final Handler mainHandler = new Handler();

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            if (!recording) return;
            long elapsed = (SystemClock.elapsedRealtimeNanos() - recordStartNs) / 1_000_000_000L;
            status.setText(getString(R.string.calib_status_recording, elapsed, frameIndex,
                    imuSamples, sessionDir != null ? sessionDir.getAbsolutePath() : "-"));
            mainHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getActionBar() != null) {
            getActionBar().hide();
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101010);
        root.setPadding(0, 0, 0, 120);
        final int extraPad = Math.round(12 * getResources().getDisplayMetrics().density);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop() + extraPad,
                        insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom() + extraPad);
                return insets;
            }
        });

        instructions = new TextView(this);
        instructions.setText(getString(R.string.calib_instructions));
        instructions.setTextColor(0xFFFFFFFF);
        instructions.setTextSize(13f);
        instructions.setPadding(32, 24, 32, 8);
        root.addView(instructions, new LinearLayout.LayoutParams(-1, -2));

        int screenW = getResources().getDisplayMetrics().widthPixels;
        FrameLayout previewHolder = new FrameLayout(this);
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(this);
        previewHolder.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        root.addView(previewHolder, new LinearLayout.LayoutParams(-1, screenW * 3 / 4));

        status = new TextView(this);
        status.setText(getString(R.string.calib_status_ready));
        status.setTextColor(0xFFFFFF00);
        status.setTextSize(14f);
        status.setPadding(32, 16, 32, 16);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        MaterialButton details = new MaterialButton(this);
        details.setText(getString(R.string.calib_details));
        details.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDetails();
            }
        });
        root.addView(details, new LinearLayout.LayoutParams(-1, -2));

        startStop = new MaterialButton(this);
        startStop.setText(getString(R.string.calib_start));
        startStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (recording) {
                    stopRecording();
                } else {
                    startRecording();
                }
            }
        });
        root.addView(startStop, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
        }

        backgroundThread = new HandlerThread("calib-camera");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        selectCamera();
    }

    private void showDetails() {
        ScrollView scroll = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(getString(R.string.calib_details_text));
        tv.setTextSize(14f);
        tv.setPadding(48, 24, 48, 24);
        scroll.addView(tv);
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.calib_details_title))
                .setView(scroll)
                .setPositiveButton(getString(R.string.calib_details_ok), null)
                .show();
    }

    private static Integer chooseAfMode(CameraCharacteristics c) {
        int[] modes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (modes == null) return null;
        int[] preferred = {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                CaptureRequest.CONTROL_AF_MODE_AUTO,
                CaptureRequest.CONTROL_AF_MODE_OFF
        };
        for (int p : preferred) {
            for (int m : modes) if (m == p) return p;
        }
        return null;
    }

    private static boolean supports(CameraCharacteristics c,
                                    CameraCharacteristics.Key<int[]> key, int value) {
        int[] values = c.get(key);
        if (values == null) return false;
        for (int v : values) if (v == value) return true;
        return false;
    }

    private void selectCamera() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    cameraFound = true;
                    Integer so = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    if (so != null) sensorOrientation = so;
                    StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map != null) {
                        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                        Size preferred = null;
                        Size largest = null;
                        Size fallback = null;
                        if (sizes != null) {
                            for (Size s : sizes) {
                                if (fallback == null || s.getWidth() * s.getHeight()
                                        < fallback.getWidth() * fallback.getHeight()) {
                                    fallback = s;
                                }
                                if (s.getWidth() > 1920 || s.getHeight() > 1440) continue;
                                if (s.getWidth() * 3 != s.getHeight() * 4) continue; // 4:3
                                if (s.getWidth() == 1280 && s.getHeight() == 960) preferred = s;
                                if (largest == null || s.getWidth() * s.getHeight()
                                        > largest.getWidth() * largest.getHeight()) {
                                    largest = s;
                                }
                            }
                        }
                        if (preferred != null) frameSize = preferred;
                        else if (largest != null) frameSize = largest;
                        else if (fallback != null) frameSize = fallback;
                    }
                    afMode = chooseAfMode(c);
                    oisOff = supports(c,
                            CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION,
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
                    vsOff = supports(c,
                            CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
                    Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                    if (ranges != null) {
                        for (Range<Integer> r : ranges) {
                            if (r.getLower() == 30 && r.getUpper() == 30) { fpsRange = r; break; }
                        }
                        if (fpsRange == null) {
                            for (Range<Integer> r : ranges) {
                                if (r.getUpper() <= 30 && (fpsRange == null || r.getUpper() > fpsRange.getUpper())) {
                                    fpsRange = r;
                                }
                            }
                        }
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            ToolkitLog.e(TAG, "selectCamera failed", t);
        }
        ToolkitLog.i(TAG, "camera=" + cameraId + " frame=" + frameSize + " orientation=" + sensorOrientation);
        if (!cameraFound) {
            Toast.makeText(this, getString(R.string.calib_toast_no_back_camera),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (preview != null && preview.isAvailable()
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        openCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    private void openCamera() {
        if (cameraDevice != null || opening) return;
        if (!cameraFound) {
            Toast.makeText(this, getString(R.string.calib_toast_no_back_camera),
                    Toast.LENGTH_LONG).show();
            return;
        }
        opening = true;
        try {
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            imageReader = ImageReader.newInstance(frameSize.getWidth(), frameSize.getHeight(),
                    ImageFormat.YUV_420_888, 4);
            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    saveFrame(reader);
                }
            }, backgroundHandler);

            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                opening = false;
                return;
            }
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    opening = false;
                    cameraDevice = camera;
                    createSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    opening = false;
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    opening = false;
                    ToolkitLog.e(TAG, "camera error " + error);
                    camera.close();
                    cameraDevice = null;
                }
            }, backgroundHandler);
        } catch (Throwable t) {
            opening = false;
            ToolkitLog.e(TAG, "openCamera failed", t);
            Toast.makeText(this, getString(R.string.calib_toast_camera_unavailable, t),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void createSession() {
        try {
            SurfaceTexture st = preview.getSurfaceTexture();
            if (st == null) return;
            st.setDefaultBufferSize(frameSize.getWidth(), frameSize.getHeight());
            previewSurface = new Surface(st);
            List<Surface> surfaces = new ArrayList<>(Arrays.asList(previewSurface, imageReader.getSurface()));
            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(previewSurface);
                        b.addTarget(imageReader.getSurface());
                        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        if (afMode != null) {
                            b.set(CaptureRequest.CONTROL_AF_MODE, afMode);
                        }
                        if (fpsRange != null) {
                            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
                        }
                        if (oisOff) {
                            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
                        }
                        if (vsOff) {
                            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
                        }
                        session.setRepeatingRequest(b.build(), null, backgroundHandler);
                        ToolkitLog.i(TAG, "session configured " + frameSize + " fps=30");
                    } catch (Throwable t) {
                        ToolkitLog.e(TAG, "preview request failed", t);
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    ToolkitLog.e(TAG, "session configure failed");
                    try { cameraDevice.close(); } catch (Throwable ignored) {}
                    cameraDevice = null;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(CalibrationActivity.this,
                                    getString(R.string.calib_toast_session_failed),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }, backgroundHandler);
        } catch (Throwable t) {
            ToolkitLog.e(TAG, "createSession failed", t);
        }
    }

    private void saveFrame(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            long ts = image.getTimestamp();
            if (ts <= 0) ts = SystemClock.elapsedRealtimeNanos();
            if (!recording || framesDir == null || framesWriter == null) return;
            if (lastSavedNs != 0 && ts - lastSavedNs < FRAME_INTERVAL_NS) return;

            int w = image.getWidth();
            int h = image.getHeight();
            Image.Plane yPlane = image.getPlanes()[0];
            ByteBuffer yBuf = yPlane.getBuffer();
            int rowStride = yPlane.getRowStride();
            int pixStride = yPlane.getPixelStride();

            // Grayscale JPEG: Y plane + neutral chroma (NV21).
            byte[] nv21 = new byte[w * h * 3 / 2];
            Arrays.fill(nv21, w * h, nv21.length, (byte) 128);
            if (pixStride == 1 && rowStride == w) {
                yBuf.get(nv21, 0, w * h);
            } else {
                for (int row = 0; row < h; row++) {
                    int base = row * rowStride;
                    int dst = row * w;
                    for (int col = 0; col < w; col++) {
                        nv21[dst + col] = yBuf.get(base + col * pixStride);
                    }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(w * h / 4);
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
            yuv.compressToJpeg(new Rect(0, 0, w, h), 92, out);

            String name = String.format(Locale.US, "%06d_%d.jpg", frameIndex, ts);
            FileOutputStream fos = new FileOutputStream(new File(framesDir, name));
            out.writeTo(fos);
            fos.close();
            framesWriter.write(String.format(Locale.US, "%d,%d,%s\n", frameIndex, ts, name));
            frameIndex++;
            lastSavedNs = ts;
            if (frameIndex % 10 == 0) framesWriter.flush();
        } catch (Throwable t) {
            ToolkitLog.e(TAG, "saveFrame failed", t);
        } finally {
            if (image != null) image.close();
        }
    }

    private void startRecording() {
        if (recording) return;
        if (preview.getSurfaceTexture() == null || cameraDevice == null) {
            Toast.makeText(this, getString(R.string.calib_toast_camera_not_ready),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            sessionDir = new File(getFilesDir(), "calib/" + stamp);
            framesDir = new File(sessionDir, "frames");
            if (!framesDir.mkdirs()) {
                Toast.makeText(this, getString(R.string.calib_toast_folder_failed),
                        Toast.LENGTH_LONG).show();
                return;
            }
            framesWriter = new BufferedWriter(new FileWriter(new File(sessionDir, "frames.csv")));
            imuWriter = new BufferedWriter(new FileWriter(new File(sessionDir, "imu.csv")));
            frameIndex = 0;
            imuSamples = 0;
            lastSavedNs = 0;
            imuBuffer.setLength(0);
            writeMeta(sessionDir);
            recording = true;
            recordStartNs = SystemClock.elapsedRealtimeNanos();
            startStop.setText(getString(R.string.calib_stop));
            if (gyro != null) sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST);
            if (accel != null) sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST);
            mainHandler.post(statusTick);
            Toast.makeText(this, getString(R.string.calib_toast_recording_started),
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            ToolkitLog.e(TAG, "startRecording failed", t);
            Toast.makeText(this, getString(R.string.calib_toast_start_error, t),
                    Toast.LENGTH_LONG).show();
            stopRecording();
        }
    }

    private void stopRecording() {
        recording = false;
        startStop.setText(getString(R.string.calib_start));
        mainHandler.removeCallbacks(statusTick);
        sensorManager.unregisterListener(this);
        try {
            if (imuBuffer.length() > 0 && imuWriter != null) imuWriter.write(imuBuffer.toString());
            if (imuWriter != null) imuWriter.close();
            if (framesWriter != null) framesWriter.close();
        } catch (Throwable ignored) {}
        imuWriter = null;
        framesWriter = null;
        status.setText(getString(R.string.calib_status_done, frameIndex, imuSamples,
                sessionDir != null ? sessionDir.getAbsolutePath() : "-"));
        Toast.makeText(this, getString(R.string.calib_toast_recording_stopped),
                Toast.LENGTH_SHORT).show();
    }

    private void writeMeta(File dir) {
        try {
            BufferedWriter w = new BufferedWriter(new FileWriter(new File(dir, "meta.txt")));
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(cameraId);
            w.write("camera_id=" + cameraId + "\n");
            w.write("frame=" + frameSize.getWidth() + "x" + frameSize.getHeight() + "\n");
            w.write("format=yuv420-gray-jpeg\n");
            w.write("fps_target=30\n");
            w.write("fps_range=" + (fpsRange != null ? fpsRange.toString() : "default") + "\n");
            w.write("sensor_orientation=" + sensorOrientation + "\n");
            w.write("active_array=" + c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) + "\n");
            w.write("pixel_array=" + c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) + "\n");
            w.write("physical_size=" + c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) + "\n");
            w.write("intrinsic_calibration=" + Arrays.toString(c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)) + "\n");
            w.write("distortion=" + Arrays.toString(c.get(CameraCharacteristics.LENS_DISTORTION)) + "\n");
            w.write("focal_lengths=" + Arrays.toString(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)) + "\n");
            w.write("timestamp_source=" + c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) + "\n");
            w.write("readout_timestamp=" + c.get(CameraCharacteristics.SENSOR_READOUT_TIMESTAMP) + "\n");
            w.write("iso_range=" + c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) + "\n");
            w.write("exposure_range=" + c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) + "\n");
            w.close();
        } catch (Throwable t) {
            ToolkitLog.e(TAG, "writeMeta failed", t);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!recording) return;
        String type = event.sensor.getType() == Sensor.TYPE_GYROSCOPE ? "gyro" : "accel";
        imuSamples++;
        imuBuffer.append(String.format(Locale.US, "%d,%s,%.9f,%.9f,%.9f\n",
                event.timestamp, type, event.values[0], event.values[1], event.values[2]));
        if (imuBuffer.length() > 64 * 1024 && imuWriter != null) {
            try {
                imuWriter.write(imuBuffer.toString());
                imuBuffer.setLength(0);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    protected void onPause() {
        super.onPause();
        opening = false;
        if (recording) stopRecording();
        try {
            if (captureSession != null) captureSession.close();
        } catch (Throwable ignored) {}
        captureSession = null;
        try {
            if (cameraDevice != null) cameraDevice.close();
        } catch (Throwable ignored) {}
        cameraDevice = null;
        try {
            if (imageReader != null) imageReader.close();
        } catch (Throwable ignored) {}
        imageReader = null;
        try {
            if (previewSurface != null) previewSurface.release();
        } catch (Throwable ignored) {}
        previewSurface = null;
    }

    @Override
    protected void onDestroy() {
        if (recording) stopRecording();
        if (backgroundThread != null) backgroundThread.quitSafely();
        super.onDestroy();
    }
}
