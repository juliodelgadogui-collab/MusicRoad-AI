package com.estradaplay.patriota;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** COMBOIO_AVANCADO_V230: keeps group presence alive from RoadSafetyService GPS state. */
final class ConvoyLiveBridge extends BroadcastReceiver {
    static final String ACTION_STATE = "com.estradaplay.patriota.CONVOY_LIVE_STATE";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean syncing = new AtomicBoolean(false);
    private volatile long lastAttemptAt;

    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !RoadSafetyService.ACTION_STATE.equals(intent.getAction())) return;
        Context app = context.getApplicationContext();
        ConvoyStore store = new ConvoyStore(app);
        if (store.code().isEmpty() || DriveSettings.offlineTestMode(app)) return;

        double lat = intent.getDoubleExtra("lat", Double.NaN);
        double lon = intent.getDoubleExtra("lon", Double.NaN);
        double speed = intent.getDoubleExtra("speed_kmh", 0.0);
        double heading = intent.hasExtra("heading") ? intent.getFloatExtra("heading", Float.NaN) : Double.NaN;
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) return;

        long now = System.currentTimeMillis();
        if (!ConvoySyncPolicy.shouldSync(now, lastAttemptAt, speed) || !syncing.compareAndSet(false, true)) return;
        lastAttemptAt = now;
        PendingResult pending = goAsync();
        io.execute(() -> {
            try {
                JSONObject state = store.ping(lat, lon, speed, heading);
                Intent update = new Intent(ACTION_STATE).setPackage(app.getPackageName());
                update.putExtra("ok", state.optBoolean("ok", false));
                update.putExtra("state_json", state.toString());
                if (!state.optBoolean("ok", false)) update.putExtra("error", state.optString("error", ""));
                app.sendBroadcast(update);
            } catch (Throwable e) {
                Intent update = new Intent(ACTION_STATE).setPackage(app.getPackageName());
                update.putExtra("ok", false);
                update.putExtra("error", "Sem conexão com o comboio");
                app.sendBroadcast(update);
            } finally {
                syncing.set(false);
                try { pending.finish(); } catch (Throwable ignored) {}
            }
        });
    }

    void shutdown(Context context) {
        try { if (context != null) context.unregisterReceiver(this); } catch (Throwable ignored) {}
        io.shutdownNow();
    }
}
