package io.github.nikonektik.arcoretoolkit;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends Activity implements GLSurfaceView.Renderer {
    private static final String TAG = "ARCOREGAME";

    private GLSurfaceView surfaceView;
    private Session session;
    private Config config;
    private BackgroundRenderer background;
    private PlaneRenderer planeRenderer;
    private ObjectRenderer objectRenderer;

    private final List<Anchor> anchors = Collections.synchronizedList(new ArrayList<Anchor>());
    private volatile Frame lastFrame;
    private boolean installRequested = false;
    private int frameCount = 0;
    private long lastStatusLog = 0;

    private TextView hud;
    private volatile String lastTracking = "-";
    private volatile int lastPlanes = 0;
    private int placed = 0;
    private Spinner profileSpinner;

    private static final String PREFS = "arcoretoolkit";
    private static final String PREF_PROFILE = "profile";
    private static final String PREF_SERVER = "server";
    private static final String[] PROFILE_LABELS = {
            "chopin patched (best)",
            "marble (POCO F5)",
            "chopin (POCO X3 GT)",
            "vayu (POCO X3 Pro)",
            "alioth (POCO F3)",
            "sweet (Redmi Note 10 Pro)",
            "flame (Pixel 4)",
            "rs_source=2",
            "tsalign=0",
            "timebase=2",
            "depth=1",
            "extr inverse",
            "generic IMU"
    };
    private static final String[] PROFILE_KEYS = {
            "chopin_patched", "marble", "chopin", "vayu", "alioth", "sweet", "flame",
            "rs2", "tsa0", "tb2", "depth1", "extr", "genericimu"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "=== start ===");
        Log.i(TAG, "device=" + android.os.Build.DEVICE + " model=" + android.os.Build.MODEL);

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
        hud.setText("ARCore Toolkit\nProfile: " + currentProfileLabel()
                + "\nTap a green plane to place a cube");
        hud.setTextColor(Color.WHITE);
        hud.setTextSize(16f);
        int hudTop = 64;
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) hudTop += getResources().getDimensionPixelSize(id);
            android.util.TypedValue tv = new android.util.TypedValue();
            if (getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
                hudTop += android.util.TypedValue.complexToDimensionPixelSize(
                        tv.data, getResources().getDisplayMetrics());
            }
        } catch (Throwable ignored) {}
        hud.setPadding(32, hudTop, 32, 32);
        hud.setShadowLayer(4f, 2f, 2f, Color.BLACK);
        hud.setClickable(false);
        hud.setFocusable(false);
        root.addView(hud, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(32, 16, 32, 48);
        controls.setBackgroundColor(0xAA000000);

        profileSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, PROFILE_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(adapter);
        String savedKey = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_PROFILE, "chopin_patched");
        for (int i = 0; i < PROFILE_KEYS.length; i++) {
            if (PROFILE_KEYS[i].equals(savedKey)) {
                profileSpinner.setSelection(i);
                break;
            }
        }

        Button apply = new Button(this);
        apply.setText("Apply profile & restart");
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyProfile();
            }
        });

        Button tune = new Button(this);
        tune.setText("Custom tune...");
        tune.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTuneDialog();
            }
        });

        Button server = new Button(this);
        server.setText("Profile server...");
        server.setOnClickListener(v -> editServer());

        Button device = new Button(this);
        device.setText("Device info / intrinsics");
        device.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, DeviceInfoActivity.class)));

        Button marker = new Button(this);
        marker.setText("Marker AR (прототип)");
        marker.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, MarkerActivity.class));
            }
        });

        Button calib = new Button(this);
        calib.setText("Калибровка (Kalibr)");
        calib.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, CalibrationActivity.class));
            }
        });

        controls.addView(profileSpinner);
        controls.addView(apply);
        controls.addView(tune);
        controls.addView(server);
        controls.addView(device);
        controls.addView(marker);
        controls.addView(calib);
        FrameLayout.LayoutParams controlsLp = new FrameLayout.LayoutParams(-1, -2);
        controlsLp.gravity = Gravity.BOTTOM;
        root.addView(controls, controlsLp);

        setContentView(root);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        surfaceView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    Log.i(TAG, "TOUCH up x=" + e.getX() + " y=" + e.getY());
                    placeObject(e.getX(), e.getY());
                } else {
                    Log.i(TAG, "TOUCH action=" + e.getAction() + " x=" + e.getX() + " y=" + e.getY());
                }
                return true;
            }
        });
    }

    private void placeObject(float x, float y) {
        Frame frame = lastFrame;
        Log.i(TAG, "PLACE x=" + x + " y=" + y + " frame=" + (frame != null));
        if (frame == null) return;
        boolean placedNow = false;
        try {
            List<HitResult> hits = frame.hitTest(x, y);
            Log.i(TAG, "HITTEST planeHits=" + hits.size());
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
                Log.i(TAG, "HITTEST instantHits=" + instant.size());
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
                Log.i(TAG, "PLACED_FIXED total=" + placed + " pose="
                        + pose.tx() + "," + pose.ty() + "," + pose.tz());
                placedNow = true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "hitTest failed", t);
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
            Toast.makeText(this, "Нет плоскости: якорь временный, объект может смещаться", Toast.LENGTH_SHORT).show();
        }
        Log.i(TAG, "PLACED_" + kind + " total=" + placed + " pose="
                + hit.getHitPose().tx() + "," + hit.getHitPose().ty() + "," + hit.getHitPose().tz());
    }

    private void updateHud() {
        if (hud == null) return;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                hud.setText("ARCore Toolkit\nProfile: " + currentProfileLabel()
                        + "\nTracking: " + lastTracking
                        + "\nPlanes: " + lastPlanes
                        + "\nCubes: " + placed
                        + "\nTap to place");
            }
        });
    }

    private String currentProfileLabel() {
        String key = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_PROFILE, "chopin_patched");
        if ("custom".equals(key)) {
            return "Custom tune";
        }
        for (int i = 0; i < PROFILE_KEYS.length; i++) {
            if (PROFILE_KEYS[i].equals(key)) {
                return PROFILE_LABELS[i];
            }
        }
        return key;
    }

    private String serverBase() {
        String base = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_SERVER, "");
        if (base == null) base = "";
        base = base.trim();
        if (base.isEmpty()) return "";
        if (!base.endsWith("/")) base += "/";
        return base;
    }

    private void editServer() {
        final EditText input = new EditText(this);
        input.setHint("https://host:9443/arcore/switch/");
        input.setText(serverBase());
        new AlertDialog.Builder(this)
                .setTitle("Profile server")
                .setMessage("Base URL of the switch server (see tools/server). "
                        + "A profile URL looks like <base><name>/<version>.zip")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(PREF_SERVER, input.getText().toString().trim()).apply();
                    Toast.makeText(this, "Server saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applyProfile() {
        int idx = profileSpinner.getSelectedItemPosition();
        if (idx < 0 || idx >= PROFILE_KEYS.length) {
            return;
        }
        String key = PROFILE_KEYS[idx];
        String base = serverBase();
        if (base.isEmpty()) {
            Toast.makeText(this, "Set the profile server first", Toast.LENGTH_LONG).show();
            editServer();
            return;
        }
        String url = base + key + "/" + nextVersion() + ".zip";
        triggerProfile(url, key, PROFILE_LABELS[idx]);
    }

    private String nextVersion() {
        return "1.57." + (System.currentTimeMillis() / 1000L);
    }

    private void triggerProfile(String url, String prefsKey, String label) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_PROFILE, prefsKey).apply();
        Log.i(TAG, "SWITCH profile=" + prefsKey + " url=" + url);
        Toast.makeText(this, "Applying " + label + "...", Toast.LENGTH_SHORT).show();
        try {
            Intent intent = new Intent();
            intent.setClassName("com.google.ar.core",
                    "com.google.ar.core.services.profiles.ProfileDownloadActivity");
            intent.putExtra("explicit-download-url", url);
            startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "switch failed", t);
            Toast.makeText(this, "Switch failed: " + t, Toast.LENGTH_LONG).show();
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                recreate();
            }
        }, 6000);
    }

    private void showTuneDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 24);
        box.setBackgroundColor(Color.WHITE);

        TextView hint = new TextView(this);
        hint.setText("Меняйте по одному параметру за раз. После «Применить» профиль "
                + "пересобирается на сервере, а ARCore перезапускается. "
                + "Пресеты заполняют поля готовыми наборами — дальше можно править вручную.");
        hint.setTextColor(Color.DKGRAY);
        hint.setTextSize(12f);
        hint.setPadding(0, 0, 0, 12);
        box.addView(hint);

        TextView baseHint = new TextView(this);
        baseHint.setText("Быстрый тест базы профиля (камера X6 Pro, лучшая синхронизация):");
        baseHint.setTextColor(Color.DKGRAY);
        baseHint.setTextSize(12f);
        baseHint.setPadding(0, 0, 0, 4);
        box.addView(baseHint);

        final Spinner baseSpinner = new Spinner(this);
        ArrayAdapter<String> baseAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"chopin", "marble", "vayu", "sweet", "flame", "alioth"});
        baseAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        baseSpinner.setAdapter(baseAdapter);
        box.addView(baseSpinner);

        Button applyBase = new Button(this);
        applyBase.setText("Применить базу");
        applyBase.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String b = String.valueOf(baseSpinner.getSelectedItem());
                applyCustom("base=" + b + "&keep_extr=1&keep_imu=1"
                        + "&fx=464.56&fy=464.56&cx=320&cy=240&k0=0.001&k1=0&k2=0"
                        + "&rs=26763333&tsa=-20000000&tb=2&rss=1&depth=2");
            }
        });
        box.addView(applyBase);

        addHeader(box, "Камера (640×480)");
        final EditText fx = addField(box, "focal x", "464.56",
                "Фокусное расстояние по X в пикселях для кадра 640×480. Влияет на масштаб и глубину.");
        final EditText fy = addField(box, "focal y", "464.56",
                "Фокусное расстояние по Y в пикселях. Обычно равно focal x.");
        final EditText cx = addField(box, "principal x", "320",
                "Главная точка по X. Паспортное значение — 320. Калибровка давала 302–310; "
                        + "если плоскости смещены вбок, попробуйте 302.");
        final EditText cy = addField(box, "principal y", "240",
                "Главная точка по Y. Паспортное значение — 240.");
        final EditText k0 = addField(box, "distortion k0", "0.001",
                "Радиальная дисторсия k0. У X6 Pro почти ноль.");
        final EditText k1 = addField(box, "distortion k1", "0",
                "Дисторсия k1. Обычно 0; большие значения ломают трекинг.");
        final EditText k2 = addField(box, "distortion k2", "0",
                "Дисторсия k2. Обычно 0.");

        addHeader(box, "Синхронизация камеры и IMU");
        final EditText rs = addField(box, "rolling shutter, нс", "26763333",
                "Реальное время считывания кадра из метаданных сенсора — 26 763 333 нс (26.76 мс). "
                        + "Неверное значение сильно ломает глубину при движении.");
        final EditText tsa = addField(box, "timestamp alignment, нс", "-20000000",
                "Сдвиг метки времени камеры относительно IMU. Отрицательное значение — камера "
                        + "считается «раньше». Подбирается по логам DESYNC.");
        final EditText tb = addField(box, "timebase trust (1/2)", "2",
                "1 — RUNTIME_CHECK_REQUIRED, 2 — TRUSTED_AS_BOOTTIME. Для этого устройства лучше 2.");
        final EditText rss = addField(box, "rs source (1/2)", "1",
                "1 — из профиля, 2 — IMAGE_METADATA. Если камера отдаёт readout-метки, попробуйте 2.");

        addHeader(box, "Положение камеры относительно IMU (lever arm), метры");
        final EditText px = addField(box, "px", "0",
                "Сдвиг IMU по X в системе камеры. 0 — безопасный вариант, если реальное плечо неизвестно.");
        final EditText py = addField(box, "py", "0",
                "Сдвиг IMU по Y. Обычно 0–0.06.");
        final EditText pz = addField(box, "pz", "0",
                "Сдвиг IMU по Z (вдоль оптической оси). Реально не больше ±0.01; большие значения физически невозможны.");

        addHeader(box, "Поворот камеры к IMU (кватернион w, x, y, z)");
        final EditText qw = addField(box, "qw", "0.0015",
                "Компонента w кватерниона. Для этого крепления ≈ 0.0015.");
        final EditText qx = addField(box, "qx", "-0.7059",
                "Компонента x. Для этого крепления ≈ −0.706.");
        final EditText qy = addField(box, "qy", "0.7083",
                "Компонента y. Для этого крепления ≈ 0.708.");
        final EditText qz = addField(box, "qz", "-0.0011",
                "Компонента z. Для этого крепления ≈ −0.001.");

        addHeader(box, "IMU");
        final EditText gsx = addField(box, "gyro scale x", "1.0286",
                "Масштаб гироскопа по X. ARCore онлайн оценивает ≈1.03.");
        final EditText gsy = addField(box, "gyro scale y", "1.0151",
                "Масштаб гироскопа по Y. ARCore оценивает ≈1.015.");
        final EditText gsz = addField(box, "gyro scale z", "1.0020",
                "Масштаб гироскопа по Z. ARCore оценивает ≈1.002.");
        final EditText asx = addField(box, "accel scale x", "1",
                "Масштаб акселерометра по X. Норма ≈ 1.0.");
        final EditText asy = addField(box, "accel scale y", "1",
                "Масштаб акселерометра по Y. Норма ≈ 1.0.");
        final EditText asz = addField(box, "accel scale z", "1",
                "Масштаб акселерометра по Z. Норма ≈ 1.0.");
        final EditText gn = addField(box, "gyro noise", "0.0005309",
                "Шум гироскопа. Меньше — больше доверия IMU; больше — больше доверия камере. "
                        + "Generic-значение 0.0005309.");
        final EditText an = addField(box, "accel noise", "0.0048836",
                "Шум акселерометра. Generic-значение 0.0048836.");
        final EditText gbx = addField(box, "gyro bias x", "0.0046",
                "Начальное смещение гироскопа по X, рад/с. Измерено на неподвижном участке.");
        final EditText gby = addField(box, "gyro bias y", "-0.0022",
                "Начальное смещение гироскопа по Y, рад/с.");
        final EditText gbz = addField(box, "gyro bias z", "0.0015",
                "Начальное смещение гироскопа по Z, рад/с.");

        addHeader(box, "Глубина и плоскости");
        final EditText depth = addField(box, "depth mode (1/2)", "2",
                "Depth API: 1 — выключен, 2 — включён. Плоскости используют ML-глубину.");

        Button best = new Button(this);
        best.setText("Пресет: лучший (viotune + pzero)");
        best.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fx.setText("464.56"); fy.setText("464.56"); cx.setText("320"); cy.setText("240");
                k0.setText("0.001"); k1.setText("0"); k2.setText("0");
                rs.setText("26763333"); tsa.setText("-20000000"); tb.setText("2"); rss.setText("1");
                px.setText("0"); py.setText("0"); pz.setText("0");
                qw.setText("0.0015"); qx.setText("-0.7059"); qy.setText("0.7083"); qz.setText("-0.0011");
                gsx.setText("1.0286"); gsy.setText("1.0151"); gsz.setText("1.0020");
                asx.setText("1"); asy.setText("1"); asz.setText("1");
                gn.setText("0.0005309"); an.setText("0.0048836");
                gbx.setText("0.0046"); gby.setText("-0.0022"); gbz.setText("0.0015");
                depth.setText("2");
            }
        });
        box.addView(best, 1);

        Button chopin = new Button(this);
        chopin.setText("Пресет: chopin (эталон)");
        chopin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rs.setText("31826666"); tsa.setText("3733333"); tb.setText("1"); rss.setText("1");
                px.setText("0.0201"); py.setText("0.0054"); pz.setText("-0.0021");
                gsx.setText("1"); gsy.setText("1"); gsz.setText("1");
                gn.setText("0.000101"); an.setText("0.002942");
                gbx.setText("0"); gby.setText("0"); gbz.setText("0");
            }
        });
        box.addView(chopin, 2);

        Button zero = new Button(this);
        zero.setText("Пресет: без сдвига времени (tsa=0)");
        zero.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tsa.setText("0"); tb.setText("2");
            }
        });
        box.addView(zero, 3);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("Настройка профиля ARCore")
                .setView(scroll)
                .setPositiveButton("Применить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String q = "fx=" + val(fx) + "&fy=" + val(fy)
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
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void addHeader(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.rgb(0, 90, 160));
        tv.setTextSize(16f);
        tv.setPadding(0, 24, 0, 4);
        parent.addView(tv);
    }

    private EditText addField(LinearLayout parent, String label, String value, String description) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.BLACK);
        tv.setTextSize(14f);
        tv.setPadding(0, 16, 0, 0);
        EditText et = new EditText(this);
        et.setText(value);
        et.setTextColor(Color.BLACK);
        et.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        TextView desc = new TextView(this);
        desc.setText(description);
        desc.setTextColor(Color.DKGRAY);
        desc.setTextSize(12f);
        desc.setPadding(0, 0, 0, 8);
        parent.addView(tv);
        parent.addView(et);
        parent.addView(desc);
        return et;
    }

    private static String val(EditText e) {
        return e.getText().toString().trim().replace(',', '.');
    }

    private void applyCustom(String query) {
        String base = serverBase();
        if (base.isEmpty()) {
            Toast.makeText(this, "Set the profile server first", Toast.LENGTH_LONG).show();
            editServer();
            return;
        }
        String url = base + "custom/" + nextVersion() + ".zip?" + query;
        triggerProfile(url, "custom", "Custom tune");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume");
        try {
            ArCoreApk.Availability a = ArCoreApk.getInstance().checkAvailability(this);
            Log.i(TAG, "availability=" + a);
            if (a == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED
                    || a == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD) {
                ArCoreApk.getInstance().requestInstall(this, !installRequested);
                installRequested = true;
                return;
            }
            if (!a.isSupported()) {
                Log.e(TAG, "ARCore not supported: " + a);
                return;
            }
            if (session == null) {
                session = new Session(this);
                config = new Config(session);
                config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
                config.setFocusMode(Config.FocusMode.AUTO);
                config.setInstantPlacementMode(Config.InstantPlacementMode.LOCAL_Y_UP);
                session.configure(config);
                Log.i(TAG, "SESSION_CREATED_OK configPlane=" + config.getPlaneFindingMode()
                        + " configInstant=" + config.getInstantPlacementMode());
            }
            session.resume();
            Log.i(TAG, "SESSION_RESUMED_OK");
        } catch (Throwable t) {
            Log.e(TAG, "onResume FAILED", t);
        }
        surfaceView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (surfaceView != null) surfaceView.onPause();
        if (session != null) session.pause();
        Log.i(TAG, "onPause");
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
        Log.i(TAG, "onSurfaceCreated");
        GLES20.glClearColor(0.02f, 0.02f, 0.05f, 1f);
        background = new BackgroundRenderer();
        background.createOnGlThread();
        planeRenderer = new PlaneRenderer();
        planeRenderer.createOnGlThread();
        objectRenderer = new ObjectRenderer();
        objectRenderer.createOnGlThread();
        if (session != null) {
            session.setCameraTextureName(background.getTextureId());
            Log.i(TAG, "setCameraTextureName=" + background.getTextureId());
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        Log.i(TAG, "onSurfaceChanged " + width + "x" + height);
        GLES20.glViewport(0, 0, width, height);
        if (session != null) {
            session.setDisplayGeometry(getDisplayRotation(), width, height);
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
                Log.i(TAG, "STATUS frame=" + frameCount
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
            Log.e(TAG, "frame FAILED", t);
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
