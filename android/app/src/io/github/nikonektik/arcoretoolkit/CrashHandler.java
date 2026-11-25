package io.github.nikonektik.arcoretoolkit;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/** Writes a crash report and opens the crash screen in a separate process. */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {
    private static final String DIR = "crash";
    private static final String LAST = "last_crash.txt";
    private static volatile boolean handling;

    private final Context app;

    public CrashHandler(Context app) {
        this.app = app.getApplicationContext();
    }

    @Override
    public void uncaughtException(Thread thread, Throwable error) {
        if (handling) {
            Process.killProcess(Process.myPid());
            System.exit(10);
            return;
        }
        handling = true;

        String report = "";
        File last = null;
        try {
            ToolkitLog.marker("UNCAUGHT on " + thread.getName() + ": " + error);
            report = buildReport(thread, error);
            File dir = new File(app.getFilesDir(), DIR);
            if (!dir.exists()) dir.mkdirs();
            last = new File(dir, LAST);
            write(last, report);
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date());
            write(new File(dir, "crash-" + stamp + ".txt"), report);
            prune(dir);
        } catch (Throwable t) {
            Log.e("CRASH", "cannot write crash report", t);
        }

        try {
            Intent intent = new Intent(app, CrashActivity.class);
            if (last != null) intent.putExtra(CrashActivity.EXTRA_PATH, last.getAbsolutePath());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            app.startActivity(intent);
        } catch (Throwable t) {
            Log.e("CRASH", "cannot open crash screen", t);
        }

        // Give the separate :crash process time to start the activity, then let
        // Android restart the app cleanly on the next launch.
        new Thread(() -> {
            try {
                Thread.sleep(900);
            } catch (InterruptedException ignored) {
            }
            Process.killProcess(Process.myPid());
            System.exit(10);
        }, "crash-exit").start();
    }

    private String buildReport(Thread thread, Throwable error) {
        StringBuilder out = new StringBuilder(16384);
        out.append("ARCore Toolkit crash report\n");
        out.append("time: ").append(new Date()).append('\n');
        out.append("process: ").append(Application.getProcessName()).append('\n');
        out.append("thread: ").append(thread.getName())
                .append(" (id ").append(thread.getId()).append(")\n");
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            out.append("version: ").append(info.versionName)
                    .append(" (").append(info.getLongVersionCode()).append(")\n");
        } catch (Throwable ignored) {
        }
        out.append("device: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append(" / ").append(Build.DEVICE).append('\n');
        out.append("android: ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        if (Build.SUPPORTED_ABIS.length > 0) {
            out.append("abi: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
        }
        out.append("\n=== exception ===\n");
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        error.printStackTrace(pw);
        pw.flush();
        out.append(sw.toString()).append('\n');

        out.append("\n=== app log ===\n");
        out.append(ToolkitLog.snapshot());

        out.append("\n=== threads ===\n");
        try {
            for (Map.Entry<Thread, StackTraceElement[]> entry
                    : Thread.getAllStackTraces().entrySet()) {
                Thread t = entry.getKey();
                out.append('"').append(t.getName()).append("\" state=")
                        .append(t.getState()).append('\n');
                for (StackTraceElement element : entry.getValue()) {
                    out.append("    at ").append(element).append('\n');
                }
            }
        } catch (Throwable ignored) {
        }
        return out.toString();
    }

    private static void write(File file, String text) throws Exception {
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8")) {
            writer.write(text);
        }
    }

    private static void prune(File dir) {
        try {
            File[] files = dir.listFiles((d, name) -> name.startsWith("crash-") && name.endsWith(".txt"));
            if (files == null || files.length <= 5) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            for (int i = 0; i < files.length - 5; i++) {
                //noinspection ResultOfMethodCallIgnored
                files[i].delete();
            }
        } catch (Throwable ignored) {
        }
    }
}
