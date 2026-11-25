package io.github.nikonektik.arcoretoolkit;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 * Crash screen that runs in the :crash process, so it survives the death of the
 * main process and can show the saved log instead of the app just closing.
 */
public class CrashActivity extends Activity {
    public static final String EXTRA_PATH = "crash_path";

    private String report = "";
    private String path = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String extra = getIntent().getStringExtra(EXTRA_PATH);
        File file = extra != null ? new File(extra)
                : new File(new File(getFilesDir(), "crash"), "last_crash.txt");
        path = file.getAbsolutePath();
        report = read(file);
        if (report.isEmpty()) {
            report = "No crash report was written.\n\nExpected file:\n" + path;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101010);
        root.setPadding(32, 48, 32, 32);

        TextView title = new TextView(this);
        title.setText("ARCore Toolkit crashed");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22f);
        title.setGravity(Gravity.START);
        root.addView(title);

        TextView where = new TextView(this);
        where.setText("Saved log: " + path);
        where.setTextColor(0xFFB0B0B0);
        where.setTextSize(11f);
        where.setTextIsSelectable(true);
        root.addView(where);

        TextView log = new TextView(this);
        log.setText(report);
        log.setTextColor(0xFFE0E0E0);
        log.setTextSize(10f);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setPadding(0, 16, 0, 16);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(log, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = button("Copy");
        Button share = button("Share");
        Button restart = button("Restart");
        Button close = button("Close");
        buttons.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
        buttons.addView(share, new LinearLayout.LayoutParams(0, -2, 1f));
        buttons.addView(restart, new LinearLayout.LayoutParams(0, -2, 1f));
        buttons.addView(close, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(buttons);

        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("ARCore Toolkit crash", report));
                Toast.makeText(this, "Crash log copied", Toast.LENGTH_SHORT).show();
            }
        });
        share.setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "ARCore Toolkit crash report");
            send.putExtra(Intent.EXTRA_TEXT, report);
            try {
                startActivity(Intent.createChooser(send, "Share crash log"));
            } catch (Throwable t) {
                Toast.makeText(this, "Cannot share: " + t, Toast.LENGTH_LONG).show();
            }
        });
        restart.setOnClickListener(v -> {
            try {
                Intent launch = new Intent(this, MainActivity.class);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(launch);
            } catch (Throwable t) {
                Toast.makeText(this, "Cannot restart: " + t, Toast.LENGTH_LONG).show();
            }
            finish();
        });
        close.setOnClickListener(v -> finish());

        setContentView(root);
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12f);
        return button;
    }

    private static String read(File file) {
        if (file == null || !file.isFile()) return "";
        StringBuilder out = new StringBuilder(16384);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        } catch (Throwable t) {
            out.append("Cannot read crash log: ").append(t).append('\n');
        }
        return out.toString();
    }
}
