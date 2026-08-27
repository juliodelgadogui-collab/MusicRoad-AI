from pathlib import Path

repo = Path(__file__).resolve().parents[2]
app = repo / 'estradaplay-app'
p = app / 'app/src/main/java/com/estradaplay/app/RoadSafetyService.java'
s = p.read_text(encoding='utf-8')

# EstradaPlay 1.7.8 — stationary speed fix.
# Automotive GPS units can keep an old speed value or jitter position while parked.
# Confirm stop using multi-second net displacement and never leave dashboard speed stale.

old = '''    private double lastSpeedKmh;
    private long lastGpsFixWallMs;
    private long lastNotificationAt;
'''
new = '''    private double lastSpeedKmh;
    private long lastGpsFixWallMs;
    private long lastSpeedFixWallMs;
    private Location motionAnchor;
    private long motionAnchorWallMs;
    private boolean stationaryConfirmed;
    private long lastNotificationAt;
'''
if old not in s:
    raise SystemExit('1.7.8 speed fields anchor not found')
s = s.replace(old, new, 1)

old = '''    private final Runnable restoreAudioFallback = this::restoreAudioAfterVoice;
'''
new = '''    private final Runnable restoreAudioFallback = this::restoreAudioAfterVoice;

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
'''
if old not in s:
    raise SystemExit('1.7.8 watchdog anchor not found')
s = s.replace(old, new, 1)

old = '''        float heading = heading(loc);
        double speedKmh = speedKmh(loc);
        if (Double.isFinite(speedKmh)) lastSpeedKmh = Math.max(0.0, speedKmh);
        previous = new Location(loc);
'''
new = '''        float heading = heading(loc);
        double speedKmh = speedKmh(loc);
        if (Double.isFinite(speedKmh)) lastSpeedKmh = Math.max(0.0, speedKmh);
        lastSpeedFixWallMs = nowWall;
        main.removeCallbacks(staleSpeedWatchdog);
        main.postDelayed(staleSpeedWatchdog, 4500L);
        previous = new Location(loc);
'''
if old not in s:
    raise SystemExit('1.7.8 handleLocation speed anchor not found')
s = s.replace(old, new, 1)

old = '''        } else {
            long now = System.currentTimeMillis();
            if (now - lastNotificationAt > 7000L) {
                String state = packs.hasAnyCoverage(loc.getLatitude(), loc.getLongitude())
                        ? packs.status(loc.getLatitude(), loc.getLongitude()) + " · " + mapRoads.status(loc.getLatitude(), loc.getLongitude())
                        : (fetching.get() ? "Preparando alertas e mapa offline…" : "Aguardando proteção offline desta região");
                updateNotification("Proteção na estrada ativa", state, false);
                broadcast(loc, speedKmh, null, 0, state);
            }
        }
'''
new = '''        } else {
            long now = System.currentTimeMillis();
            String state = packs.hasAnyCoverage(loc.getLatitude(), loc.getLongitude())
                    ? packs.status(loc.getLatitude(), loc.getLongitude()) + " · " + mapRoads.status(loc.getLatitude(), loc.getLongitude())
                    : (fetching.get() ? "Preparando alertas e mapa offline…" : "Aguardando proteção offline desta região");
            if (now - lastNotificationAt > 7000L) {
                updateNotification("Proteção na estrada ativa", state, false);
            }
            // Speed is live telemetry, not a notification. Broadcast every accepted
            // location sample so the cockpit cannot display an old value for 7+ sec.
            broadcast(loc, speedKmh, null, 0, state);
        }
'''
if old not in s:
    raise SystemExit('1.7.8 live broadcast anchor not found')
s = s.replace(old, new, 1)

old = '''    private double speedKmh(Location loc) {
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
                float noiseGate = Math.max(3.0f, Math.min(18.0f, Math.max(accNow, accPrev) * 0.22f));
                if (moved >= noiseGate) derived = moved / (dt / 1000.0) * 3.6;
            }
        }

        double chosen;
        if (Double.isFinite(sensor) && sensor >= 2.0) chosen = sensor;
        else if (Double.isFinite(derived) && derived >= 2.0) chosen = derived;
        else if (Double.isFinite(sensor)) chosen = sensor;
        else if (Double.isFinite(derived)) chosen = derived;
        else chosen = 0.0;

        // Reject impossible GPS spikes. Preserve a recent moving value for one short
        // zero sample so interleaved providers do not make the dashboard flash 0.
        if (chosen > 260.0) chosen = lastSpeedKmh;
        if (chosen < 1.0 && lastSpeedKmh >= 5.0 && dt > 0L && dt <= 3500L && moved >= 2f) {
            chosen = lastSpeedKmh * 0.78;
        }
        if (chosen < 1.2) chosen = 0.0;
        return chosen;
    }
'''
new = '''    private double speedKmh(Location loc) {
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
            stationaryConfirmed = false;
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
'''
if old not in s:
    raise SystemExit('1.7.8 speedKmh anchor not found')
s = s.replace(old, new, 1)

old = '''    @Override public void onDestroy() {
        restoreAudioAfterVoice();
'''
new = '''    @Override public void onDestroy() {
        main.removeCallbacks(staleSpeedWatchdog);
        restoreAudioAfterVoice();
'''
if old not in s:
    raise SystemExit('1.7.8 onDestroy anchor not found')
s = s.replace(old, new, 1)

p.write_text(s, encoding='utf-8')

gradle = app / 'app/build.gradle'
s = gradle.read_text(encoding='utf-8')
s = s.replace('versionCode 19', 'versionCode 20')
s = s.replace("versionName '1.7.7'", "versionName '1.7.8'")
if "versionName '1.7.8'" not in s or 'versionCode 20' not in s:
    raise SystemExit('1.7.8 version bump failed')
gradle.write_text(s, encoding='utf-8')

print('EstradaPlay 1.7.8 stationary speed + live telemetry fix applied')
