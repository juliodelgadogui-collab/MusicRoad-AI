package com.estradaplay.comunista;

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
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RoadSafetyService extends Service {
    static final String ACTION_STATE = "com.estradaplay.comunista.ROAD_SAFETY_STATE";
    private static final String CHANNEL = "estradaplay_road_safety";
    private static final int NOTIFICATION_ID = 4110;
    private static final long ALERT_COOLDOWN_MS = 8L * 60L * 1000L;
    static final String ACTION_PREFETCH_CORE = "com.estradaplay.comunista.PREFETCH_CORE";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService limitIo = Executors.newSingleThreadExecutor();
    private final AtomicBoolean fetching = new AtomicBoolean(false);
    private final Map<String, Long> alertedAt = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private LocationManager locationManager;
    private volatile RoadPackStore packs;
    private volatile OfflineRoadStore mapRoads;
    private final AtomicBoolean storesLoading = new AtomicBoolean(false);
    private final AtomicBoolean coreStatesPriming = new AtomicBoolean(false);
    private ApiClient api;
    private TextToSpeech tts;
    // OFFLINE_VOICE_V204: primary deterministic voice; Android TTS is fallback only.
    private EstradaPlayOfflineVoice offlineVoice;
    private CommunistCopilot copilot;
    private TripRecorder tripRecorder;
    private CollectiveRoadStore collectiveStore;
    private RoadSurfaceMonitor surfaceMonitor;
    private boolean ttsReady;
    private AudioManager audioManager;
    private AudioFocusRequest alertFocusRequest;
    private Location previous;
    private float lastHeading = Float.NaN;
    private double lastSpeedKmh;
    private long lastGpsFixWallMs;
    private long lastSpeedFixWallMs;
    private Location motionAnchor;
    private long motionAnchorWallMs;
    private boolean stationaryConfirmed;
    private long lastNotificationAt;
    // ANR_GUARD_V201: keep repetitive disk/status/coverage work out of the 1 Hz GPS hot path.
    private long lastCoverageCheckAt;
    private long lastStateRefreshAt;
    private String lastStateText = "GPS ativo · preparando proteção";
    // ROAD_LIMIT_V202: announce road limit changes, radar limits and one-shot overspeed.
    private final AtomicBoolean roadLimitResolving = new AtomicBoolean(false);
    private volatile int currentRoadLimitKmh;
    private int announcedRoadLimitKmh;
    private boolean roadOverspeedWarned;
    private long lastRoadLimitCheckAt;
    // ROAD_THOUGHTS_V142: low-priority cultural layer; safety always wins.
    private final long thoughtSessionStartedAt = System.currentTimeMillis();
    private long lastThoughtCheckAt;
    private long lastSafetyVoiceAt;
    private long continuousDrivingStartedAt,lastMovingForRestAt;
    private boolean restSuggested;
    private long lastUpcomingAt; private String upcomingCache="";

    private final Runnable restoreAudioFallback = this::restoreAudioAfterVoice;

    // STATIONARY_SPEED_V178: if the head unit stops delivering fresh GPS fixes,
    // a previous moving speed must never remain frozen on screen indefinitely.
    private final Runnable staleSpeedWatchdog = () -> {
        long age = System.currentTimeMillis() - lastSpeedFixWallMs;
        if (lastSpeedFixWallMs > 0L && age >= 4500L && lastSpeedKmh > 0.0 && previous != null) {
            lastSpeedKmh = 0.0;
            stationaryConfirmed = true;
            sendBroadcast(baseBroadcast(previous.getLatitude(), previous.getLongitude(), 0.0,
                    "GPS sem movimento recente"));
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        // ANR_ROAD_INIT_V211: heavy offline JSON parsing is deferred to IO.
        api = new ApiClient(this);
        offlineVoice = new EstradaPlayOfflineVoice(this);
        copilot = new CommunistCopilot(this);
        tripRecorder = new TripRecorder(this);
        collectiveStore = new CollectiveRoadStore(this);
        surfaceMonitor = new RoadSurfaceMonitor(this, this::handleRoadImpact);
        surfaceMonitor.start();
        locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);
        audioManager = (AudioManager)getSystemService(AUDIO_SERVICE);
        createChannel();
        startForeground(NOTIFICATION_ID, notification("Proteção na estrada ativa", "GPS aguardando localização", false));
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                try {
                    tts.setLanguage(new Locale("pt", "BR"));
                    // ESTRADAPLAY_VOICE_V203: branded automotive profile.
                    // Slightly lower pitch + calmer pace makes road warnings firm and distinct.
                    tts.setSpeechRate(0.91f);
                    tts.setPitch(0.84f);
                    selectEstradaPlayVoice();
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String utteranceId) { main.post(RoadSafetyService.this::beginVoiceDucking); }
                        @Override public void onDone(String utteranceId) { main.post(RoadSafetyService.this::restoreAudioAfterVoice); }
                        @Override public void onError(String utteranceId) { main.post(RoadSafetyService.this::restoreAudioAfterVoice); }
                        @Override public void onStop(String utteranceId, boolean interrupted) { main.post(RoadSafetyService.this::restoreAudioAfterVoice); }
                    });
                } catch (Throwable ignored) {}
            }
        });
        initializeStoresAsync();
    }

    private void initializeStoresAsync() {
        if (packs != null && mapRoads != null) {
            startLocation();
            return;
        }
        if (!storesLoading.compareAndSet(false, true)) return;
        io.execute(() -> {
            try {
                RoadPackStore loadedPacks = new RoadPackStore(getApplicationContext());
                OfflineRoadStore loadedRoads = new OfflineRoadStore(getApplicationContext());
                packs = loadedPacks;
                mapRoads = loadedRoads;
            } catch (Throwable ignored) {
            } finally {
                storesLoading.set(false);
                main.post(() -> {
                    if (packs != null && mapRoads != null) {
                        startLocation();
                        primeOfflineRadarCore();
                    } else updateNotification("Proteção na estrada", "Base offline indisponível; tentando novamente", true);
                });
            }
        });
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (packs != null && mapRoads != null) { startLocation(); primeOfflineRadarCore(); } else initializeStoresAsync();
        if(intent!=null&&ACTION_PREFETCH_CORE.equals(intent.getAction())) primeOfflineRadarCore();
        return START_STICKY;
    }

    // OFFLINE_RADAR_CORE_V141: state radar packs are prepared before route/map extras.
    // They remain fully local once downloaded and do not depend on live internet to alert.
    private void primeOfflineRadarCore() {
        RoadPackStore local = packs;
        if (local == null || local.coreStatesReady() || !coreStatesPriming.compareAndSet(false, true)) return;
        io.execute(() -> {
            try {
                ensureApiSession(false);
                boolean ok = local.prefetchCoreStates(api);
                if (!ok || !local.coreStatesReady()) {
                    ensureApiSession(true);
                    local.prefetchCoreStates(api);
                }
            } catch (Throwable ignored) {
            } finally {
                coreStatesPriming.set(false);
                String text = "Radares offline · " + local.coreStatesStatus();
                updateNotification("Proteção offline", text, false);
                Location p = previous;
                if (p != null) broadcastSynthetic(p.getLatitude(), p.getLongitude(), text);
            }
        });
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void startLocation() {
        if (locationManager == null) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("Proteção pausada", "Autorize a localização dentro do EstradaPlay", true);
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
        if (packs == null || mapRoads == null) return;
        if (loc.hasAccuracy() && loc.getAccuracy() > 120f && previous != null) return;

        long nowWall = System.currentTimeMillis();
        String provider = loc.getProvider() == null ? "" : loc.getProvider();
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            lastGpsFixWallMs = nowWall;
        } else if (LocationManager.NETWORK_PROVIDER.equals(provider) && nowWall - lastGpsFixWallMs < 4000L) {
            // A recent GPS fix is more useful than an interleaved network fix that can
            // report speed=0 and erase the vehicle speed on automotive Android ROMs.
            return;
        }

        float heading = heading(loc);
        double speedKmh = speedKmh(loc);
        if (Double.isFinite(speedKmh)) lastSpeedKmh = Math.max(0.0, speedKmh);
        lastSpeedFixWallMs = nowWall;
        main.removeCallbacks(staleSpeedWatchdog);
        main.postDelayed(staleSpeedWatchdog, 4500L);
        previous = new Location(loc);
        if (tripRecorder != null) tripRecorder.onLocation(loc, speedKmh);
        if (surfaceMonitor != null) surfaceMonitor.updateDriveState(loc, speedKmh);
        updateRestClock(loc, speedKmh);

        maybeResolveRoadLimit(loc.getLatitude(), loc.getLongitude(), heading);
        evaluateRoadLimit(speedKmh);
        ensureCoverage(loc.getLatitude(), loc.getLongitude(), heading);
        List<RoadHazard> nearby = packs.nearby(loc.getLatitude(), loc.getLongitude(), 1900);
        RoadHazard best = null;
        RoadHazard next = null;
        double bestForward = Double.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;
        double bestScore = Double.MAX_VALUE;
        double nextForward = Double.MAX_VALUE;
        double nextScore = Double.MAX_VALUE;

        if (Float.isFinite(heading) && speedKmh >= 3.0) {
            for (RoadHazard h : nearby) {
                if (!shouldAlert(h)) continue;
                Match m = match(loc.getLatitude(), loc.getLongitude(), heading, speedKmh, h);
                if (!m.valid) continue;
                double score = m.forwardM + hazardPriorityBias(h.type);
                if (score < bestScore) {
                    best = h;
                    bestForward = m.forwardM;
                    bestDistance = m.distanceM;
                    bestScore = score;
                }
            }
            if (best != null) {
                for (RoadHazard h : nearby) {
                    if (h == null || h.id.equals(best.id) || !shouldAlert(h)) continue;
                    Match m = match(loc.getLatitude(), loc.getLongitude(), heading, speedKmh, h);
                    if (!m.valid || m.forwardM < bestForward + 15) continue;
                    double score = m.forwardM + hazardPriorityBias(h.type);
                    if (score < nextScore) { next = h; nextForward = m.forwardM; nextScore = score; }
                }
            }
        }

        if (best != null) {
            rememberAlert(best);
            if (tripRecorder != null) tripRecorder.onHazard(best.type);
            if (collectiveStore != null && "RADAR".equals(best.type)) collectiveStore.addRadarConfirmation(best,loc);
            speakHazardVoice(best, bestForward);
            String title = best.label() + " à frente";
            String detail = distanceText(bestForward);
            if (best.speed > 0 && "RADAR".equals(best.type)) {
                detail += " · " + best.speed + " km/h";
                int delta = (int)Math.round(speedKmh - best.speed);
                if (delta >= 2) detail += " · reduza " + delta;
            }
            if (!best.road.isEmpty()) detail += " · " + best.road;
            updateNotification(title, detail, true);
            broadcast(loc, speedKmh, best, bestDistance, detail, next, nextForward);
        } else {
            long now = System.currentTimeMillis();
            if (now - lastStateRefreshAt >= 5000L || lastStateText == null || lastStateText.isEmpty()) {
                lastStateText = packs.hasAnyCoverage(loc.getLatitude(), loc.getLongitude())
                        ? packs.status(loc.getLatitude(), loc.getLongitude()) + " · " + mapRoads.status(loc.getLatitude(), loc.getLongitude())
                        : (fetching.get() ? "Preparando alertas e mapa offline…" : "Aguardando proteção offline desta região");
                lastStateRefreshAt = now;
            }
            String ahead=upcomingSummary(loc.getLatitude(),loc.getLongitude(),heading);
            String state = ahead.isEmpty()?lastStateText:lastStateText+"\nÀ frente · "+ahead;
            if (now - lastNotificationAt > 7000L) updateNotification("Proteção na estrada ativa", state, false);
            maybeEmitRoadThought(loc, speedKmh);
            broadcast(loc, speedKmh, null, 0, state, null, 0);
        }
    }

    private void maybeEmitRoadThought(Location loc, double speedKmh) {
        int mode = RoadThoughts.mode(this);
        if (mode == RoadThoughts.MODE_OFF || loc == null || speedKmh < 5.0) return;
        long now = System.currentTimeMillis();
        if (now - lastThoughtCheckAt < 10000L) return;
        lastThoughtCheckAt = now;
        if (now - thoughtSessionStartedAt < 4L * 60L * 1000L) return;
        if (now - lastSafetyVoiceAt < 60_000L) return;
        long previousThought = RoadThoughts.lastShownAt(this);
        if (previousThought > 0 && now - previousThought < RoadThoughts.intervalMs(this)) return;

        RoadThoughts.Entry e = RoadThoughts.next(this);
        if (e == null) return;
        RoadThoughts.markShown(this, now);
        Intent thought = baseBroadcast(loc.getLatitude(), loc.getLongitude(), speedKmh, "Proteção ativa");
        thought.putExtra("thought_author", e.author);
        thought.putExtra("thought_text", e.text);
        thought.putExtra("thought_paraphrase", true);
        sendBroadcast(thought);
        if (mode == RoadThoughts.MODE_SCREEN_VOICE && ttsReady) speak(RoadThoughts.spoken(e));
    }

    private void interruptThoughtForSafety() {
        lastSafetyVoiceAt = System.currentTimeMillis();
        try { if (tts != null) tts.stop(); } catch (Throwable ignored) {}
        restoreAudioAfterVoice();
    }

    private void maybeResolveRoadLimit(double lat, double lon, float heading) {
        long now = System.currentTimeMillis();
        if (now - lastRoadLimitCheckAt < 7000L || roadLimitResolving.get()) return;
        lastRoadLimitCheckAt = now;
        if (!roadLimitResolving.compareAndSet(false, true)) return;
        limitIo.execute(() -> {
            int limit = 0;
            try {
                if (mapRoads != null) limit = mapRoads.speedLimitAt(lat, lon, heading);
            } catch (Throwable ignored) {}
            final int resolved = limit;
            main.post(() -> applyRoadLimit(resolved));
            roadLimitResolving.set(false);
        });
    }

    private void applyRoadLimit(int limitKmh) {
        if (limitKmh < 10 || limitKmh > 180) return;
        boolean changed = currentRoadLimitKmh != limitKmh;
        currentRoadLimitKmh = limitKmh;
        if (changed) roadOverspeedWarned = false;
        if (limitKmh != announcedRoadLimitKmh && speakRoadLimitVoice(limitKmh)) {
            announcedRoadLimitKmh = limitKmh;
        }
    }

    private void evaluateRoadLimit(double speedKmh) {
        int limit = currentRoadLimitKmh;
        if (limit <= 0 || !Double.isFinite(speedKmh)) return;
        if (speedKmh <= limit) {
            roadOverspeedWarned = false;
            return;
        }
        // Small GPS tolerance prevents a 60/61 oscillation from becoming a false warning.
        if (!roadOverspeedWarned && speedKmh >= limit + 2.0 && speakOverspeedVoice(limit)) {
            roadOverspeedWarned = true;
        }
    }

    private void ensureCoverage(double lat, double lon, float heading) {
        long now = System.currentTimeMillis();
        if (now - lastCoverageCheckAt < 5000L) return;
        lastCoverageCheckAt = now;
        boolean alertNeeds = packs.needsPreparation(lat, lon, heading);
        boolean mapNeeds = mapRoads.needsPreparation(lat, lon, heading);
        if ((!alertNeeds && !mapNeeds) || fetching.get()) return;
        if (!fetching.compareAndSet(false, true)) return;
        updateNotification("Preparando viagem offline", "Alertas + mapa livre para até 250 km à frente", false);
        io.execute(() -> {
            try {
                ensureApiSession(false);
                boolean alertOk = !alertNeeds || packs.prepareTravelReserve(api, lat, lon, heading);
                boolean mapOk = !mapNeeds || mapRoads.prepare(api, lat, lon, heading);
                if (!alertOk || !mapOk) {
                    // Cookie can exist locally while PHP session already expired.
                    ensureApiSession(true);
                    if (alertNeeds && !alertOk) packs.prepareTravelReserve(api, lat, lon, heading);
                    if (mapNeeds && !mapOk) mapRoads.prepare(api, lat, lon, heading);
                }
            } finally {
                fetching.set(false);
                String text = packs.hasAnyCoverage(lat, lon)
                        ? packs.status(lat, lon) + " · " + mapRoads.status(lat, lon)
                        : "Não consegui atualizar agora; usando o que já está salvo";
                updateNotification("Proteção na estrada ativa", text, false);
                broadcastSynthetic(lat, lon, text);
            }
        });
    }

    private void ensureApiSession(boolean force) {
        if (api == null) return;
        String cookie = api.cookie();
        if (!force && cookie != null && !cookie.trim().isEmpty()) return;
        try {
            if (force) api.clearSession();
            JSONObject d = new JSONObject();
            d.put("device_token", DeviceIdentity.token(this));
            d.put("device_label", DeviceIdentity.label());
            d.put("app_version", BuildConfig.VERSION_NAME);
            ApiClient.Response response = api.post("api/native_app.php?action=device_login", d);
            if (!response.ok() || !response.json().optBoolean("ok", false)) {
                if (force) api.clearSession();
            }
        } catch (Exception ignored) {
            if (force) api.clearSession();
        }
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
        // If a bump enters the local pack very late, still warn while it is almost
        // under the car. Other point types retain the normal forward safety gate.
        if ("QUEBRA_MOLAS".equals(h.type)) {
            if (forward < -12.0) return Match.no();
        } else if (forward <= 12.0) return Match.no();

        double maxDistance;
        double maxLateral;
        double minSpeed;
        switch (h.type) {
            case "SEMAFORO":
                maxDistance = speedKmh >= 55 ? 300 : 220; maxLateral = 55; minSpeed = 18; break;
            case "QUEBRA_MOLAS":
                maxDistance = speedKmh >= 55 ? 430 : 300; maxLateral = 55; minSpeed = 3; break;
            case "CAMERA_MONITORAMENTO":
                maxDistance = speedKmh >= 80 ? 650 : 450; maxLateral = 75; minSpeed = 8; break;
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

    private double hazardPriorityBias(String type) {
        if ("QUEBRA_MOLAS".equals(type)) return -220.0;
        if ("RADAR".equals(type)) return -160.0;
        if ("PASSAGEM_NIVEL".equals(type)) return -130.0;
        if ("SEMAFORO".equals(type)) return -90.0;
        if ("CAMERA_MONITORAMENTO".equals(type)) return -35.0;
        return 0.0;
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
        // Bearing and speed are independent Android Location fields. Some head units
        // provide a good bearing while speed is present-but-zero, so never discard it.
        if (loc.hasBearing()) {
            lastHeading = normalize(loc.getBearing());
            return lastHeading;
        }
        if (previous != null) {
            float moved = previous.distanceTo(loc);
            long dt = Math.max(1L, loc.getTime() - previous.getTime());
            if (moved >= 4f && dt <= 15000L) {
                lastHeading = normalize(previous.bearingTo(loc));
                return lastHeading;
            }
        }
        return lastHeading;
    }

    private double speedKmh(Location loc) {
        long nowWall = System.currentTimeMillis();
        double sensor = Double.NaN;
        if (loc.hasSpeed()) sensor = Math.max(0.0, loc.getSpeed() * 3.6);

        double derived = Double.NaN;
        long dt = 0L;
        float moved = 0f;
        if (previous != null) {
            dt = loc.getTime() - previous.getTime();
            if (dt > 0L && dt <= 15000L) {
                moved = previous.distanceTo(loc);
                float accNow = loc.hasAccuracy() ? loc.getAccuracy() : 25f;
                float accPrev = previous.hasAccuracy() ? previous.getAccuracy() : 25f;
                float noiseGate = Math.max(4.0f, Math.min(18.0f, Math.max(accNow, accPrev) * 0.30f));
                if (moved >= noiseGate) derived = moved / (dt / 1000.0) * 3.6;
            }
        }

        // A single GPS jump is not movement. Compare the current point with an
        // anchor over several seconds. While parked, net displacement remains inside
        // the GPS accuracy cloud even when consecutive fixes jump around.
        if (motionAnchor == null) {
            motionAnchor = new Location(loc);
            motionAnchorWallMs = nowWall;
            // Fail safe: an automotive receiver can expose a stale speed on its
            // first fix. Only actual displacement is allowed to leave this state.
            stationaryConfirmed = true;
        } else {
            long anchorAge = nowWall - motionAnchorWallMs;
            if (anchorAge >= 2500L) {
                float anchorMoved = motionAnchor.distanceTo(loc);
                float accNow = loc.hasAccuracy() ? loc.getAccuracy() : 20f;
                float accAnchor = motionAnchor.hasAccuracy() ? motionAnchor.getAccuracy() : 20f;
                float stationaryRadius = Math.max(6.0f,
                        Math.min(12.0f, Math.max(accNow, accAnchor) * 0.45f));
                stationaryConfirmed = anchorMoved <= stationaryRadius;
                if (anchorAge >= 5000L || anchorMoved > stationaryRadius * 1.5f) {
                    motionAnchor = new Location(loc);
                    motionAnchorWallMs = nowWall;
                }
            }
        }

        double chosen;
        if (Double.isFinite(sensor) && sensor >= 2.0) chosen = sensor;
        else if (Double.isFinite(derived) && derived >= 2.0) chosen = derived;
        else if (Double.isFinite(sensor)) chosen = sensor;
        else if (Double.isFinite(derived)) chosen = derived;
        else chosen = 0.0;

        // Multi-sample stationary evidence wins over stale speed reported by the ROM.
        if (stationaryConfirmed) chosen = 0.0;

        // Reject impossible spikes. Only preserve one short zero sample if we have
        // actual displacement and have NOT confirmed the vehicle is stationary.
        if (chosen > 260.0) chosen = stationaryConfirmed ? 0.0 : lastSpeedKmh;
        if (!stationaryConfirmed && chosen < 1.0 && lastSpeedKmh >= 5.0 &&
                dt > 0L && dt <= 2500L && moved >= 3f) {
            chosen = lastSpeedKmh * 0.70;
        }
        if (chosen < 1.5) chosen = 0.0;
        return chosen;
    }

    private void selectEstradaPlayVoice() {
        if (tts == null || Build.VERSION.SDK_INT < 21) return;
        try {
            java.util.Set<Voice> voices = tts.getVoices();
            if (voices == null || voices.isEmpty()) return;
            ArrayList<Voice> pt = new ArrayList<>();
            for (Voice v : voices) {
                if (v == null || v.getLocale() == null) continue;
                String lang = v.getLocale().getLanguage();
                String country = v.getLocale().getCountry();
                if (!"pt".equalsIgnoreCase(lang)) continue;
                if (!country.isEmpty() && !"BR".equalsIgnoreCase(country)) continue;
                pt.add(v);
            }
            if (pt.isEmpty()) return;
            pt.sort(Comparator
                    .comparing((Voice v) -> v.isNetworkConnectionRequired())
                    .thenComparing((Voice v) -> -v.getQuality())
                    .thenComparing(Voice::getName));
            tts.setVoice(pt.get(0));
        } catch (Throwable ignored) {}
    }

    private void beginVoiceDucking() {
        duckOwnPlayer(true);
        requestVoiceFocus();
        main.removeCallbacks(restoreAudioFallback);
        main.postDelayed(restoreAudioFallback, 8000L);
    }

    private boolean speakRoadLimitVoice(int limitKmh) {
        interruptThoughtForSafety();
        if (offlineVoice != null && offlineVoice.playRoadLimit(limitKmh, this::beginVoiceDucking, this::restoreAudioAfterVoice)) return true;
        if (ttsReady && copilot != null) return speak(copilot.roadLimit(limitKmh));
        return false;
    }

    private boolean speakOverspeedVoice(int limitKmh) {
        interruptThoughtForSafety();
        if (offlineVoice != null && offlineVoice.playOverspeed(limitKmh, this::beginVoiceDucking, this::restoreAudioAfterVoice)) return true;
        if (ttsReady && copilot != null) return speak(copilot.overspeed(limitKmh));
        return false;
    }

    private void speakHazardVoice(RoadHazard h, double forwardM) {
        interruptThoughtForSafety();
        if (offlineVoice != null && h != null &&
                offlineVoice.playHazard(h.type, forwardM, h.speed, this::beginVoiceDucking, this::restoreAudioAfterVoice)) return;
        if (ttsReady && copilot != null && h != null && speak(copilot.hazard(h.type, forwardM, h.speed))) return;
        speak(voice(h, forwardM));
    }

    private boolean speak(String text) {
        if (!ttsReady || tts == null || text == null || text.trim().isEmpty()) return false;
        try {
            String id = "road-alert-" + System.currentTimeMillis();
            int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
            if (result == TextToSpeech.ERROR) { restoreAudioAfterVoice(); return false; }
            // Deliberately do not duck here. UtteranceProgressListener.onStart is the
            // proof that Android actually began speaking. This prevents silent ducking.
            return true;
        } catch (Throwable e) {
            restoreAudioAfterVoice();
            return false;
        }
    }

    private String voice(RoadHazard h, double forward) {
        String distance = distanceSpeech(forward);
        switch (h.type) {
            case "SEMAFORO":
                return "Atenção. Semáforo à frente. " + distance + ".";
            case "QUEBRA_MOLAS":
                return "Reduza. Quebra-molas à frente. " + distance + ".";
            case "PEDAGIO":
                return "Pedágio à frente. " + distance + ".";
            case "PASSAGEM_NIVEL":
                return "Atenção. Passagem de nível à frente. " + distance + ". Reduza.";
            case "CAMERA_MONITORAMENTO":
                return "Atenção. Câmera de monitoramento de tráfego à frente. " + distance + ".";
            default:
                if (h.speed > 0) return "Radar à frente, a " + distance + ". Limite do radar, " + h.speed + " quilômetros por hora.";
                return "Radar à frente. " + distance + ".";
        }
    }

    private void duckOwnPlayer(boolean duck) {
        try {
            Intent i = new Intent(this, PlayerService.class).setAction(duck ? PlayerService.ACTION_DUCK : PlayerService.ACTION_UNDUCK);
            startService(i);
        } catch (Throwable ignored) {}
    }

    private void requestVoiceFocus() {
        if (audioManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (alertFocusRequest == null) {
                    AudioAttributes attrs = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build();
                    alertFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(attrs)
                            .setAcceptsDelayedFocusGain(false)
                            .setWillPauseWhenDucked(false)
                            .build();
                }
                audioManager.requestAudioFocus(alertFocusRequest);
            } else {
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
        } catch (Throwable ignored) {}
    }

    private void restoreAudioAfterVoice() {
        main.removeCallbacks(restoreAudioFallback);
        duckOwnPlayer(false);
        if (audioManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && alertFocusRequest != null) audioManager.abandonAudioFocusRequest(alertFocusRequest);
            else audioManager.abandonAudioFocus(null);
        } catch (Throwable ignored) {}
    }

    private String distanceSpeech(double m) {
        if (m < 120) return Math.max(30, (int)(Math.round(m / 10.0) * 10)) + " metros";
        if (m >= 1000) return String.format(Locale.getDefault(), "%.1f quilômetros", m / 1000.0);
        return Math.max(100, (int)(Math.round(m / 50.0) * 50)) + " metros";
    }

    private String distanceText(double m) {
        if (m >= 1000) return String.format(Locale.getDefault(), "%.1f km", m / 1000.0);
        return Math.max(10, (int)(Math.round(m / 10.0) * 10)) + " m";
    }

    private void broadcast(Location loc, double speedKmh, RoadHazard h, double distance, String status,
                           RoadHazard next, double nextDistance) {
        Intent i = baseBroadcast(loc.getLatitude(), loc.getLongitude(), speedKmh, status);
        if (h != null) {
            i.putExtra("hazard_id", h.id);
            i.putExtra("hazard_type", h.type);
            i.putExtra("hazard_label", h.label());
            i.putExtra("road", h.road);
            i.putExtra("source", h.source);
            i.putExtra("distance_m", distance);
            i.putExtra("limit_kmh", h.speed);
            i.putExtra("radar_limit_kmh", h.speed);
            int delta = h.speed > 0 ? Math.max(0, (int)Math.round(speedKmh - h.speed)) : 0;
            i.putExtra("overspeed_delta_kmh", delta);
            int level = ("RADAR".equals(h.type) && delta >= 10) ||
                    ("QUEBRA_MOLAS".equals(h.type) && distance <= 130) ? 2 : 1;
            i.putExtra("alert_level", level);
        }
        if (next != null) {
            i.putExtra("next_hazard_type", next.type);
            i.putExtra("next_hazard_label", next.label());
            i.putExtra("next_distance_m", nextDistance);
            i.putExtra("next_limit_kmh", next.speed);
        }
        sendBroadcast(i);
    }

    private void broadcastSynthetic(double lat, double lon, String status) {
        // Coverage refresh is not a new GPS sample. Never overwrite a valid vehicle
        // speed with zero just because a network/package operation finished.
        sendBroadcast(baseBroadcast(lat, lon, lastSpeedKmh, status));
    }

    private Intent baseBroadcast(double lat, double lon, double speedKmh, String status) {
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra("lat", lat);
        i.putExtra("lon", lon);
        i.putExtra("speed_kmh", speedKmh);
        i.putExtra("road_limit_kmh", currentRoadLimitKmh);
        i.putExtra("heading", Float.isFinite(lastHeading) ? lastHeading : -1f);
        i.putExtra("pack_count", packs.packCount());
        i.putExtra("state_pack_count", packs.statePackCount());
        i.putExtra("core_state_count", packs.coreStatePackCount());
        i.putExtra("core_states_status", packs.coreStatesStatus());
        i.putExtra("thermal_status", thermalStatus());
        i.putExtra("reserve_km", 250);
        i.putExtra("hazard_count", packs.hazardCount());
        i.putExtra("map_pack_count", mapRoads.packCount());
        if(collectiveStore!=null){i.putExtra("collective_impact_count",collectiveStore.impactCount());i.putExtra("collective_queue_count",collectiveStore.queuedCount());}
        i.putExtra("upcoming_text",upcomingCache);
        i.putExtra("status", status == null ? "" : status);
        return i;
    }

    private void handleRoadImpact(RoadSurfaceMonitor.Impact impact) {
        if(impact==null)return;
        if(tripRecorder!=null)tripRecorder.onRoadImpact(impact);
        if(collectiveStore!=null){collectiveStore.recordImpact(impact);io.execute(()->{try{ensureApiSession(false);collectiveStore.flush(api);}catch(Throwable ignored){}});}
        Intent i=baseBroadcast(impact.lat,impact.lon,impact.speedKmh,"Irregularidade detectada pela suspensão/sensor");
        i.putExtra("road_surface_event",true);i.putExtra("road_surface_force",impact.force);sendBroadcast(i);
    }

    private void updateRestClock(Location loc,double speedKmh){
        long now=System.currentTimeMillis();
        if(speedKmh>=10){if(continuousDrivingStartedAt==0)continuousDrivingStartedAt=now;lastMovingForRestAt=now;if(!restSuggested&&now-continuousDrivingStartedAt>=2L*60L*60L*1000L){restSuggested=true;if(tripRecorder!=null)tripRecorder.onRestSuggested();Intent i=baseBroadcast(loc.getLatitude(),loc.getLongitude(),speedKmh,"Pausa sugerida após 2 horas em movimento");i.putExtra("rest_suggested",true);sendBroadcast(i);updateNotification("Pausa sugerida","Você está há cerca de 2 horas em movimento. Pare quando for seguro.",false);}}else if(lastMovingForRestAt>0&&now-lastMovingForRestAt>=15L*60L*1000L){continuousDrivingStartedAt=0;restSuggested=false;}
    }

    private String upcomingSummary(double lat,double lon,float heading){
        long now=System.currentTimeMillis();if(now-lastUpcomingAt<5000L)return upcomingCache;lastUpcomingAt=now;if(!Float.isFinite(heading)){upcomingCache="";return upcomingCache;}
        try{List<RoadHazard> list=packs.nearby(lat,lon,7000);ArrayList<String> rows=new ArrayList<>();ArrayList<Double> ds=new ArrayList<>();double rad=Math.toRadians(heading);for(RoadHazard h:list){double north=(h.lat-lat)*110540.0;double east=(h.lon-lon)*111320.0*Math.max(.25,Math.cos(Math.toRadians(lat)));double forward=east*Math.sin(rad)+north*Math.cos(rad);double lateral=Math.abs(east*Math.cos(rad)-north*Math.sin(rad));if(forward<150||forward>7000||lateral>220)continue;int at=0;while(at<ds.size()&&ds.get(at)<forward)at++;ds.add(at,forward);String d=forward>=1000?String.format(Locale.getDefault(),"%.1f km",forward/1000.0):Math.round(forward)+" m";String label=h.label()+(h.speed>0&&"RADAR".equals(h.type)?" "+h.speed:"")+" · "+d;rows.add(at,label);if(rows.size()>3){rows.remove(3);ds.remove(3);}}StringBuilder out=new StringBuilder();for(int i=0;i<rows.size();i++){if(i>0)out.append(" → ");out.append(rows.get(i));}upcomingCache=out.toString();return upcomingCache;}catch(Throwable ignored){upcomingCache="";return "";}
    }

    private int thermalStatus() {
        if (Build.VERSION.SDK_INT < 29) return 0;
        try {
            android.os.PowerManager pm = (android.os.PowerManager)getSystemService(POWER_SERVICE);
            return pm == null ? 0 : pm.getCurrentThermalStatus();
        } catch (Throwable ignored) { return 0; }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Alertas da estrada", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("GPS, alertas e mapa livre offline da estrada.");
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private Notification notification(String title, String text, boolean alert) {
        Intent open = new Intent(this, AutomotiveActivity.class);
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
        main.removeCallbacks(staleSpeedWatchdog);
        restoreAudioAfterVoice();
        try { if (locationManager != null) locationManager.removeUpdates(listener); } catch (Throwable ignored) {}
        try { if (offlineVoice != null) offlineVoice.release(); } catch (Throwable ignored) {}
        try { if (surfaceMonitor != null) surfaceMonitor.stop(); } catch (Throwable ignored) {}
        try { if (tripRecorder != null) tripRecorder.finish("serviço encerrado"); } catch (Throwable ignored) {}
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}
        io.shutdownNow();
        limitIo.shutdownNow();
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
