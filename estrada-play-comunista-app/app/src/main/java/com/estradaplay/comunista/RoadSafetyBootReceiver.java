package com.estradaplay.comunista;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONObject;

/** Restarts road protection after device reboot/app replacement when a signed-in user exists. */
public final class RoadSafetyBootReceiver extends BroadcastReceiver {
    private static final String UI_PREFS = "estradaplay_ui_v1";
    private static final String KEY_ACCOUNT = "account";

    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || !eligible(context)) return;
        try {
            Intent service = new Intent(context, RoadSafetyService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
            else context.startService(service);
        } catch (Throwable ignored) {
            // Android 14+ can defer location FGS creation until the app is foreground again.
            // RoadMapActivity will start it immediately on the next user-visible session.
        }
    }

    private static boolean eligible(Context context) {
        try {
            boolean location = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (!location) return false;
            SharedPreferences p = context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE);
            JSONObject account = new JSONObject(p.getString(KEY_ACCOUNT, "{}"));
            return account.optBoolean("authenticated", false) || account.optJSONObject("user") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }
}