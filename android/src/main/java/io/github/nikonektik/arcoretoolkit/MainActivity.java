package io.github.nikonektik.arcoretoolkit;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.CameraIntrinsics;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends Activity implements GLSurfaceView.Renderer {
    private static final String TAG = "ArCoreToolkit";

    private GLSurfaceView surfaceView;
    private Session session;
    private Config config;
    private BackgroundRenderer background;
    private PlaneRenderer planeRenderer;
    private ObjectRenderer objectRenderer;

    private final List<Anchor> anchors = Collections.synchronizedList(new ArrayList<Anchor>());
    private volatile Frame lastFrame;
    private boolean installDialogShown = false;
    private int frameCount = 0;
    private long lastStatusLog = 0;

    private TextView hud;
    private volatile String lastTracking = "-";
    private volatile int lastPlanes = 0;
    private int placed = 0;
    private static final String PREFS = "arcoretoolkit";
    private static final String DEFAULT_SERVER = "https://nikonekt.ru:9444/arcore/switch/";
    private static final String LEGACY_SERVER = "https://nikonekt.ru:9443/arcore/switch/";
    private static final String PREF_SERVER = "server";
    private static final String PREF_LABEL = "label";
    private static final String PREF_PENDING_SPEC = "pending_spec";
    private static final String PREF_PENDING_LABEL = "pending_label";
    private static final String PREF_LAST_GOOD_SPEC = "last_good_spec";
    private static final String PREF_LAST_GOOD_LABEL = "last_good_label";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean recoveryShown;

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private MaterialButton newButton(String text, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int margin = dp(4);
        lp.setMargins(margin, margin, margin, margin);
        button.setLayoutParams(lp);
        return button;
    }

    private void showError(String title, String message) {
        if (isFinishing() || isDestroyed()) return;
        try {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(getString(R.string.common_close), null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            recreate();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ToolkitLog.i(TAG, "=== start ===");
        ToolkitLog.i(TAG, "device=" + android.os.Build.DEVICE + " model=" + android.os.Build.MODEL);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
        }

        FrameLayout root = new FrameLayout(this);
        surfaceView = new GLSurfaceView(this);
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));

        hud = new TextView(this);
        hud.setText(getString(R.string.main_hud_title) + "\n"
                + getString(R.string.main_hud_profile, currentProfileLabel()) + "\n"
                + getString(R.string.main_hud_tap_hint));
        hud.setTextColor(Color.WHITE);
        hud.setTextSize(16f);
        int hudTop = dp(32);
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) hudTop += getResources().getDimensionPixelSize(id);
            android.util.TypedValue tv = new android.util.TypedValue();
            if (getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
                hudTop += android.util.TypedValue.complexToDimensionPixelSize(
                        tv.data, getResources().getDisplayMetrics());
            }
        } catch (Throwable ignored) {}
        hud.setPadding(dp(16), hudTop, dp(16), dp(16));
        hud.setShadowLayer(4f, 2f, 2f, Color.BLACK);
        hud.setClickable(false);
        hud.setFocusable(false);
        root.addView(hud, new FrameLayout.LayoutParams(-1, -2));

        // Bottom card with the navigation buttons.
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(8), dp(4), dp(8), dp(8));
        card.addView(controls, new FrameLayout.LayoutParams(-1, -2));

        controls.addView(newButton(getString(R.string.main_btn_profiles),
                v -> showProfilesDialog()));
        controls.addView(newButton(getString(R.string.main_btn_device), v ->
                startActivity(new Intent(MainActivity.this, DeviceInfoActivity.class))));
        controls.addView(newButton(getString(R.string.main_btn_marker), v ->
                startActivity(new Intent(MainActivity.this, MarkerActivity.class))));
        controls.addView(newButton(getString(R.string.main_btn_calibration), v ->
                startActivity(new Intent(MainActivity.this, CalibrationActivity.class))));
        controls.addView(newButton(getString(R.string.main_btn_print_tag), v ->
                TagPdfExporter.showPrintDialog(this)));
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(-1, -2);
        cardLp.gravity = Gravity.BOTTOM;
        cardLp.setMargins(dp(8), 0, dp(8), dp(8));
        root.addView(card, cardLp);

        setContentView(root);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        surfaceView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    ToolkitLog.i(TAG, "TOUCH up x=" + e.getX() + " y=" + e.getY());
                    placeObject(e.getX(), e.getY());
                } else {
                    ToolkitLog.i(TAG, "TOUCH action=" + e.getAction() + " x=" + e.getX() + " y=" + e.getY());
                }
                return true;
            }
        });
    }

    private void placeObject(float x, float y) {
        Frame frame = lastFrame;
        ToolkitLog.i(TAG, "PLACE x=" + x + " y=" + y + " frame=" + (frame != null));
        if (frame == null) return;
        boolean placedNow = false;
        try {
            List<HitResult> hits = frame.hitTest(x, y);
            ToolkitLog.i(TAG, "HITTEST planeHits=" + hits.size());
            for (HitResult hit : hits) {
                Trackable t = hit.getTrackable();
                if (t instanceof Plane) {
                    Plane plane = (Plane) t;
                    if (plane.getTrackingState() == TrackingState.TRACKING
                            && plane.isPoseInPolygon(hit.getHitPose())) {
                        addAnchor(hit, "PLANE");
                        placedNow = true;
                        break;
                    }
                }
            }
            if (!placedNow) {
                List<HitResult> instant = frame.hitTestInstantPlacement(x, y, 1.0f);
                ToolkitLog.i(TAG, "HITTEST instantHits=" + instant.size());
                for (HitResult hit : instant) {
                    if (hit.getTrackable() != null) {
                        addAnchor(hit, "INSTANT");
                        placedNow = true;
                        break;
                    }
                }
            }
            if (!placedNow) {
                Camera cam = frame.getCamera();
                Pose pose = cam.getPose().compose(Pose.makeTranslation(0f, -0.3f, -1.0f));
                Anchor a = session.createAnchor(pose);
                anchors.add(a);
                placed++;
                ToolkitLog.i(TAG, "PLACED_FIXED total=" + placed + " pose="
                        + pose.tx() + "," + pose.ty() + "," + pose.tz());
                placedNow = true;
            }
        } catch (Throwable t) {
            ToolkitLog.e(TAG, "hitTest failed", t);
        }
        if (placedNow) {
            updateHud();
        }
    }

    private void addAnchor(HitResult hit, String kind) {
        Anchor a = hit.createAnchor();
        anchors.add(a);
        placed++;
        if ("INSTANT".equals(kind)) {
            Toast.makeText(this, getString(R.string.main_toast_no_plane_anchor),
                    Toast.LENGTH_SHORT).show();
        }
        ToolkitLog.i(TAG, "PLACED_" + kind + " total=" + placed + " pose="
                + hit.getHitPose().tx() + "," + hit.getHitPose().ty() + "," + hit.getHitPose().tz());
    }

    private void updateHud() {
        if (hud == null) return;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                hud.setText(getString(R.string.main_hud_status, currentProfileLabel(),
                        lastTracking, lastPlanes, placed));
            }
        });
    }

    private String currentProfileLabel() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String pending = prefs.getString(PREF_PENDING_LABEL, "");
        if (!pending.isEmpty()) return pending + " (applying)";
        String label = prefs.getString(PREF_LABEL, "");
        return label.isEmpty() ? "-" : label;
    }

    /** True when the user configured a server explicitly (raw pref, no default fallback). */
    private boolean serverConfigured() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String base = prefs.getString(PREF_SERVER, "");
        return base != null && !base.trim().isEmpty();
    }

    private String serverBase() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String base = prefs.getString(PREF_SERVER, "");
        if (base == null) base = "";
        base = base.trim();
        if (base.isEmpty()) {
            base = DEFAULT_SERVER;
        } else if (base.equals(LEGACY_SERVER)) {
            base = DEFAULT_SERVER;
            prefs.edit().putString(PREF_SERVER, DEFAULT_SERVER).apply();
        }
        if (!base.endsWith("/")) base += "/";
        return base;
    }

    private String arCoreApkUrl() {
        String base = serverBase();
        int idx = base.indexOf("/arcore/switch/");
        if (idx >= 0) {
            return base.substring(0, idx) + "/arcore/apk";
        }
        return base.endsWith("/") ? base + "arcore.apk" : base + "/arcore.apk";
    }

    private void editServer() {
        final EditText input = new EditText(this);
        input.setHint(getString(R.string.main_server_hint));
        input.setText(serverBase());
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.main_server_title))
                .setMessage(getString(R.string.main_server_message))
                .setView(input)
                .setPositiveButton(getString(R.string.main_server_save), (d, w) -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(PREF_SERVER, input.getText().toString().trim()).apply();
                    Toast.makeText(this, getString(R.string.main_toast_server_saved),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(getString(R.string.common_cancel), null)
                .show();
    }

    private String nextVersion() {
        return arcoreVersionBase() + (System.currentTimeMillis() / 1000L);
    }

    private String arcoreVersionBase() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo("com.google.ar.core", 0);
            if (info.versionName != null) {
                String[] parts = info.versionName.split("\\.");
                if (parts.length >= 2) return parts[0] + "." + parts[1] + ".";
            }
        } catch (Throwable ignored) {
        }
        return "1.57.";
    }

    private String urlFor(String spec) {
        String base = serverBase();
        if (base.isEmpty() || spec == null) return null;
        if (spec.startsWith("name:")) {
            return base + spec.substring("name:".length()) + "/" + nextVersion() + ".zip";
        }
        if (spec.startsWith("custom:")) {
            return base + "custom/" + nextVersion() + ".zip?" + spec.substring("custom:".length());
        }
        return null;
    }

    private void applySpec(String spec, String label) {
        String url = urlFor(spec);
        if (url == null) {
            Toast.makeText(this, getString(R.string.main_toast_set_server_first),
                    Toast.LENGTH_LONG).show();
            editServer();
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_PENDING_SPEC, spec)
                .putString(PREF_PENDING_LABEL, label)
                .apply();
        recoveryShown = false;
        ToolkitLog.i(TAG, "APPLY " + label + " -> " + url);
        Toast.makeText(this, getString(R.string.main_toast_applying, label),
                Toast.LENGTH_LONG).show();
        try {
            Intent intent = new Intent();
            intent.setClassName("com.google.ar.core",
                    "com.google.ar.core.services.profiles.ProfileDownloadActivity");
            intent.putExtra("explicit-download-url", url);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            ToolkitLog.e(TAG, "switch failed", t);
            Toast.makeText(this, getString(R.string.main_toast_switch_failed, t),
                    Toast.LENGTH_LONG).show();
            return;
        }
        ui.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) recreate();
        }, 6000);
    }

    private void applyNamed(String name) {
        applySpec("name:" + name, name);
    }

    private void applyCustom(String query) {
        applySpec("custom:" + query, "Custom tune");
    }

    private void markProfileWorking() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String spec = prefs.getString(PREF_PENDING_SPEC, "");
        if (spec.isEmpty()) return;
        prefs.edit()
                .putString(PREF_LAST_GOOD_SPEC, spec)
                .putString(PREF_LAST_GOOD_LABEL, prefs.getString(PREF_PENDING_LABEL, ""))
                .putString(PREF_LABEL, prefs.getString(PREF_PENDING_LABEL, ""))
                .remove(PREF_PENDING_SPEC)
                .remove(PREF_PENDING_LABEL)
                .apply();
    }

    private void showRecoveryDialog() {
        if (recoveryShown || isFinishing() || isDestroyed()) return;
        recoveryShown = true;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        final String spec = prefs.getString(PREF_LAST_GOOD_SPEC, "");
        final String label = prefs.getString(PREF_LAST_GOOD_LABEL,
                getString(R.string.main_recovery_default_label));
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.main_recovery_title))
                .setMessage(getString(R.string.main_recovery_message))
                .setNeutralButton(getString(R.string.main_btn_profiles), (d, w) -> showProfilesDialog())
                .setNegativeButton(getString(R.string.common_close), null);
        if (!spec.isEmpty()) {
            builder.setPositiveButton(getString(R.string.main_recovery_reapply, label),
                    (d, w) -> applySpec(spec, label));
        }
        builder.show();
    }

    private static final class ProfileEntry {
        String name;
        double fx;
        double fy;
        double cx;
        double cy;
    }

    private static String profileKey(String device) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(new byte[]{0x0c, (byte) 0xe8, (byte) 0x8d, 0x52, 0x6f, (byte) 0xb7, 0x40, (byte) 0xd7});
            byte[] digest = md.digest(device.getBytes("UTF-8"));
            return Base64.encodeToString(digest,
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING).substring(0, 6);
        } catch (Throwable t) {
            return "?";
        }
    }

    private interface CatalogCallback {
        void onCatalog(List<ProfileEntry> catalog, String error);
    }

    private void fetchCatalog(final CatalogCallback callback) {
        final String base = serverBase();
        if (base.isEmpty()) {
            callback.onCatalog(null, "no server configured");
            return;
        }
        new Thread(() -> {
            List<ProfileEntry> out = new ArrayList<>();
            String error = null;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(base + "profiles").openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                int code = conn.getResponseCode();
                if (code != 200) throw new RuntimeException("HTTP " + code);
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
                JSONArray array = new JSONObject(body.toString()).getJSONArray("profiles");
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    ProfileEntry entry = new ProfileEntry();
                    entry.name = item.getString("name");
                    entry.fx = item.optDouble("fx", 0);
                    entry.fy = item.optDouble("fy", 0);
                    entry.cx = item.optDouble("cx", 320);
                    entry.cy = item.optDouble("cy", 240);
                    out.add(entry);
                }
            } catch (Throwable t) {
                error = t.toString();
            }
            final List<ProfileEntry> result = out;
            final String failure = error;
            ui.post(() -> callback.onCatalog(result, failure));
        }).start();
    }

    private void showProfilesDialog() {
        if (!serverConfigured()) {
            // Explain the flow before the user tries a profile without a server.
            new MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.main_profiles_unconfigured_title))
                    .setMessage(getString(R.string.main_profiles_unconfigured_message))
                    .setPositiveButton(getString(R.string.main_profiles_unconfigured_set),
                            (d, w) -> editServer())
                    .setNeutralButton(getString(R.string.main_profiles_unconfigured_default),
                            (d, w) -> loadProfiles())
                    .setNegativeButton(getString(R.string.common_cancel), null)
                    .show();
            return;
        }
        loadProfiles();
    }

    private void loadProfiles() {
        Toast.makeText(this, getString(R.string.main_toast_loading_profiles),
                Toast.LENGTH_SHORT).show();
        fetchCatalog((catalog, error) -> {
            if (isFinishing() || isDestroyed()) return;
            if (error != null) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.main_server_title))
                        .setMessage(getString(R.string.main_profiles_error_message, error))
                        .setPositiveButton(getString(R.string.main_profiles_server_url), (d, w) -> editServer())
                        .setNegativeButton(getString(R.string.common_close), null)
                        .show();
                return;
            }
            if (catalog == null || catalog.isEmpty()) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.main_server_title))
                        .setMessage(getString(R.string.main_profiles_empty_message))
                        .setPositiveButton(getString(R.string.main_profiles_server_url), (d, w) -> editServer())
                        .setNegativeButton(getString(R.string.common_close), null)
                        .show();
                return;
            }
            buildProfilesDialog(catalog);
        });
    }

    private void buildProfilesDialog(final List<ProfileEntry> catalog) {
        CameraSpec camera = CameraSpec.read(this);
        final ProfileEntry nearest = camera != null && camera.valid()
                ? closestEntry(catalog, camera.fx(), camera.fy()) : null;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(12), dp(24), dp(12));

        TextView info = new TextView(this);
        String text = getString(R.string.main_profiles_info, serverBase(),
                android.os.Build.DEVICE, profileKey(android.os.Build.DEVICE));
        if (camera != null && camera.valid()) {
            text += "\n" + getString(R.string.main_profiles_info_camera, camera.fx(), camera.fy());
        }
        info.setText(text);
        info.setTextSize(12f);
        box.addView(info);

        box.addView(newButton(getString(R.string.main_profiles_auto), v -> autoConfigure(catalog)));
        box.addView(newButton(getString(R.string.main_profiles_advanced), v -> showTuneDialog(catalog)));
        box.addView(newButton(getString(R.string.main_profiles_server_url), v -> editServer()));

        for (ProfileEntry entry : catalog) {
            MaterialButton button = newButton(entryLabel(entry), v -> applyNamed(entry.name));
            button.setAllCaps(false);
            if (entry == nearest) {
                button.setText(entryLabel(entry) + getString(R.string.main_profiles_nearest_suffix));
            }
            box.addView(button);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.main_profiles_title))
                .setView(scroll)
                .setNegativeButton(getString(R.string.common_close), null)
                .show();
    }

    private String entryLabel(ProfileEntry entry) {
        return getString(R.string.profiles_entry_format, entry.name, entry.fx);
    }

    private static ProfileEntry closestEntry(List<ProfileEntry> catalog, float fx, float fy) {
        ProfileEntry best = null;
        double bestScore = Double.MAX_VALUE;
        for (ProfileEntry entry : catalog) {
            if (entry.fx <= 0) continue;
            double score = Math.abs(entry.fx - fx) / fx + Math.abs(entry.fy - fy) / fy;
            if (score < bestScore) {
                bestScore = score;
                best = entry;
            }
        }
        return best;
    }

    private void autoConfigure(List<ProfileEntry> catalog) {
        CameraSpec camera = CameraSpec.read(this);
        if (camera == null || !camera.valid()) {
            Toast.makeText(this, getString(R.string.main_toast_camera_unavailable),
                    Toast.LENGTH_LONG).show();
            return;
        }
        ProfileEntry base = closestEntry(catalog, camera.fx(), camera.fy());
        if (base == null) {
            Toast.makeText(this, getString(R.string.main_toast_no_usable_profiles),
                    Toast.LENGTH_LONG).show();
            return;
        }
        String query = "base=" + base.name + String.format(Locale.US,
                "&fx=%.2f&fy=%.2f&cx=320&cy=240&dev=%s",
                camera.fx(), camera.fy(), android.os.Build.DEVICE);
        Toast.makeText(this, getString(R.string.main_toast_closest,
                        base.name, base.fx, camera.fx()), Toast.LENGTH_LONG).show();
        applySpec("custom:" + query, "Auto (" + base.name + ")");
    }

    private void showTuneDialog(final List<ProfileEntry> catalog) {
        if (catalog == null || catalog.isEmpty()) {
            Toast.makeText(this, getString(R.string.main_toast_no_profiles),
                    Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(12), dp(24), dp(12));

        TextView hint = new TextView(this);
        hint.setText(getString(R.string.tune_intro));
        hint.setTextSize(12f);
        hint.setPadding(0, 0, 0, dp(6));
        box.addView(hint);

        TextView baseHint = new TextView(this);
        baseHint.setText(getString(R.string.tune_base_profile));
        baseHint.setTextSize(12f);
        baseHint.setPadding(0, 0, 0, dp(2));
        box.addView(baseHint);

        final List<String> baseNames = new ArrayList<>();
        List<String> baseLabels = new ArrayList<>();
        for (ProfileEntry entry : catalog) {
            baseNames.add(entry.name);
            baseLabels.add(entryLabel(entry));
        }
        final Spinner baseSpinner = new Spinner(this);
        ArrayAdapter<String> baseAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, baseLabels);
        baseAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        baseSpinner.setAdapter(baseAdapter);
        box.addView(baseSpinner);

        addHeader(box, getString(R.string.tune_section_camera));
        final EditText fx = addField(box, "focal x", "464.56", getString(R.string.tune_hint_fx));
        final EditText fy = addField(box, "focal y", "464.56", getString(R.string.tune_hint_fy));
        final EditText cx = addField(box, "principal x", "320", getString(R.string.tune_hint_cx));
        final EditText cy = addField(box, "principal y", "240", getString(R.string.tune_hint_cy));
        final EditText k0 = addField(box, "distortion k0", "0.001", getString(R.string.tune_hint_k0));
        final EditText k1 = addField(box, "distortion k1", "0", getString(R.string.tune_hint_k1));
        final EditText k2 = addField(box, "distortion k2", "0", getString(R.string.tune_hint_k2));

        addHeader(box, getString(R.string.tune_section_sync));
        final EditText rs = addField(box, "rolling shutter, ns", "26763333", getString(R.string.tune_hint_rs));
        final EditText tsa = addField(box, "timestamp alignment, ns", "-20000000", getString(R.string.tune_hint_tsa));
        final EditText tb = addField(box, "timebase trust (1/2)", "2", getString(R.string.tune_hint_tb));
        final EditText rss = addField(box, "rs source (1/2)", "1", getString(R.string.tune_hint_rss));

        addHeader(box, getString(R.string.tune_section_lever));
        final EditText px = addField(box, "px", "0", getString(R.string.tune_hint_px));
        final EditText py = addField(box, "py", "0", getString(R.string.tune_hint_py));
        final EditText pz = addField(box, "pz", "0", getString(R.string.tune_hint_pz));

        addHeader(box, getString(R.string.tune_section_rotation));
        final EditText qw = addField(box, "qw", "0.0015", getString(R.string.tune_hint_qw));
        final EditText qx = addField(box, "qx", "-0.7059", getString(R.string.tune_hint_qx));
        final EditText qy = addField(box, "qy", "0.7083", getString(R.string.tune_hint_qy));
        final EditText qz = addField(box, "qz", "-0.0011", getString(R.string.tune_hint_qz));

        addHeader(box, getString(R.string.tune_section_imu));
        final EditText gsx = addField(box, "gyro scale x", "1.0286", getString(R.string.tune_hint_gsx));
        final EditText gsy = addField(box, "gyro scale y", "1.0151", getString(R.string.tune_hint_gsy));
        final EditText gsz = addField(box, "gyro scale z", "1.0020", getString(R.string.tune_hint_gsz));
        final EditText asx = addField(box, "accel scale x", "1", getString(R.string.tune_hint_asx));
        final EditText asy = addField(box, "accel scale y", "1", getString(R.string.tune_hint_asy));
        final EditText asz = addField(box, "accel scale z", "1", getString(R.string.tune_hint_asz));
        final EditText gn = addField(box, "gyro noise", "0.0005309", getString(R.string.tune_hint_gn));
        final EditText an = addField(box, "accel noise", "0.0048836", getString(R.string.tune_hint_an));
        final EditText gbx = addField(box, "gyro bias x", "0.0046", getString(R.string.tune_hint_gbx));
        final EditText gby = addField(box, "gyro bias y", "-0.0022", getString(R.string.tune_hint_gby));
        final EditText gbz = addField(box, "gyro bias z", "0.0015", getString(R.string.tune_hint_gbz));

        addHeader(box, getString(R.string.tune_section_depth));
        final EditText depth = addField(box, "depth mode (1/2)", "2", getString(R.string.tune_hint_depth));

        MaterialButton zero = newButton(getString(R.string.tune_preset_zero_tsa), v -> {
            tsa.setText("0");
            tb.setText("2");
        });
        box.addView(zero, 3);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.tune_title))
                .setView(scroll)
                .setPositiveButton(getString(R.string.tune_apply), (dialog, which) -> {
                    String q = "base=" + baseNames.get(baseSpinner.getSelectedItemPosition())
                            + "&fx=" + val(fx) + "&fy=" + val(fy)
                            + "&cx=" + val(cx) + "&cy=" + val(cy)
                            + "&k0=" + val(k0) + "&k1=" + val(k1) + "&k2=" + val(k2)
                            + "&rs=" + val(rs) + "&tsa=" + val(tsa)
                            + "&tb=" + val(tb) + "&rss=" + val(rss) + "&depth=" + val(depth)
                            + "&px=" + val(px) + "&py=" + val(py) + "&pz=" + val(pz)
                            + "&qw=" + val(qw) + "&qx=" + val(qx) + "&qy=" + val(qy) + "&qz=" + val(qz)
                            + "&gsx=" + val(gsx) + "&gsy=" + val(gsy) + "&gsz=" + val(gsz)
                            + "&asx=" + val(asx) + "&asy=" + val(asy) + "&asz=" + val(asz)
                            + "&gn=" + val(gn) + "&an=" + val(an)
                            + "&gbx=" + val(gbx) + "&gby=" + val(gby) + "&gbz=" + val(gbz);
                    applyCustom(q);
                })
                .setNegativeButton(getString(R.string.common_cancel), null)
                .show();
    }

    private void addHeader(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16f);
        tv.setPadding(0, dp(12), 0, dp(2));
        parent.addView(tv);
    }

    private EditText addField(LinearLayout parent, String label, String value, String description) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(14f);
        tv.setPadding(0, dp(8), 0, 0);
        EditText et = new EditText(this);
        et.setText(value);
        et.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        TextView desc = new TextView(this);
        desc.setText(description);
        desc.setTextSize(12f);
        desc.setPadding(0, 0, 0, dp(4));
        parent.addView(tv);
        parent.addView(et);
        parent.addView(desc);
        return et;
    }

    private static String val(EditText e) {
        return e.getText().toString().trim().replace(',', '.');
    }

    @Override
    protected void onResume() {
        super.onResume();
        ToolkitLog.i(TAG, "onResume");
        try {
            ArCoreApk.Availability a = ArCoreApk.getInstance().checkAvailability(this);
            ToolkitLog.i(TAG, "availability=" + a);
            if (a == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED
                    || a == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD) {
                if (ArCoreInstaller.retryPending(this)) return;
                if (!installDialogShown) {
                    installDialogShown = true;
                    ArCoreInstaller.showInstallDialog(this, arCoreApkUrl(), String.valueOf(a));
                }
                return;
            }
            if (!a.isSupported()) {
                ToolkitLog.e(TAG, "ARCore not supported: " + a);
                if ((a == ArCoreApk.Availability.UNKNOWN_ERROR
                        || a == ArCoreApk.Availability.UNKNOWN_TIMED_OUT)
                        && !installDialogShown) {
                    installDialogShown = true;
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(getString(R.string.main_unavailable_title))
                            .setMessage(getString(R.string.main_unavailable_message, String.valueOf(a)))
                            .setPositiveButton(getString(R.string.main_unavailable_install), (d, w) ->
                                    ArCoreInstaller.showInstallDialog(
                                            this, arCoreApkUrl(), String.valueOf(a)))
                            .setNegativeButton(getString(R.string.common_close), null)
                            .show();
                } else {
                    showError(getString(R.string.main_unavailable_title),
                            getString(R.string.main_unavailable_hint, String.valueOf(a)));
                }
                return;
            }
            if (session == null) {
                session = new Session(this);
                config = new Config(session);
                config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
                config.setFocusMode(Config.FocusMode.AUTO);
                config.setInstantPlacementMode(Config.InstantPlacementMode.LOCAL_Y_UP);
                session.configure(config);
                ToolkitLog.i(TAG, "SESSION_CREATED_OK configPlane=" + config.getPlaneFindingMode()
                        + " configInstant=" + config.getInstantPlacementMode());
            }
            session.resume();
            ToolkitLog.i(TAG, "SESSION_RESUMED_OK");
            markProfileWorking();
        } catch (com.google.ar.core.exceptions.FatalException fatal) {
            ToolkitLog.e(TAG, "onResume FATAL: profile rejected", fatal);
            closeSessionQuietly();
            showRecoveryDialog();
        } catch (com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException incompatible) {
            ToolkitLog.e(TAG, "onResume incompatible device/profile", incompatible);
            closeSessionQuietly();
            showRecoveryDialog();
        } catch (Throwable t) {
            ToolkitLog.e(TAG, "onResume FAILED", t);
            closeSessionQuietly();
            showError(getString(R.string.main_session_failed_title), String.valueOf(t));
        }
        if (surfaceView != null) surfaceView.onResume();
    }

    private void closeSessionQuietly() {
        if (session != null) {
            try { session.close(); } catch (Throwable ignored) {}
            session = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (surfaceView != null) surfaceView.onPause();
        } catch (Throwable t) {
            ToolkitLog.e(TAG, "surface pause failed", t);
        }
        if (session != null) {
            try {
                session.pause();
            } catch (Throwable t) {
                ToolkitLog.e(TAG, "session pause failed", t);
            }
        }
        ToolkitLog.i(TAG, "onPause");
    }

    @Override
    protected void onDestroy() {
        if (session != null) {
            try {
                session.close();
            } catch (Throwable ignored) {}
            session = null;
        }
        super.onDestroy();
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        ToolkitLog.i(TAG, "onSurfaceCreated");
        try {
            GLES20.glClearColor(0.02f, 0.02f, 0.05f, 1f);
            background = new BackgroundRenderer();
            background.createOnGlThread();
            planeRenderer = new PlaneRenderer();
            planeRenderer.createOnGlThread();
            objectRenderer = new ObjectRenderer();
            objectRenderer.createOnGlThread();
            if (session != null && background.getTextureId() != 0) {
                session.setCameraTextureName(background.getTextureId());
                ToolkitLog.i(TAG, "setCameraTextureName=" + background.getTextureId());
            }
        } catch (Throwable t) {
            ToolkitLog.e(TAG, "GL setup failed", t);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        ToolkitLog.i(TAG, "onSurfaceChanged " + width + "x" + height);
        GLES20.glViewport(0, 0, width, height);
        if (session != null && width > 0 && height > 0) {
            try {
                session.setDisplayGeometry(getDisplayRotation(), width, height);
            } catch (Throwable t) {
                ToolkitLog.e(TAG, "setDisplayGeometry failed", t);
            }
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        if (session == null) return;
        try {
            session.setDisplayGeometry(getDisplayRotation(), surfaceView.getWidth(), surfaceView.getHeight());
            Frame frame = session.update();
            lastFrame = frame;
            background.draw(frame);

            Camera camera = frame.getCamera();
            frameCount++;
            long now = SystemClock.elapsedRealtime();
            if (now - lastStatusLog > 2000) {
                lastStatusLog = now;
                int planes = 0;
                try {
                    planes = session.getAllTrackables(Plane.class).size();
                } catch (Throwable ignored) {}
                lastPlanes = planes;
                lastTracking = String.valueOf(camera.getTrackingState());
                updateHud();
                CameraIntrinsics in = camera.getImageIntrinsics();
                float[] f = in.getFocalLength();
                float[] pp = in.getPrincipalPoint();
                com.google.ar.core.Pose cp = camera.getPose();
                ToolkitLog.i(TAG, "STATUS frame=" + frameCount
                        + " tracking=" + camera.getTrackingState()
                        + " planes=" + planes
                        + " anchors=" + anchors.size()
                        + " focal=" + String.format("%.2f,%.2f", f[0], f[1])
                        + " pp=" + String.format("%.2f,%.2f", pp[0], pp[1])
                        + " pos=" + String.format("%.3f,%.3f,%.3f", cp.tx(), cp.ty(), cp.tz()));
            }
            if (camera.getTrackingState() == TrackingState.TRACKING) {
                float[] view = new float[16];
                float[] proj = new float[16];
                camera.getViewMatrix(view, 0);
                camera.getProjectionMatrix(proj, 0, 0.1f, 100f);
                planeRenderer.draw(session.getAllTrackables(Plane.class), view, proj);
                objectRenderer.draw(view, proj, anchors);
            }

            synchronized (anchors) {
                for (int i = anchors.size() - 1; i >= 0; i--) {
                    Anchor a = anchors.get(i);
                    if (a.getTrackingState() == TrackingState.STOPPED) {
                        a.detach();
                        anchors.remove(i);
                    }
                }
            }
        } catch (Throwable t) {
            ToolkitLog.e(TAG, "frame FAILED", t);
        }
    }

    private int getDisplayRotation() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_0: return 0;
            case Surface.ROTATION_90: return 1;
            case Surface.ROTATION_180: return 2;
            case Surface.ROTATION_270: return 3;
            default: return 0;
        }
    }
}
