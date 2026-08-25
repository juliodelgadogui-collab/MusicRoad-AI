package com.estradaplay.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RoadSafetyService extends Service {
    static final String ACTION_STATE = "com.estradaplay.app.ROAD_SAFETY_STATE";
    private static final String CHANNEL = "estradaplay_road_safety";
    private static final int NOTIFICATION_ID = 4110;
    private static final long ALERT_COOLDOWN_MS = 8L * 60L * 1000L;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean fetching = new AtomicBoolean(false);
    private final Map<String, Long> alertedAt = new HashMap<>();

    private LocationManager locationManager;
    private RoadPackStore packs;
    private ApiClient api;
    private TextToSpeech tts;
    private boolean ttsReady;
    private Location previous;
    private float lastHeading = Float.NaN;
    private long lastNotificationAt;

    @Override public void onCreate() {
        super.onCreate();
        packs = new RoadPackStore(this);
        api = new ApiClient(this);
        locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);
        createChannel();
        startForeground(NOTIFICATION_ID, notification("Proteção na estrada ativa", "GPS aguardando localização", false));
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                try { tts.setLanguage(new Locale("pt", "BR")); } catch (Throwable ignored) {}
            }
        });
        startLocation();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startLocation();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void startLocation() {
        if (locationManager == null) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("Proteção pausada", "Permita a localização para detectar a estrada", true);
            stopSelf();
            return;
        }
        try { locationManager.removeUpdates(listener); } catch (Throwable ignored) {}
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f, listener);
        } catch (Throwable ignored) {}
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 12f, listener);
        } catch (Throwable ignored) {}
    }

    private final LocationListener listener = new LocationListener() {
        @Override public void onLocationChanged(Location location) { handleLocation(location); }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };

    private void handleLocation(Location loc) {
        if (loc == null) return;
        if (loc.hasAccuracy() && loc.getAccuracy() > 120f && previous != null) return;

        float heading = heading(loc);
        double speedKmh = speedKmh(loc);
        previous = new Location(loc);

        ensureCoverage(loc.getLatitude(), loc.getLongitude());
        List<RoadHazard> nearby = packs.nearby(loc.getLatitude(), loc.getLongitude(), 1900);
        RoadHazard best = null;
        double bestForward = Double.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;

        if (Float.isFinite(heading) && speedKmh >= 7.0) {
            for (RoadHazard h : nearby) {
                Match m = match(loc.getLatitude(), loc.getLongitude(), heading, speedKmh, h);
                if (!m.valid) continue;
                if (m.forwardM < bestForward) {
                    best = h;
                    bestForward = m.forwardM;
                    bestDistance = m.distanceM;
                }
            }
        }

        if (best != null && shouldAlert(best)) {
            rememberAlert(best);
            String spoken = voice(best, bestForward);
            speak(spoken);
            String title = best.label() + " à frente";
            String detail = distanceText(bestForward);
            if (best.speed > 0 && "RADAR".equals(best.type)) detail += " · " + best.speed + " km/h";
            if (!best.road.isEmpty()) detail += " · " + best.road;
            updateNotification(title, detail, true);
            broadcast(loc, speedKmh, best, bestDistance, detail);
        } else {
            long now = System.currentTimeMillis();
            if (now - lastNotificationAt > 7000L) {
                String state = packs.hasAnyCoverage(loc.getLatitude(), loc.getLongitude())
                        ? packs.status(loc.getLatitude(), loc.getLongitude())
                        : (fetching.get() ? "Baixando alertas desta área…" : "Aguardando pacote offline desta área");
                updateNotification("Proteção na estrada ativa", state, false);
                broadcast(loc, speedKmh, null, 0, state);
            }
        }
    }

    private void ensureCoverage(double lat, double lon) {
        if (packs.hasFreshCoreCoverage(lat, lon) || fetching.get()) return;
        if (api.cookie() == null || api.cookie().trim().isEmpty()) return;
        if (!fetching.compareAndSet(false, true)) return;
        updateNotification("Preparando alertas offline", "Baixando radares, semáforos e obstáculos da região", false);
        io.execute(() -> {
            try { packs.fetchCoverage(api, lat, lon); }
            finally {
                fetching.set(false);
                String text = packs.hasAnyCoverage(lat, lon)
                        ? "Pacote salvo no aparelho · " + packs.hazardCount() + " pontos"
                        : "Não consegui atualizar agora; usando o que já estiver offline";
                updateNotification("Proteção na estrada ativa", text, false);
            }
        });
    }

    private Match match(double lat, double lon, float heading, double speedKmh, RoadHazard h) {
        double dLat = h.lat - lat;
        double dLon = h.lon - lon;
        double north = dLat * 110540.0;
        double east = dLon * 111320.0 * Math.max(0.25, Math.cos(Math.toRadians(lat)));
        double rad = Math.toRadians(heading);
        double forward = east * Math.sin(rad) + north * Math.cos(rad);
        double lateral = Math.abs(east * Math.cos(rad) - north * Math.sin(rad));
        double distance = Math.hypot(east, north);
        if (forward <= 12.0) return Match.no();

        double maxDistance;
        double maxLateral;
        double minSpeed;
        switch (h.type) {
            case "SEMAFORO":
                maxDistance = speedKmh >= 55 ? 300 : 220; maxLateral = 55; minSpeed = 18; break;
            case "QUEBRA_MOLAS":
                maxDistance = speedKmh >= 55 ? 360 : 260; maxLateral = 50; minSpeed = 10; break;
            case "PEDAGIO":
                maxDistance = speedKmh >= 80 ? 1100 : 800; maxLateral = 150; minSpeed = 10; break;
            case "PASSAGEM_NIVEL":
                maxDistance = speedKmh >= 70 ? 700 : 500; maxLateral = 85; minSpeed = 10; break;
            default:
                maxDistance = speedKmh >= 95 ? 1250 : (speedKmh >= 70 ? 1000 : 700);
                maxLateral = speedKmh >= 70 ? 115 : 85;
                minSpeed = 10;
                break;
        }
        if (speedKmh < minSpeed || forward > maxDistance || lateral > maxLateral || distance > maxDistance * 1.18) return Match.no();
        if (Double.isFinite(h.heading) && angleDiff(heading, h.heading) > 75.0) return Match.no();
        return new Match(true, forward, lateral, distance);
    }

    private boolean shouldAlert(RoadHazard h) {
        Long when = alertedAt.get(h.id);
        return when == null || System.currentTimeMillis() - when > ALERT_COOLDOWN_MS;
    }

    private void rememberAlert(RoadHazard h) {
        long now = System.currentTimeMillis();
        alertedAt.put(h.id, now);
        if (alertedAt.size() > 300) alertedAt.entrySet().removeIf(e -> now - e.getValue() > ALERT_COOLDOWN_MS);
    }

    private float heading(Location loc) {
        if (loc.hasBearing() && (!loc.hasSpeed() || loc.getSpeed() > 1.5f)) {
            lastHeading = normalize(loc.getBearing());
            return lastHeading;
        }
        if (previous != null) {
            float moved = previous.distanceTo(loc);
            long dt = Math.max(1L, loc.getTime() - previous.getTime());
            if (moved >= 8f && dt <= 15000L) {
                lastHeading = normalize(previous.bearingTo(loc));
                return lastHeading;
            }
        }
        return lastHeading;
    }

    private double speedKmh(Location loc) {
        if (loc.hasSpeed()) return Math.max(0.0, loc.getSpeed() * 3.6);
        if (previous == null) return 0.0;
        long dt = loc.getTime() - previous.getTime();
        if (dt <= 0 || dt > 15000L) return 0.0;
        return previous.distanceTo(loc) / (dt / 1000.0) * 3.6;
    }

    private void speak(String text) {
        if (!ttsReady || tts == null || text == null || text.isEmpty()) return;
        try { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "road-alert"); } catch (Throwable ignored) {}
    }

    private String voice(RoadHazard h, double forward) {
        StringBuilder s = new StringBuilder();
        switch (h.type) {
            case "SEMAFORO": s.append("Semáforo à frente"); break;
            case "QUEBRA_MOLAS": s.append("Quebra-molas à frente"); break;
            case "PEDAGIO": s.append("Pedágio à frente"); break;
            case "PASSAGEM_NIVEL": s.append("Passagem de nível à frente"); break;
            default: s.append("Radar à frente"); break;
        }
        s.append(", a ").append(distanceSpeech(forward));
        if ("RADAR".equals(h.type) && h.speed > 0) s.append(", limite de ").append(h.speed).append(" quilômetros por hora");
        return s.toString();
    }

    private String distanceSpeech(double m) {
        if (m < 120) return Math.max(30, (int)(Math.round(m / 10.0) * 10)) + " metros";
        return Math.max(100, (int)(Math.round(m / 50.0) * 50)) + " metros";
    }

    private String distanceText(double m) {
        if (m >= 1000) return String.format(Locale.getDefault(), "%.1f km", m / 1000.0);
        return Math.max(10, (int)(Math.round(m / 10.0) * 10)) + " m";
    }

    private void broadcast(Location loc, double speedKmh, RoadHazard h, double distance, String status) {
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra("lat", loc.getLatitude());
        i.putExtra("lon", loc.getLongitude());
        i.putExtra("speed_kmh", speedKmh);
        i.putExtra("heading", Float.isFinite(lastHeading) ? lastHeading : -1f);
        i.putExtra("pack_count", packs.packCount());
        i.putExtra("hazard_count", packs.hazardCount());
        i.putExtra("status", status == null ? "" : status);
        if (h != null) {
            i.putExtra("hazard_id", h.id);
            i.putExtra("hazard_type", h.type);
            i.putExtra("hazard_label", h.label());
            i.putExtra("road", h.road);
            i.putExtra("distance_m", distance);
            i.putExtra("limit_kmh", h.speed);
        }
        sendBroadcast(i);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Alertas da estrada", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("GPS e alertas offline de radares, semáforos e obstáculos.");
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private Notification notification(String title, String text, boolean alert) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 10, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(!alert)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        return b.build();
    }

    private void updateNotification(String title, String text, boolean alert) {
        lastNotificationAt = System.currentTimeMillis();
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, notification(title, text, alert));
    }

    private static float normalize(float deg) {
        float v = deg % 360f;
        return v < 0 ? v + 360f : v;
    }

    private static double angleDiff(double a, double b) {
        double d = Math.abs(a - b) % 360.0;
        return d > 180.0 ? 360.0 - d : d;
    }

    @Override public void onDestroy() {
        try { if (locationManager != null) locationManager.removeUpdates(listener); } catch (Throwable ignored) {}
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}
        io.shutdownNow();
        super.onDestroy();
    }

    private static final class Match {
        final boolean valid;
        final double forwardM, lateralM, distanceM;
        Match(boolean valid, double forwardM, double lateralM, double distanceM) {
            this.valid=valid; this.forwardM=forwardM; this.lateralM=lateralM; this.distanceM=distanceM;
        }
        static Match no() { return new Match(false, 0, 0, 0); }
    }
}
