package io.github.nikonektik.arcoretoolkit;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.ar.core.ArCoreApk;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Optional ARCore installer for devices without Play Services for AR.
 *
 * <p>The app can either open the Play Store or download a user-supplied APK.
 * Downloads are accepted only over HTTPS, must be the real ARCore package and
 * must carry Google's signing certificate; anything else is deleted before it
 * can be installed.
 */
public final class ArCoreInstaller {
    private static final String TAG = "ARCORE_INSTALL";
    private static final String PREFS = "arcoretoolkit";
    private static final String PREF_APK_URL = "arcore_apk_url";
    private static final String AR_CORE_PACKAGE = "com.google.ar.core";
    private static final String AR_CORE_CERT_SHA256 =
            "6e913f65ea53321ead6932ff777e1b7f94bbba7e2b0dafdd8494b100101e3797";
    private static final long MAX_APK_BYTES = 500L * 1024L * 1024L;

    private static String pendingUrl;

    private ArCoreInstaller() {}

    public static String apkUrl(Activity activity, String fallback) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        String value = prefs.getString(PREF_APK_URL, "");
        if (value == null) value = "";
        value = value.trim();
        return value.isEmpty() ? fallback : value;
    }

    private static void saveApkUrl(Activity activity, String url) {
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .edit().putString(PREF_APK_URL, url.trim()).apply();
    }

    public static void showInstallDialog(final Activity activity, final String defaultUrl,
                                         String availability) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        final String currentUrl = apkUrl(activity, defaultUrl);
        String message = activity.getString(R.string.installer_message, availability, currentUrl);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 24);

        TextView info = new TextView(activity);
        info.setText(message);
        info.setTextSize(14f);
        box.addView(info);

        MaterialButton download = new MaterialButton(activity);
        download.setText(activity.getString(R.string.installer_btn_download));
        box.addView(download);

        MaterialButton play = new MaterialButton(activity);
        play.setText(activity.getString(R.string.installer_btn_play));
        box.addView(play);

        MaterialButton edit = new MaterialButton(activity);
        edit.setText(activity.getString(R.string.installer_btn_change_url));
        box.addView(edit);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);

        final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.installer_title))
                .setView(scroll)
                .setNegativeButton(activity.getString(R.string.common_cancel), null)
                .create();
        download.setOnClickListener(v -> {
            dialog.dismiss();
            downloadAndInstall(activity, currentUrl);
        });
        play.setOnClickListener(v -> {
            dialog.dismiss();
            openPlayStore(activity);
        });
        edit.setOnClickListener(v -> {
            dialog.dismiss();
            editUrl(activity, currentUrl);
        });
        dialog.show();
    }

    private static void editUrl(final Activity activity, String currentUrl) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(currentUrl);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.installer_url_title))
                .setMessage(activity.getString(R.string.installer_url_message))
                .setView(input)
                .setPositiveButton(activity.getString(R.string.installer_url_save), (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    saveApkUrl(activity, url);
                    downloadAndInstall(activity, url);
                })
                .setNegativeButton(activity.getString(R.string.common_cancel), null)
                .show();
    }

    public static void openPlayStore(final Activity activity) {
        try {
            ArCoreApk.InstallStatus status =
                    ArCoreApk.getInstance().requestInstall(activity, true);
            if (status == ArCoreApk.InstallStatus.INSTALL_REQUESTED
                    || status == ArCoreApk.InstallStatus.INSTALLED) {
                return;
            }
        } catch (Throwable t) {
            ToolkitLog.w(TAG, "ARCore install request failed", t);
        }
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + AR_CORE_PACKAGE)));
        } catch (Throwable t) {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id="
                                + AR_CORE_PACKAGE)));
            } catch (Throwable t2) {
                Toast.makeText(activity, activity.getString(R.string.installer_toast_no_play),
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Retry a download that was blocked by the "install unknown apps" setting. */
    public static boolean retryPending(Activity activity) {
        if (pendingUrl == null) return false;
        if (!canInstallPackages(activity)) return false;
        String url = pendingUrl;
        pendingUrl = null;
        downloadAndInstall(activity, url);
        return true;
    }

    private static boolean canInstallPackages(Activity activity) {
        if (Build.VERSION.SDK_INT < 26) return true;
        return activity.getPackageManager().canRequestPackageInstalls();
    }

    private static void requestInstallPermission(Activity activity) {
        try {
            activity.startActivity(new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            Toast.makeText(activity, activity.getString(R.string.installer_toast_allow_unknown),
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(activity, activity.getString(R.string.installer_toast_settings_failed),
                    Toast.LENGTH_LONG).show();
        }
    }

    public static void downloadAndInstall(final Activity activity, final String url) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (url == null || !url.startsWith("https://")) {
            Toast.makeText(activity, activity.getString(R.string.installer_toast_need_https),
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!canInstallPackages(activity)) {
            pendingUrl = url;
            requestInstallPermission(activity);
            return;
        }

        final ProgressDialog progress = new ProgressDialog(activity);
        progress.setMessage(activity.getString(R.string.installer_progress_downloading));
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            File part = new File(activity.getCacheDir(), "arcore.apk.part");
            File apk = new File(activity.getCacheDir(), "arcore.apk");
            Throwable failure = null;
            try {
                download(url, part, activity, progress);
                if (!verifyApk(activity, part)) {
                    throw new SecurityException(
                            "The APK is not the Google-signed com.google.ar.core package");
                }
                if (apk.exists() && !apk.delete()) {
                    throw new IllegalStateException("Cannot replace cached ARCore APK");
                }
                if (!part.renameTo(apk)) {
                    throw new IllegalStateException("Cannot move downloaded APK");
                }
                install(activity, apk);
            } catch (Throwable t) {
                failure = t;
                ToolkitLog.e(TAG, "ARCore download/install failed", t);
            } finally {
                if (!apk.exists()) part.delete();
            }
            final Throwable error = failure;
            activity.runOnUiThread(() -> {
                try { progress.dismiss(); } catch (Throwable ignored) {}
                if (error != null) {
                    Toast.makeText(activity,
                            activity.getString(R.string.installer_toast_failed,
                                    error.getMessage() == null ? String.valueOf(error) : error.getMessage()),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "arcore-download").start();
    }

    private static void download(String url, File out, Activity activity,
                                 ProgressDialog progress) throws Exception {
        URL parsed = new URL(url);
        if (!"https".equalsIgnoreCase(parsed.getProtocol())) {
            throw new IllegalArgumentException("Only HTTPS URLs are allowed");
        }
        HttpURLConnection conn = (HttpURLConnection) parsed.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "ARCoreToolkit");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }
            if (!"https".equalsIgnoreCase(conn.getURL().getProtocol())) {
                throw new SecurityException("Redirected to a non-HTTPS URL");
            }
            long total = conn.getContentLengthLong();
            if (total > MAX_APK_BYTES) {
                throw new IllegalStateException("APK is too large");
            }
            if (total > 0) {
                activity.runOnUiThread(() -> {
                    progress.setIndeterminate(false);
                    progress.setMax(100);
                });
            }
            try (InputStream in = conn.getInputStream();
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buffer = new byte[64 * 1024];
                long written = 0;
                int n;
                int lastPercent = -1;
                while ((n = in.read(buffer)) > 0) {
                    written += n;
                    if (written > MAX_APK_BYTES) {
                        throw new IllegalStateException("APK is too large");
                    }
                    os.write(buffer, 0, n);
                    if (total > 0) {
                        int percent = (int) Math.min(100, written * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            final int value = percent;
                            activity.runOnUiThread(() -> progress.setProgress(value));
                        }
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static boolean verifyApk(Activity activity, File apk) {
        try {
            PackageManager pm = activity.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            if (info == null || !AR_CORE_PACKAGE.equals(info.packageName)) {
                return false;
            }
            if (info.signingInfo == null) return false;
            Signature[] signatures = info.signingInfo.getApkContentsSigners();
            if (signatures == null || signatures.length == 0) return false;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(signatures[0].toByteArray());
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format(Locale.US, "%02x", b & 0xff));
            return AR_CORE_CERT_SHA256.equalsIgnoreCase(hex.toString());
        } catch (Throwable t) {
            ToolkitLog.e(TAG, "cannot verify APK", t);
            return false;
        }
    }

    private static void install(Activity activity, File apk) throws Exception {
        PackageManager pm = activity.getPackageManager();
        PackageInstaller installer = pm.getPackageInstaller();
        if (installer == null) throw new IllegalStateException("PackageInstaller unavailable");

        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(AR_CORE_PACKAGE);
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try {
            try (InputStream in = new java.io.FileInputStream(apk);
                 OutputStream out = session.openWrite("arcore.apk", 0, apk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                session.fsync(out);
            }
            Intent result = new Intent(activity, InstallResultReceiver.class);
            result.putExtra("session", sessionId);
            PendingIntent pending = PendingIntent.getBroadcast(activity, sessionId, result,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
            session.commit(pending.getIntentSender());
        } finally {
            try { session.close(); } catch (Throwable ignored) {}
        }
    }
}
