package com.estradaplay.patriota;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CONTEXTO_VIVO_V220
 *
 * Keeps the authenticated Server 7.0 context warm while RoadSafetyService is alive,
 * even when RoadMapActivity is not visible. The local road-safety stream remains
 * authoritative; this component only adds online context and never blocks GPS alerts.
 */
public final class RouteContextV7BackgroundReceiver extends BroadcastReceiver {
    public static final String ACTION_CONTEXT = "com.estradaplay.patriota.ROUTE_CONTEXT_V7_STATE";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean LOADING = new AtomicBoolean(false);
    private static volatile long lastAttemptAt;

    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !RoadSafetyService.ACTION_STATE.equals(intent.getAction())) return;
        if (DriveSettings.offlineTestMode(context)) return;

        final double lat = intent.getDoubleExtra("lat", Double.NaN);
        final double lon = intent.getDoubleExtra("lon", Double.NaN);
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) return;

        final double headingRaw = intent.hasExtra("heading")
                ? intent.getFloatExtra("heading", -1f) : Double.NaN;
        final double heading = headingRaw >= 0 ? headingRaw : Double.NaN;
        final double speed = Math.max(0.0, intent.getDoubleExtra("speed_kmh", 0.0));
        int roadLimit = intent.getIntExtra("road_limit_kmh", 0);
        if (roadLimit <= 0) roadLimit = intent.getIntExtra("limit_kmh", 0);
        final int limit = Math.max(0, roadLimit);
        final String road = clean(intent.getStringExtra("road"));
        final boolean hasDestination = DestinationStore.read(context) != null;

        long now = System.currentTimeMillis();
        if (!RouteContextV7BackgroundPolicy.shouldRefresh(now, lastAttemptAt, speed, hasDestination)) return;
        if (!LOADING.compareAndSet(false, true)) return;
        lastAttemptAt = now;

        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                RouteContextV7Client.Snapshot snapshot = RouteContextV7Client.fetch(
                        app, lat, lon, heading, speed, limit, road, null);
                RouteContextV7Cache.save(app, snapshot);
                DriveSettings.setRainAutoDetected(app, snapshot.rainSoon);
                app.sendBroadcast(toIntent(app, snapshot));
            } catch (Throwable ignored) {
                // The entire road-safety stack remains operational offline.
            } finally {
                LOADING.set(false);
                try { pending.finish(); } catch (Throwable ignored) {}
            }
        });
    }

    private static Intent toIntent(Context context, RouteContextV7Client.Snapshot s) {
        Intent out = new Intent(ACTION_CONTEXT).setPackage(context.getPackageName());
        out.putExtra("received_at", s.receivedAt);
        out.putExtra("version", s.version);
        out.putExtra("weather", s.weatherText);
        out.putExtra("rain_soon", s.rainSoon);
        out.putExtra("attention_kind", s.attentionKind);
        out.putExtra("attention_title", s.attentionTitle);
        out.putExtra("attention_detail", s.attentionDetail);
        out.putExtra("attention_ahead_m", s.attentionAheadM);
        out.putExtra("traffic", s.trafficText);
        out.putExtra("fuel", s.fuelText);
        out.putExtra("traffic_opt_in", s.trafficOptIn);
        out.putExtra("traffic_stored", s.trafficStored);
        out.putExtra("hazard_count", s.hazardCount);
        out.putExtra("live_count", s.liveCount);
        out.putExtra("traffic_count", s.trafficCount);
        return out;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
