package com.estradaplay.comunista;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps route-weather samples synchronized with the currently active calculated route. */
final class RoadRouteWeatherBridgeV303 {
    private static final long RETRY_GAP_MS = 20_000L;
    private final Application app;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private double savedLat = Double.NaN, savedLon = Double.NaN;
    private long lastAttemptAt;
    private boolean routeWeatherActive;

    static void install(Application app) {
        if (app == null) return;
        RoadRouteWeatherBridgeV303 bridge = new RoadRouteWeatherBridgeV303(app);
        IntentFilter f = new IntentFilter(RoadSafetyService.ACTION_STATE);
        try {
            if (Build.VERSION.SDK_INT >= 33) InternalBroadcasts.register(app, bridge.receiver, f);
            else InternalBroadcasts.register(app, bridge.receiver, f);
        } catch (Throwable ignored) {}
    }

    private RoadRouteWeatherBridgeV303(Application app) { this.app = app; }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            DestinationStore.Destination d = DestinationStore.read(app);
            if (d == null) {
                if (routeWeatherActive) {
                    routeWeatherActive = false;
                    savedLat = Double.NaN; savedLon = Double.NaN;
                    RoadWeatherMonitor.clearActiveRoute(app);
                }
                return;
            }
            if (sameSaved(d)) return;
            double lat = intent.getDoubleExtra("lat", Double.NaN);
            double lon = intent.getDoubleExtra("lon", Double.NaN);
            if (!Double.isFinite(lat) || !Double.isFinite(lon)) return;
            long now = System.currentTimeMillis();
            if (now - lastAttemptAt < RETRY_GAP_MS || !busy.compareAndSet(false, true)) return;
            lastAttemptAt = now;
            io.execute(() -> {
                try {
                    RouteEngine.Route route = RouteOfflineCache.load(app, lat, lon, d.lat, d.lon);
                    if (route == null) return;
                    RoadWeatherMonitor.saveActiveRoute(app, route, d.label);
                    savedLat = d.lat; savedLon = d.lon;
                    routeWeatherActive = true;
                    if (DriveSettings.autoRain(app) && !DriveSettings.offlineTestMode(app)) {
                        RoadWeatherMonitor.refreshRoute(app);
                    }
                } catch (Throwable ignored) {
                } finally {
                    busy.set(false);
                }
            });
        }
    };

    private boolean sameSaved(DestinationStore.Destination d) {
        return routeWeatherActive && d != null && Double.isFinite(savedLat) && Double.isFinite(savedLon)
                && RouteEngine.distanceM(d.lat, d.lon, savedLat, savedLon) <= 300.0;
    }
}