package com.estradaplay.comunista;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/** Centralized Android 13+ notification permission guard. */
final class NotificationPermissionCompat {
    // NOTIFICATION_PERMISSION_GUARD_V300
    private NotificationPermissionCompat() {}

    static boolean canPost(Context context) {
        if (context == null) return false;
        return Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission") // Guarded above and SecurityException is handled for revocation races.
    static boolean notify(Context context, int id, Notification notification) {
        if (context == null || notification == null || !canPost(context)) return false;
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            nm.notify(id, notification);
            return true;
        } catch (SecurityException ignored) {
            return false;
        }
    }
}
