package io.github.nikonektik.arcoretoolkit;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

public class ToolkitApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        String process = Application.getProcessName();
        if (process != null && process.endsWith(":crash")) {
            // The crash screen has its own process; do not install a handler
            // that could try to open the crash screen recursively.
            return;
        }
        // Material You dynamic color on Android 12+.
        DynamicColors.applyToActivitiesIfAvailable(this);
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(this));
    }
}
