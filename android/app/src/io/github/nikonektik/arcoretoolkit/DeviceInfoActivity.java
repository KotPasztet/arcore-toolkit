package io.github.nikonektik.arcoretoolkit;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.util.Size;
import android.util.SizeF;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * Reads the camera and IMU characteristics of this device and derives the
 * intrinsics ARCore expects for its 640x480 stream.
 *
 * This is the "suggest a profile" screen: it turns the hardware numbers into
 * the fx/fy that a device profile should contain, plus a ready-to-run
 * arcore-profile command.
 */
public class DeviceInfoActivity extends Activity {
    private TextView output;
    private String summary = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);

        TextView title = new TextView(this);
        title.setText("Device info / intrinsics");
        title.setTextSize(20f);
        root.addView(title);

        Button copy = new Button(this);
        copy.setText("Copy as text");
        copy.setOnClickListener(v -> copyToClipboard());
        root.addView(copy);

        ScrollView scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTextSize(13f);
        output.setGravity(Gravity.START);
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);

        StringBuilder sb = new StringBuilder();
        sb.append("model: ").append(android.os.Build.MANUFACTURER).append(' ')
          .append(android.os.Build.MODEL).append('\n');
        sb.append("device: ").append(android.os.Build.DEVICE).append('\n');
        sb.append("android: ").append(android.os.Build.VERSION.RELEASE)
          .append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n\n");
        sb.append(dumpCamera());
        sb.append('\n').append(dumpImu());
        summary = sb.toString();
        output.setText(summary);
    }

    private String dumpCamera() {
        StringBuilder sb = new StringBuilder("== camera ==\n");
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String chosen = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    chosen = id;
                    break;
                }
            }
            if (chosen == null) {
                return sb.append("no back camera found\n").toString();
            }
            CameraCharacteristics c = manager.getCameraCharacteristics(chosen);
            sb.append("camera id: ").append(chosen).append('\n');

            SizeF physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            Rect active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Size pixel = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Integer tsSource = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
            float[] calib = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
            float[] distortion = c.get(CameraCharacteristics.LENS_DISTORTION);

            sb.append("active array: ").append(active).append('\n');
            sb.append("pixel array: ").append(pixel).append('\n');
            sb.append("sensor orientation: ").append(orientation).append('\n');
            sb.append("timestamp source: ")
              .append(tsSource == null ? "?" : (tsSource == 1 ? "REALTIME" : "MONOTONIC"))
              .append('\n');
            if (physical != null) {
                sb.append(String.format(Locale.US, "physical size: %.3f x %.3f mm\n",
                        physical.getWidth(), physical.getHeight()));
            }
            if (focals != null && focals.length > 0) {
                sb.append(String.format(Locale.US, "focal length: %.3f mm\n", focals[0]));
            }
            if (calib != null && calib.length >= 4) {
                sb.append("factory intrinsic calibration: ");
                for (int i = 0; i < calib.length; i++) sb.append(i == 0 ? "" : ", ").append(calib[i]);
                sb.append('\n');
            }
            if (distortion != null && distortion.length > 0) {
                sb.append("factory distortion: ");
                for (int i = 0; i < distortion.length; i++) {
                    sb.append(i == 0 ? "" : ", ").append(distortion[i]);
                }
                sb.append('\n');
            }

            if (physical != null && focals != null && focals.length > 0 && active != null) {
                // The ARCore CPU image is 640x480.  If the stream uses the full
                // sensor width, fx = f / sensor_width * 640.
                double f = focals[0];
                double w = physical.getWidth();
                double h = physical.getHeight();
                double fx = f / w * 640.0;
                double fy = f / h * 480.0;
                double hfov = Math.toDegrees(2.0 * Math.atan(w / (2.0 * f)));
                double vfov = Math.toDegrees(2.0 * Math.atan(h / (2.0 * f)));
                sb.append(String.format(Locale.US,
                        "\n=> for 640x480: fx=%.1f fy=%.1f  (HFOV %.1f deg, VFOV %.1f deg)\n",
                        fx, fy, hfov, vfov));
                sb.append(String.format(Locale.US,
                        "   arcore-profile patch base.pb -o out.pb --fx %.1f --fy %.1f --cx 320 --cy 240\n",
                        fx, fy));
                sb.append("   (verify with a printed AprilGrid; distortion is not known from the HAL)\n");
            }
        } catch (Throwable t) {
            sb.append("camera error: ").append(t).append('\n');
        }
        return sb.toString();
    }

    private String dumpImu() {
        StringBuilder sb = new StringBuilder("== sensors ==\n");
        try {
            SensorManager sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            List<Sensor> sensors = sm.getSensorList(Sensor.TYPE_ALL);
            for (Sensor s : sensors) {
                int type = s.getType();
                if (type == Sensor.TYPE_ACCELEROMETER
                        || type == Sensor.TYPE_GYROSCOPE
                        || type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED
                        || type == Sensor.TYPE_ACCELEROMETER_UNCALIBRATED) {
                    double hz = s.getMinDelay() > 0 ? 1_000_000.0 / s.getMinDelay() : 0.0;
                    sb.append(String.format(Locale.US, "%s | %s | up to %.0f Hz\n",
                            s.getName(), s.getVendor(), hz));
                }
            }
        } catch (Throwable t) {
            sb.append("sensor error: ").append(t).append('\n');
        }
        return sb.toString();
    }

    private void copyToClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("device info", summary));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }
}
