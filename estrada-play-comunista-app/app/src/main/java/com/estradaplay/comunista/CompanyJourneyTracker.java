package com.estradaplay.comunista;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reuses RoadSafetyService location broadcasts to account company mileage without a second GPS listener. */
final class CompanyJourneyTracker {
    private static final Object LOCK = new Object();
    private static CompanyJourneyTracker instance;

    private final Context app;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean sending = new AtomicBoolean(false);
    private long lastSentAt;
    private double lastSentLat = Double.NaN;
    private double lastSentLon = Double.NaN;

    private final BroadcastReceiver roadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !CompanyAccount.isDriverContext(app) || CompanyAccount.activeVehicleId(app) <= 0) return;
            double lat = intent.getDoubleExtra("lat", Double.NaN);
            double lon = intent.getDoubleExtra("lon", Double.NaN);
            double speed = intent.getDoubleExtra("speed_kmh", 0.0);
            double heading = intent.getDoubleExtra("heading", -1.0);
            if (!Double.isFinite(lat) || !Double.isFinite(lon)) return;

            long now = System.currentTimeMillis();
            double moved = distanceM(lastSentLat, lastSentLon, lat, lon);
            if (now - lastSentAt < 10_000L && (!Double.isFinite(moved) || moved < 35.0)) return;
            if (!sending.compareAndSet(false, true)) return;

            lastSentAt = now;
            lastSentLat = lat;
            lastSentLon = lon;
            io.execute(() -> {
                try { new CompanyApi(app).journeyPing(lat, lon, speed, heading); }
                catch (Throwable ignored) {}
                finally { sending.set(false); }
            });
        }
    };

    private CompanyJourneyTracker(Context context) {
        app = context.getApplicationContext();
        IntentFilter filter = new IntentFilter(RoadSafetyService.ACTION_STATE);
        InternalBroadcasts.register(app, roadReceiver, filter);
    }

    static void install(Context context) {
        if (context == null || instance != null) return;
        synchronized (LOCK) {
            if (instance == null) {
                try { instance = new CompanyJourneyTracker(context); }
                catch (Throwable ignored) { instance = null; }
            }
        }
    }

    private static double distanceM(double aLat, double aLon, double bLat, double bLon) {
        if (!Double.isFinite(aLat) || !Double.isFinite(aLon) || !Double.isFinite(bLat) || !Double.isFinite(bLon)) return Double.NaN;
        double r = 6371000.0;
        double p1 = Math.toRadians(aLat), p2 = Math.toRadians(bLat);
        double dp = Math.toRadians(bLat - aLat), dl = Math.toRadians(bLon - aLon);
        double x = Math.sin(dp / 2.0) * Math.sin(dp / 2.0)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2.0) * Math.sin(dl / 2.0);
        return 2.0 * r * Math.atan2(Math.sqrt(x), Math.sqrt(Math.max(0.0, 1.0 - x)));
    }
}
