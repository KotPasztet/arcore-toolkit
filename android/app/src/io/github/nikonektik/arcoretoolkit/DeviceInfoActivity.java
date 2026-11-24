package io.github.nikonektik.arcoretoolkit;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * Camera and IMU characteristics of this device plus the intrinsics ARCore
 * expects for its 640x480 stream.
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
        CameraSpec ci = CameraSpec.read(this);
        if (ci == null) {
            return sb.append("no back camera found\n").toString();
        }
        sb.append("camera id: ").append(ci.cameraId).append('\n');
        sb.append("active array: ").append(ci.activeArray).append('\n');
        sb.append("pixel array: ").append(ci.pixelArray).append('\n');
        sb.append("sensor orientation: ").append(ci.sensorOrientation).append('\n');
        sb.append("timestamp source: ")
          .append(ci.timestampSource == null ? "?"
                  : (ci.timestampSource == 1 ? "REALTIME" : "MONOTONIC"))
          .append('\n');
        if (ci.physicalSize != null) {
            sb.append(String.format(Locale.US, "physical size: %.3f x %.3f mm\n",
                    ci.physicalSize.getWidth(), ci.physicalSize.getHeight()));
        }
        if (ci.focalLength > 0f) {
            sb.append(String.format(Locale.US, "focal length: %.3f mm\n", ci.focalLength));
        }
        if (ci.calibration != null && ci.calibration.length >= 4) {
            sb.append("factory intrinsic calibration: ");
            for (int i = 0; i < ci.calibration.length; i++) {
                sb.append(i == 0 ? "" : ", ").append(ci.calibration[i]);
            }
            sb.append('\n');
        }
        if (ci.distortion != null && ci.distortion.length > 0) {
            sb.append("factory distortion: ");
            for (int i = 0; i < ci.distortion.length; i++) {
                sb.append(i == 0 ? "" : ", ").append(ci.distortion[i]);
            }
            sb.append('\n');
        }
        if (ci.valid()) {
            sb.append(String.format(Locale.US,
                    "\n=> for 640x480: fx=%.1f fy=%.1f  (HFOV %.1f deg, VFOV %.1f deg)\n",
                    ci.fx(), ci.fy(), ci.hfovDeg(), ci.vfovDeg()));
            sb.append(String.format(Locale.US,
                    "   arcore-profile patch base.pb -o out.pb --fx %.1f --fy %.1f --cx 320 --cy 240\n",
                    ci.fx(), ci.fy()));
            sb.append("   (verify with a printed AprilGrid; distortion is not known from the HAL)\n");
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
