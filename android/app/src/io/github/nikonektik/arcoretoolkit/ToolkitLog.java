package io.github.nikonektik.arcoretoolkit;

import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;

/**
 * Logcat plus a small in-memory ring buffer.  When a Java crash is caught, the
 * buffer is written next to the stack trace so the crash screen shows what the
 * app was doing immediately before it died.
 */
public final class ToolkitLog {
    private static final int MAX_LINES = 600;
    private static final ArrayDeque<String> RING = new ArrayDeque<>();

    private ToolkitLog() {}

    public static int i(String tag, String msg) {
        append("I", tag, msg, null);
        return Log.i(tag, msg);
    }

    public static int i(String tag, String msg, Throwable tr) {
        append("I", tag, msg, tr);
        return Log.i(tag, msg, tr);
    }

    public static int e(String tag, String msg) {
        append("E", tag, msg, null);
        return Log.e(tag, msg);
    }

    public static int e(String tag, String msg, Throwable tr) {
        append("E", tag, msg, tr);
        return Log.e(tag, msg, tr);
    }

    public static int w(String tag, String msg) {
        append("W", tag, msg, null);
        return Log.w(tag, msg);
    }

    public static int w(String tag, String msg, Throwable tr) {
        append("W", tag, msg, tr);
        return Log.w(tag, msg, tr);
    }

    public static void marker(String msg) {
        append("M", "CRASH", msg, null);
    }

    public static String snapshot() {
        synchronized (RING) {
            StringBuilder out = new StringBuilder(RING.size() * 96);
            for (String line : RING) {
                out.append(line).append('\n');
            }
            return out.toString();
        }
    }

    private static void append(String level, String tag, String msg, Throwable tr) {
        StringBuilder line = new StringBuilder(128);
        line.append('[').append(SystemClock.elapsedRealtime()).append("] ")
                .append(level).append('/').append(tag).append(": ").append(msg);
        if (tr != null) {
            line.append('\n').append(Log.getStackTraceString(tr));
        }
        synchronized (RING) {
            RING.addLast(line.toString());
            while (RING.size() > MAX_LINES) RING.removeFirst();
        }
    }
}
