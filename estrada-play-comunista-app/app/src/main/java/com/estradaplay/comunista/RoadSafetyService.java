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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RoadSafetyService extends Service {
    static final String ACTION_STATE = "com.estradaplay.comunista.ROAD_SAFETY_STATE";
    private static final String CHANNEL = "estradaplay_road_safety";
    private static final int NOTIFICATION_ID = 4110;
    private static final long ALERT_COOLDOWN_MS = 8L * 60L * 1000L;
    private static final String HEALTH_PREFS = "epc_protection_health_v303";
    static final String ACTION_PREFETCH_CORE = "com.estradaplay.comunista.PREFETCH_CORE";
    static final String ACTION_SAFETY_AUDIO = "com.estradaplay.comunista.SAFETY_AUDIO";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService limitIo = Executors.newSingleThreadExecutor();
    private final AtomicBoolean fetching = new AtomicBoolean(false);
    private final RoadAlertCooldown alertCooldown = new RoadAlertCooldown(ALERT_COOLDOWN_MS, 300);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final long serviceStartedAt = System.currentTimeMillis();

    private LocationManager locationManager;
    private volatile RoadPackStore packs;
    private volatile OfflineRoadStore mapRoads;
    private final AtomicBoolean storesLoading = new AtomicBoolean(false);
    private final AtomicBoolean coreStatesPriming = new AtomicBoolean(false);
    private ApiClient api;
    private TextToSpeech tts;
    private EstradaPlayOfflineVoice offlineVoice;
    private CommunistCopilot copilot;
    private TripRecorder tripRecorder;
    private CollectiveRoadStore collectiveStore;
    private RoadSurfaceMonitor surfaceMonitor;
    private RoadQualityStore roadQualityStore;
    private long lastWeatherCheckAt;
    private long lastWeatherVoiceAt;
    private String lastWeatherVoiceKey="";
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
    private long lastCoverageCheckAt;
    private long lastStateRefreshAt;
    private String lastStateText = "GPS ativo · preparando proteção";
    private final AtomicBoolean roadLimitResolving = new AtomicBoolean(false);
    private final RoadLimitPolicy roadLimitPolicy = new RoadLimitPolicy();
    private long lastRoadLimitCheckAt;
    private final long thoughtSessionStartedAt = System.currentTimeMillis();
    private long lastThoughtCheckAt;
    private long lastSafetyVoiceAt;
    private long continuousDrivingStartedAt,lastMovingForRestAt;
    private boolean restSuggested;
    private long lastUpcomingAt; private String upcomingCache="";
    private int voiceSessionCounter;
    private int activeVoiceToken;
    private String activeVoiceKind="";
    private long voiceBusyUntil;
    private Runnable voiceRestoreWatchdog;
    private boolean protectionGpsUnavailable;

    private final Runnable staleSpeedWatchdog = () -> {
        long age = System.currentTimeMillis() - lastSpeedFixWallMs;
        if (lastSpeedFixWallMs > 0L && age >= 4500L && lastSpeedKmh > 0.0 && previous != null) {
            lastSpeedKmh = 0.0;
            stationaryConfirmed = true;
            sendBroadcast(baseBroadcast(previous.getLatitude(), previous.getLongitude(), 0.0,
                    "GPS sem movimento recente"));
        }
    };

    // PROTECTION_CONTINUITY_V303: detect a frozen/no-update GPS stream and recover the
    // listener without pretending protection is healthy. This watchdog never fabricates a fix.
    private final Runnable protectionWatchdog = new Runnable() {
        @Override public void run() {
            try {
                long now = System.currentTimeMillis();
                long age = lastSpeedFixWallMs > 0L ? now - lastSpeedFixWallMs : now - serviceStartedAt;
                boolean stale = age >= 25_000L;
                getSharedPreferences(HEALTH_PREFS, MODE_PRIVATE).edit()
                        .putLong("heartbeat_at", now)
                        .putLong("gps_age_ms", Math.max(0L, age))
                        .putBoolean("gps_ok", !stale)
                        .apply();
                if (stale) {
                    startLocation();
                    if (!protectionGpsUnavailable) {
                        protectionGpsUnavailable = true;
                        updateNotification("Proteção temporariamente indisponível",
                                "GPS sem sinal confiável · tentando recuperar", true);
                        Location p = previous;
                        if (p != null && packs != null && mapRoads != null) {
                            Intent state = baseBroadcast(p.getLatitude(), p.getLongitude(), 0.0,
                                    "Proteção temporariamente indisponível · GPS sem sinal confiável");
                            state.putExtra("protection_available", false);
                            state.putExtra("gps_fix_age_ms", age);
                            sendBroadcast(state);
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                main.postDelayed(this, 15_000L);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        api = new ApiClient(this);
        offlineVoice = new EstradaPlayOfflineVoice(this);
        copilot = new CommunistCopilot(this);
        tripRecorder = new TripRecorder(this);
        collectiveStore = new CollectiveRoadStore(this);
        surfaceMonitor = new RoadSurfaceMonitor(this, this::handleRoadImpact);
        roadQualityStore = new RoadQualityStore(this);
        surfaceMonitor.start();
        locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);
        audioManager = (AudioManager)getSystemService(AUDIO_SERVICE);
        createChannel();
        startForeground(NOTIFICATION_ID, notification("Proteção na estrada ativa", "GPS aguardando localização", false));
        getSharedPreferences(HEALTH_PREFS, MODE_PRIVATE).edit()
                .putLong("service_started_at", System.currentTimeMillis())
                .putBoolean("service_alive", true)
                .apply();
        main.removeCallbacks(protectionWatchdog);
        main.postDelayed(protectionWatchdog, 15_000L);
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                try {
                    tts.setLanguage(new Locale("pt", "BR"));
                    tts.setSpeechRate(0.91f);
                    tts.setPitch(0.84f);
                    selectEstradaPlayVoice();
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String utteranceId) { int t=voiceTokenFromId(utteranceId); main.post(() -> { if(t==activeVoiceToken){ requestVoiceFocus(); beginVoiceDucking(t); } }); }
                        @Override public void onDone(String utteranceId) { int t=voiceTokenFromId(utteranceId); main.post(() -> restoreAudioAfterVoice(t)); }
                        @Override public void onError(String utteranceId) { int t=voiceTokenFromId(utteranceId); main.post(() -> restoreAudioAfterVoice(t)); }
                        @Override public void onStop(String utteranceId, boolean interrupted) { int t=voiceTokenFromId(utteranceId); main.post(() -> restoreAudioAfterVoice(t)); }
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
        getSharedPreferences(HEALTH_PREFS, MODE_PRIVATE).edit().putLong("last_start_command_at", System.currentTimeMillis()).apply();
        return START_STICKY;
    }

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
            return;
        }

        if (protectionGpsUnavailable) {
            protectionGpsUnavailable = false;
            updateNotification("Proteção na estrada ativa", "GPS recuperado · proteção retomada", false);
        }
        getSharedPreferences(HEALTH_PREFS, MODE_PRIVATE).edit()
                .putLong("last_fix_at", nowWall)
                .putBoolean("gps_ok", true)
                .apply();

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
        if (DriveSettings.autoRain(this) && !DriveSettings.offlineTestMode(this) && nowWall-lastWeatherCheckAt>=15L*60L*1000L) {
            lastWeatherCheckAt=nowWall; final double wa=loc.getLatitude(), wo=loc.getLongitude(); io.execute(() -> {RoadWeatherMonitor.refresh(this,wa,wo);main.post(this::maybeAnnounceWeatherForecast);});
        }

        maybeResolveRoadLimit(loc.getLatitude(), loc.getLongitude(), heading);
        evaluateRoadLimit(speedKmh);
        ensureCoverage(loc.getLatitude(), loc.getLongitude(), heading);
        List<RoadHazard> nearby = packs.nearby(loc.getLatitude(), loc.getLongitude(), 1900);
        RoadHazardSelector.Selection selection = RoadHazardSelector.select(
                nearby, loc.getLatitude(), loc.getLongitude(),
                Float.isFinite(heading) ? heading : Double.NaN, speedKmh,
                DriveSettings.rainNow(this), alertCooldown, nowWall);
        RoadHazard best = selection.best;
        RoadHazard next = selection.next;
        double bestForward = selection.bestMatch.forwardM;
        double bestDistance = selection.bestMatch.distanceM;
        double nextForward = selection.nextMatch.forwardM;

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
        if (mode == RoadThoughts.MODE_OFF || loc == null || speedKmh < 5.0 || voiceBusy()) return;
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
        if (mode == RoadThoughts.MODE_SCREEN_VOICE && VoiceSettings.mode(this) == VoiceSettings.MODE_ANDROID && ttsReady) speakThought(RoadThoughts.spoken(e));
    }

    private void interruptThoughtForSafety() { lastSafetyVoiceAt = System.currentTimeMillis(); }

    private void maybeAnnounceWeatherForecast() {
        RoadWeatherMonitor.Snapshot wx=RoadWeatherMonitor.snapshot(this);
        if(!wx.shouldAnnounce()||voiceBusy())return;
        long now=System.currentTimeMillis();String key=wx.voiceKey();
        if(key.isEmpty())return;
        if(key.equals(lastWeatherVoiceKey)&&now-lastWeatherVoiceAt<90L*60L*1000L)return;
        if(speakWeatherVoice(wx.spoken())){lastWeatherVoiceKey=key;lastWeatherVoiceAt=now;}
    }

    private boolean speakWeatherVoice(String text) {
        if(!ttsReady||tts==null||voiceBusy()||text==null||text.trim().isEmpty())return false;
        if(VoiceSettings.mode(this)==VoiceSettings.MODE_EMBEDDED)return false;
        int token=openVoiceSession("weather",false);if(token<=0)return false;
        if(speakWithToken(text,token))return true;
        restoreAudioAfterVoice(token);return false;
    }

    private void maybeResolveRoadLimit(double lat, double lon, float heading) {
        long now = System.currentTimeMillis();
        if (now - lastRoadLimitCheckAt < 7000L || roadLimitResolving.get()) return;
        lastRoadLimitCheckAt = now;
        if (!roadLimitResolving.compareAndSet(false, true)) return;
        limitIo.execute(() -> {
            int limit = 0;
            try { if (mapRoads != null) limit = mapRoads.speedLimitAt(lat, lon, heading); } catch (Throwable ignored) {}
            final int resolved = limit;
            main.post(() -> applyRoadLimit(resolved));
            roadLimitResolving.set(false);
        });
    }

    private void applyRoadLimit(int limitKmh) {
        if (!roadLimitPolicy.applyLimit(limitKmh)) return;
        if (roadLimitPolicy.shouldAnnounceLimit() && speakRoadLimitVoice(roadLimitPolicy.currentLimit())) roadLimitPolicy.markLimitAnnounced();
    }

    private void evaluateRoadLimit(double speedKmh) {
        int limit = roadLimitPolicy.currentLimit();
        if (roadLimitPolicy.observeSpeedAndShouldWarn(speedKmh) && speakOverspeedVoice(limit)) roadLimitPolicy.markOverspeedWarned();
    }

    private void ensureCoverage(double lat, double lon, float heading) {
        long now = System.currentTimeMillis();
        if (now - lastCoverageCheckAt < 5000L) return;
        lastCoverageCheckAt = now;
        boolean alertNeeds = packs.needsPreparation(lat, lon, heading);
        boolean mapNeeds = mapRoads.needsPreparation(lat, lon, heading);
        if (DriveSettings.offlineTestMode(this)) return;
        if ((!alertNeeds && !mapNeeds) || fetching.get()) return;
        if (!fetching.compareAndSet(false, true)) return;
        updateNotification("Preparando viagem offline", "Alertas + mapa livre para até 250 km à frente", false);
        io.execute(() -> {
            try {
                ensureApiSession(false);
                boolean alertOk = !alertNeeds || packs.prepareTravelReserve(api, lat, lon, heading);
                boolean mapOk = !mapNeeds || mapRoads.prepare(api, lat, lon, heading);
                if (!alertOk || !mapOk) {
                    ensureApiSession(true);
                    if (alertNeeds && !alertOk) packs.prepareTravelReserve(api, lat, lon, heading);
                    if (mapNeeds && !mapOk) mapRoads.prepare(api, lat, lon, heading);
                }
            } finally {
                fetching.set(false);
                String text = packs.hasAnyCoverage(lat, lon) ? packs.status(lat, lon) + " · " + mapRoads.status(lat, lon) : "Não consegui atualizar agora; usando o que já está salvo";
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
            if (!response.ok() || !response.json().optBoolean("ok", false)) { if (force) api.clearSession(); }
        } catch (Exception ignored) { if (force) api.clearSession(); }
    }

    private boolean shouldAlert(RoadHazard h) { return h != null && alertCooldown.shouldAlert(h.id, System.currentTimeMillis()); }
    private void rememberAlert(RoadHazard h) { if (h != null) alertCooldown.remember(h.id, System.currentTimeMillis()); }

    private float heading(Location loc) {
        if (loc.hasBearing()) { lastHeading = normalize(loc.getBearing()); return lastHeading; }
        if (previous != null) {
            float moved = previous.distanceTo(loc);
            long dt = Math.max(1L, loc.getTime() - previous.getTime());
            if (moved >= 4f && dt <= 15000L) { lastHeading = normalize(previous.bearingTo(loc)); return lastHeading; }
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
        if (motionAnchor == null) {
            motionAnchor = new Location(loc); motionAnchorWallMs = nowWall; stationaryConfirmed = true;
        } else {
            long anchorAge = nowWall - motionAnchorWallMs;
            if (anchorAge >= 2500L) {
                float anchorMoved = motionAnchor.distanceTo(loc);
                float accNow = loc.hasAccuracy() ? loc.getAccuracy() : 20f;
                float accAnchor = motionAnchor.hasAccuracy() ? motionAnchor.getAccuracy() : 20f;
                float stationaryRadius = Math.max(6.0f, Math.min(12.0f, Math.max(accNow, accAnchor) * 0.45f));
                stationaryConfirmed = anchorMoved <= stationaryRadius;
                if (anchorAge >= 5000L || anchorMoved > stationaryRadius * 1.5f) { motionAnchor = new Location(loc); motionAnchorWallMs = nowWall; }
            }
        }
        double chosen;
        if (Double.isFinite(sensor) && sensor >= 2.0) chosen = sensor;
        else if (Double.isFinite(derived) && derived >= 2.0) chosen = derived;
        else if (Double.isFinite(sensor)) chosen = sensor;
        else if (Double.isFinite(derived)) chosen = derived;
        else chosen = 0.0;
        if (stationaryConfirmed) chosen = 0.0;
        if (chosen > 260.0) chosen = stationaryConfirmed ? 0.0 : lastSpeedKmh;
        if (!stationaryConfirmed && chosen < 1.0 && lastSpeedKmh >= 5.0 && dt > 0L && dt <= 2500L && moved >= 3f) chosen = lastSpeedKmh * 0.70;
        if (chosen < 1.5) chosen = 0.0;
        return chosen;
    }

    private void selectEstradaPlayVoice() {
        if (tts == null || Build.VERSION.SDK_INT < 21) return;
        try {
            java.util.Set<Voice> voices = tts.getVoices(); if (voices == null || voices.isEmpty()) return;
            ArrayList<Voice> pt = new ArrayList<>();
            for (Voice v : voices) {
                if (v == null || v.getLocale() == null) continue;
                String lang = v.getLocale().getLanguage(); String country = v.getLocale().getCountry();
                if (!"pt".equalsIgnoreCase(lang)) continue;
                if (!country.isEmpty() && !"BR".equalsIgnoreCase(country)) continue;
                pt.add(v);
            }
            if (pt.isEmpty()) return;
            pt.sort(Comparator.comparing((Voice v) -> v.isNetworkConnectionRequired()).thenComparing((Voice v) -> -v.getQuality()).thenComparing(Voice::getName));
            tts.setVoice(pt.get(0));
        } catch (Throwable ignored) {}
    }

    private boolean voiceBusy() { return activeVoiceToken > 0 && System.currentTimeMillis() < voiceBusyUntil; }

    private int openVoiceSession(String kind, boolean interrupt) {
        if (!interrupt && voiceBusy()) return 0;
        int token = ++voiceSessionCounter;
        activeVoiceToken = token; activeVoiceKind = kind == null ? "" : kind; voiceBusyUntil = System.currentTimeMillis() + 15_000L;
        if (interrupt) { try { if (offlineVoice != null) offlineVoice.stop(); } catch (Throwable ignored) {} try { if (tts != null) tts.stop(); } catch (Throwable ignored) {} }
        return token;
    }

    private void beginVoiceDucking(int token) {
        if (token <= 0 || token != activeVoiceToken) return;
        sendSafetyAudioState(true); duckOwnPlayer(true); voiceBusyUntil = System.currentTimeMillis() + 15_000L;
        if (voiceRestoreWatchdog != null) main.removeCallbacks(voiceRestoreWatchdog);
        voiceRestoreWatchdog = () -> restoreAudioAfterVoice(token); main.postDelayed(voiceRestoreWatchdog, 15_000L);
    }

    private void restoreAudioAfterVoice(int token) {
        if (token <= 0 || token != activeVoiceToken) return;
        if (voiceRestoreWatchdog != null) { main.removeCallbacks(voiceRestoreWatchdog); voiceRestoreWatchdog = null; }
        activeVoiceToken = 0; activeVoiceKind = ""; voiceBusyUntil = 0L; forceRestoreAudio();
    }

    private void restoreAudioAfterVoice() { int token = activeVoiceToken; if (token > 0) restoreAudioAfterVoice(token); else forceRestoreAudio(); }

    private void forceRestoreAudio() {
        sendSafetyAudioState(false); duckOwnPlayer(false);
        if (audioManager == null) return;
        try { if (Build.VERSION.SDK_INT >= 26 && alertFocusRequest != null) audioManager.abandonAudioFocusRequest(alertFocusRequest); else audioManager.abandonAudioFocus(null); } catch (Throwable ignored) {}
    }

    private int voiceTokenFromId(String id) { if (id == null || !id.startsWith("ep-voice-")) return -1; try { return Integer.parseInt(id.substring("ep-voice-".length())); } catch (Throwable ignored) { return -1; } }

    private boolean speakRoadLimitVoice(int limitKmh) {
        if (voiceBusy() && !"thought".equals(activeVoiceKind)) return false;
        interruptThoughtForSafety(); int token = openVoiceSession("limit", true); int mode = VoiceSettings.mode(this);
        if (mode != VoiceSettings.MODE_ANDROID && offlineVoice != null && offlineVoice.playRoadLimit(limitKmh, () -> beginVoiceDucking(token), () -> restoreAudioAfterVoice(token))) return true;
        if (mode != VoiceSettings.MODE_EMBEDDED && ttsReady && copilot != null && speakWithToken(copilot.roadLimit(limitKmh), token)) return true;
        restoreAudioAfterVoice(token); return false;
    }

    private boolean speakOverspeedVoice(int limitKmh) {
        if (voiceBusy() && !"thought".equals(activeVoiceKind)) return false;
        interruptThoughtForSafety(); int token = openVoiceSession("overspeed", true); int mode = VoiceSettings.mode(this);
        if (mode != VoiceSettings.MODE_ANDROID && offlineVoice != null && offlineVoice.playOverspeed(limitKmh, () -> beginVoiceDucking(token), () -> restoreAudioAfterVoice(token))) return true;
        if (mode != VoiceSettings.MODE_EMBEDDED && ttsReady && copilot != null && speakWithToken(copilot.overspeed(limitKmh), token)) return true;
        restoreAudioAfterVoice(token); return false;
    }

    private void speakHazardVoice(RoadHazard h, double forwardM) {
        interruptThoughtForSafety(); int token = openVoiceSession("hazard", true); int mode = VoiceSettings.mode(this);
        if (mode != VoiceSettings.MODE_ANDROID && offlineVoice != null && h != null && offlineVoice.playHazard(h.type, forwardM, h.speed, () -> beginVoiceDucking(token), () -> restoreAudioAfterVoice(token))) return;
        if (mode != VoiceSettings.MODE_EMBEDDED && ttsReady && copilot != null && h != null && speakWithToken(copilot.hazard(h.type, forwardM, h.speed), token)) return;
        if (mode != VoiceSettings.MODE_EMBEDDED && speakWithToken(voice(h, forwardM), token)) return;
        restoreAudioAfterVoice(token);
    }

    private boolean speakThought(String text) { if (!ttsReady || voiceBusy()) return false; int token = openVoiceSession("thought", false); if (token <= 0) return false; if (speakWithToken(text, token)) return true; restoreAudioAfterVoice(token); return false; }

    private boolean speakWithToken(String text, int token) {
        if (!ttsReady || tts == null || token <= 0 || token != activeVoiceToken || text == null || text.trim().isEmpty()) return false;
        try { int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ep-voice-" + token); return result != TextToSpeech.ERROR; } catch (Throwable ignored) { return false; }
    }

    private String voice(RoadHazard h, double forward) {
        String distance = distanceSpeech(forward);
        switch (h.type) {
            case "SEMAFORO": return "Atenção. Semáforo à frente. " + distance + ".";
            case "SEMAFORO_RADAR": return "Atenção. Semáforo fiscalizado à frente. " + distance + ".";
            case "QUEBRA_MOLAS": return "Reduza. Quebra-molas à frente. " + distance + ".";
            case "PEDAGIO": return "Pedágio à frente. " + distance + ".";
            case "PASSAGEM_NIVEL": return "Atenção. Passagem de nível à frente. " + distance + ". Reduza.";
            case "CAMERA_MONITORAMENTO": return "Atenção. Câmera de monitoramento de tráfego à frente. " + distance + ".";
            default:
                if (h.speed > 0) return "Radar à frente, a " + distance + ". Limite do radar, " + h.speed + " quilômetros por hora.";
                return "Radar à frente. " + distance + ".";
        }
    }

    private void duckOwnPlayer(boolean duck) { try { Intent i = new Intent(this, PlayerService.class).setAction(duck ? PlayerService.ACTION_DUCK : PlayerService.ACTION_UNDUCK); startService(i); } catch (Throwable ignored) {} }
    private void sendSafetyAudioState(boolean active) { try { Intent i = new Intent(ACTION_SAFETY_AUDIO).setPackage(getPackageName()); i.putExtra("active", active); sendBroadcast(i); } catch (Throwable ignored) {} }

    private void requestVoiceFocus() {
        if (audioManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (alertFocusRequest == null) {
                    AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
                    alertFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(false).build();
                }
                audioManager.requestAudioFocus(alertFocusRequest);
            } else audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        } catch (Throwable ignored) {}
    }

    private String distanceSpeech(double m) { return RoadSafetyFormat.distanceSpeech(m); }
    private String distanceText(double m) { return RoadSafetyFormat.distanceText(m); }

    private void broadcast(Location loc, double speedKmh, RoadHazard h, double distance, String status, RoadHazard next, double nextDistance) {
        Intent i = baseBroadcast(loc.getLatitude(), loc.getLongitude(), speedKmh, status);
        if (h != null) {
            i.putExtra("hazard_id", h.id); i.putExtra("hazard_type", h.type); i.putExtra("hazard_label", h.label()); i.putExtra("road", h.road); i.putExtra("source", h.source); i.putExtra("hazard_confidence", h.confidenceLabel()); i.putExtra("distance_m", distance); i.putExtra("limit_kmh", h.speed); i.putExtra("radar_limit_kmh", h.speed);
            int delta = h.speed > 0 ? Math.max(0, (int)Math.round(speedKmh - h.speed)) : 0; i.putExtra("overspeed_delta_kmh", delta);
            int level = ("RADAR".equals(h.type) && delta >= 10) || ("QUEBRA_MOLAS".equals(h.type) && distance <= 130) ? 2 : 1; i.putExtra("alert_level", level);
        }
        if (next != null) { i.putExtra("next_hazard_type", next.type); i.putExtra("next_hazard_label", next.label()); i.putExtra("next_distance_m", nextDistance); i.putExtra("next_limit_kmh", next.speed); }
        sendBroadcast(i);
    }

    private void broadcastSynthetic(double lat, double lon, String status) { sendBroadcast(baseBroadcast(lat, lon, lastSpeedKmh, status)); }

    private Intent baseBroadcast(double lat, double lon, double speedKmh, String status) {
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        long now = System.currentTimeMillis();
        long fixAge = lastSpeedFixWallMs > 0L ? Math.max(0L, now - lastSpeedFixWallMs) : Math.max(0L, now - serviceStartedAt);
        i.putExtra("lat", lat); i.putExtra("lon", lon); i.putExtra("speed_kmh", speedKmh); i.putExtra("road_limit_kmh", roadLimitPolicy.currentLimit()); i.putExtra("heading", Float.isFinite(lastHeading) ? lastHeading : -1f); i.putExtra("pack_count", packs.packCount()); i.putExtra("state_pack_count", packs.statePackCount()); i.putExtra("core_state_count", packs.coreStatePackCount()); i.putExtra("core_states_status", packs.coreStatesStatus()); i.putExtra("thermal_status", thermalStatus()); i.putExtra("rain_mode", DriveSettings.rainNow(this)); i.putExtra("weather_status", RoadWeatherMonitor.compactStatus(this)); i.putExtra("weather_rain_ahead", RoadWeatherMonitor.snapshot(this).shouldAnnounce()); i.putExtra("night_mode", DriveSettings.nightNow(this)); i.putExtra("offline_test_mode", DriveSettings.offlineTestMode(this)); i.putExtra("reserve_km", 250); i.putExtra("hazard_count", packs.hazardCount()); i.putExtra("map_pack_count", mapRoads.packCount());
        i.putExtra("protection_available", !protectionGpsUnavailable); i.putExtra("gps_fix_age_ms", fixAge);
        if(collectiveStore!=null){i.putExtra("collective_impact_count",collectiveStore.impactCount());i.putExtra("collective_queue_count",collectiveStore.queuedCount());}
        i.putExtra("upcoming_text",upcomingCache); i.putExtra("status", status == null ? "" : status); return i;
    }

    private void handleRoadImpact(RoadSurfaceMonitor.Impact impact) {
        if(impact==null)return;
        if(tripRecorder!=null)tripRecorder.onRoadImpact(impact);
        if(roadQualityStore!=null)roadQualityStore.record(impact);
        if(collectiveStore!=null){collectiveStore.recordImpact(impact);io.execute(()->{try{ensureApiSession(false);collectiveStore.flush(api);}catch(Throwable ignored){}});}
        Intent i=baseBroadcast(impact.lat,impact.lon,impact.speedKmh,"Irregularidade detectada pela suspensão/sensor"); i.putExtra("road_surface_event",true);i.putExtra("road_surface_force",impact.force);sendBroadcast(i);
    }

    private void updateRestClock(Location loc,double speedKmh){
        long now=System.currentTimeMillis();
        if(speedKmh>=10){if(continuousDrivingStartedAt==0)continuousDrivingStartedAt=now;lastMovingForRestAt=now;if(!restSuggested&&now-continuousDrivingStartedAt>=2L*60L*60L*1000L){restSuggested=true;if(tripRecorder!=null)tripRecorder.onRestSuggested();Intent i=baseBroadcast(loc.getLatitude(),loc.getLongitude(),speedKmh,"Pausa sugerida após 2 horas em movimento");i.putExtra("rest_suggested",true);sendBroadcast(i);updateNotification("Pausa sugerida","Você está há cerca de 2 horas em movimento. Pare quando for seguro.",false);}}else if(lastMovingForRestAt>0&&now-lastMovingForRestAt>=15L*60L*1000L){continuousDrivingStartedAt=0;restSuggested=false;}
    }

    private String upcomingSummary(double lat,double lon,float heading){
        long now=System.currentTimeMillis();if(now-lastUpcomingAt<5000L)return upcomingCache;lastUpcomingAt=now;if(!Float.isFinite(heading)){upcomingCache="";return upcomingCache;}
        try{List<RoadHazard> list=packs.nearby(lat,lon,7000);ArrayList<String> rows=new ArrayList<>();ArrayList<Double> ds=new ArrayList<>();double rad=Math.toRadians(heading);for(RoadHazard h:list){double north=(h.lat-lat)*110540.0;double east=(h.lon-lon)*111320.0*Math.max(.25,Math.cos(Math.toRadians(lat)));double forward=east*Math.sin(rad)+north*Math.cos(rad);double lateral=Math.abs(east*Math.cos(rad)-north*Math.sin(rad));if(forward<150||forward>7000||lateral>220)continue;int at=0;while(at<ds.size()&&ds.get(at)<forward)at++;ds.add(at,forward);String d=forward>=1000?String.format(Locale.getDefault(),"%.1f km",forward/1000.0):Math.round(forward)+" m";String label=h.label()+(h.speed>0&&"RADAR".equals(h.type)?" "+h.speed:"")+" · "+d;rows.add(at,label);if(rows.size()>3){rows.remove(3);ds.remove(3);}}StringBuilder out=new StringBuilder();for(int i=0;i<rows.size();i++){if(i>0)out.append(" → ");out.append(rows.get(i));}upcomingCache=out.toString();return upcomingCache;}catch(Throwable ignored){upcomingCache="";return "";}
    }

    private int thermalStatus() { if (Build.VERSION.SDK_INT < 29) return 0; try { android.os.PowerManager pm = (android.os.PowerManager)getSystemService(POWER_SERVICE); return pm == null ? 0 : pm.getCurrentThermalStatus(); } catch (Throwable ignored) { return 0; } }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Alertas da estrada", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("GPS, alertas e mapa livre offline da estrada."); ch.setSound(null, null); nm.createNotificationChannel(ch);
    }

    private Notification notification(String title, String text, boolean alert) {
        Intent open = new Intent(this, AutomotiveActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 10, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle(title).setContentText(text).setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(!alert).setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PUBLIC);
        return b.build();
    }

    private void updateNotification(String title, String text, boolean alert) {
        lastNotificationAt = System.currentTimeMillis();
        NotificationPermissionCompat.notify(this, NOTIFICATION_ID, notification(title, text, alert));
    }

    private static float normalize(float deg) { float v = deg % 360f; return v < 0 ? v + 360f : v; }
    private static double angleDiff(double a, double b) { double d = Math.abs(a - b) % 360.0; return d > 180.0 ? 360.0 - d : d; }

    @Override public void onTaskRemoved(Intent rootIntent) {
        try { stopService(new Intent(this, PlayerService.class)); } catch (Throwable ignored) {}
        try { stopService(new Intent(this, DownloadService.class)); } catch (Throwable ignored) {}
        getSharedPreferences(HEALTH_PREFS, MODE_PRIVATE).edit().putLong("task_removed_at", System.currentTimeMillis()).apply();
        // Do not stop RoadSafetyService: road protection is intentionally independent of the UI task.
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        main.removeCallbacks(staleSpeedWatchdog);
        main.removeCallbacks(protectionWatchdog);
        getSharedPreferences(HEALTH_PREFS, MODE_PRIVATE).edit()
                .putBoolean("service_alive", false)
                .putLong("service_destroyed_at", System.currentTimeMillis())
                .apply();
        activeVoiceToken = ++voiceSessionCounter;
        if (voiceRestoreWatchdog != null) main.removeCallbacks(voiceRestoreWatchdog);
        forceRestoreAudio();
        try { if (locationManager != null) locationManager.removeUpdates(listener); } catch (Throwable ignored) {}
        try { if (offlineVoice != null) offlineVoice.release(); } catch (Throwable ignored) {}
        try { if (surfaceMonitor != null) surfaceMonitor.stop(); } catch (Throwable ignored) {}
        try { if (tripRecorder != null) tripRecorder.finish("serviço encerrado"); } catch (Throwable ignored) {}
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}
        io.shutdownNow(); limitIo.shutdownNow(); super.onDestroy();
    }
}
