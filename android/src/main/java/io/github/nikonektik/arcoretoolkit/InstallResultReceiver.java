package io.github.nikonektik.arcoretoolkit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Receives the result of a PackageInstaller session for the optional ARCore APK. */
public class InstallResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                } catch (Throwable t) {
                    Toast.makeText(context,
                            context.getString(R.string.install_toast_open_failed, t),
                            Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, context.getString(R.string.install_toast_success),
                    Toast.LENGTH_LONG).show();
            try {
                Intent launch = new Intent(context, MainActivity.class);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(launch);
            } catch (Throwable ignored) {
            }
        } else {
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(context, context.getString(R.string.install_toast_failed,
                    message == null ? "" : ": " + message), Toast.LENGTH_LONG).show();
        }
    }
}
