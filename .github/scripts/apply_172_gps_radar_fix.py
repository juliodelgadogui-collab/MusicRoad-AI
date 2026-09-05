from pathlib import Path

repo = Path(__file__).resolve().parents[2]
app = repo / 'estradaplay-app'

# RoadSafetyService: automotive GPS providers may expose hasSpeed()==true while
# repeatedly returning 0 m/s. Do not let that suppress bearing/radar matching.
p = app / 'app/src/main/java/com/estradaplay/app/RoadSafetyService.java'
s = p.read_text(encoding='utf-8')

old = '''    private Location previous;\n    private float lastHeading = Float.NaN;\n    private long lastNotificationAt;\n'''
new = '''    private Location previous;\n    private float lastHeading = Float.NaN;\n    private double lastSpeedKmh;\n    private long lastGpsFixWallMs;\n    private long lastNotificationAt;\n'''
if old not in s:
    raise SystemExit('1.7.2 RoadSafetyService fields anchor not found')
s = s.replace(old, new, 1)

old = '''    private void handleLocation(Location loc) {\n        if (loc == null) return;\n        if (loc.hasAccuracy() && loc.getAccuracy() > 120f && previous != null) return;\n\n        float heading = heading(loc);\n        double speedKmh = speedKmh(loc);\n        previous = new Location(loc);\n'''
new = '''    private void handleLocation(Location loc) {\n        if (loc == null) return;\n        if (loc.hasAccuracy() && loc.getAccuracy() > 120f && previous != null) return;\n\n        long nowWall = System.currentTimeMillis();\n        String provider = loc.getProvider() == null ? "" : loc.getProvider();\n        if (LocationManager.GPS_PROVIDER.equals(provider)) {\n            lastGpsFixWallMs = nowWall;\n        } else if (LocationManager.NETWORK_PROVIDER.equals(provider) && nowWall - lastGpsFixWallMs < 4000L) {\n            // A recent GPS fix is more useful than an interleaved network fix that can\n            // report speed=0 and erase the vehicle speed on automotive Android ROMs.\n            return;\n        }\n\n        float heading = heading(loc);\n        double speedKmh = speedKmh(loc);\n        if (Double.isFinite(speedKmh)) lastSpeedKmh = Math.max(0.0, speedKmh);\n        previous = new Location(loc);\n'''
if old not in s:
    raise SystemExit('1.7.2 handleLocation anchor not found')
s = s.replace(old, new, 1)

s = s.replace(
    '        if (Float.isFinite(heading) && speedKmh >= 7.0) {',
    '        if (Float.isFinite(heading) && speedKmh >= 4.0) {',
    1,
)

old = '''    private float heading(Location loc) {\n        if (loc.hasBearing() && (!loc.hasSpeed() || loc.getSpeed() > 1.5f)) {\n            lastHeading = normalize(loc.getBearing());\n            return lastHeading;\n        }\n        if (previous != null) {\n            float moved = previous.distanceTo(loc);\n            long dt = Math.max(1L, loc.getTime() - previous.getTime());\n            if (moved >= 8f && dt <= 15000L) {\n                lastHeading = normalize(previous.bearingTo(loc));\n                return lastHeading;\n            }\n        }\n        return lastHeading;\n    }\n\n    private double speedKmh(Location loc) {\n        if (loc.hasSpeed()) return Math.max(0.0, loc.getSpeed() * 3.6);\n        if (previous == null) return 0.0;\n        long dt = loc.getTime() - previous.getTime();\n        if (dt <= 0 || dt > 15000L) return 0.0;\n        return previous.distanceTo(loc) / (dt / 1000.0) * 3.6;\n    }\n'''
new = '''    private float heading(Location loc) {\n        // Bearing and speed are independent Android Location fields. Some head units\n        // provide a good bearing while speed is present-but-zero, so never discard it.\n        if (loc.hasBearing()) {\n            lastHeading = normalize(loc.getBearing());\n            return lastHeading;\n        }\n        if (previous != null) {\n            float moved = previous.distanceTo(loc);\n            long dt = Math.max(1L, loc.getTime() - previous.getTime());\n            if (moved >= 4f && dt <= 15000L) {\n                lastHeading = normalize(previous.bearingTo(loc));\n                return lastHeading;\n            }\n        }\n        return lastHeading;\n    }\n\n    private double speedKmh(Location loc) {\n        double sensor = Double.NaN;\n        if (loc.hasSpeed()) sensor = Math.max(0.0, loc.getSpeed() * 3.6);\n\n        double derived = Double.NaN;\n        long dt = 0L;\n        float moved = 0f;\n        if (previous != null) {\n            dt = loc.getTime() - previous.getTime();\n            if (dt > 0L && dt <= 15000L) {\n                moved = previous.distanceTo(loc);\n                float accNow = loc.hasAccuracy() ? loc.getAccuracy() : 25f;\n                float accPrev = previous.hasAccuracy() ? previous.getAccuracy() : 25f;\n                float noiseGate = Math.max(3.0f, Math.min(18.0f, Math.max(accNow, accPrev) * 0.22f));\n                if (moved >= noiseGate) derived = moved / (dt / 1000.0) * 3.6;\n            }\n        }\n\n        double chosen;\n        if (Double.isFinite(sensor) && sensor >= 2.0) chosen = sensor;\n        else if (Double.isFinite(derived) && derived >= 2.0) chosen = derived;\n        else if (Double.isFinite(sensor)) chosen = sensor;\n        else if (Double.isFinite(derived)) chosen = derived;\n        else chosen = 0.0;\n\n        // Reject impossible GPS spikes. Preserve a recent moving value for one short\n        // zero sample so interleaved providers do not make the dashboard flash 0.\n        if (chosen > 260.0) chosen = lastSpeedKmh;\n        if (chosen < 1.0 && lastSpeedKmh >= 5.0 && dt > 0L && dt <= 3500L && moved >= 2f) {\n            chosen = lastSpeedKmh * 0.78;\n        }\n        if (chosen < 1.2) chosen = 0.0;\n        return chosen;\n    }\n'''
if old not in s:
    raise SystemExit('1.7.2 heading/speed anchor not found')
s = s.replace(old, new, 1)

old = '''    private void broadcastSynthetic(double lat, double lon, String status) {\n        sendBroadcast(baseBroadcast(lat, lon, 0.0, status));\n    }\n'''
new = '''    private void broadcastSynthetic(double lat, double lon, String status) {\n        // Coverage refresh is not a new GPS sample. Never overwrite a valid vehicle\n        // speed with zero just because a network/package operation finished.\n        sendBroadcast(baseBroadcast(lat, lon, lastSpeedKmh, status));\n    }\n'''
if old not in s:
    raise SystemExit('1.7.2 synthetic broadcast anchor not found')
s = s.replace(old, new, 1)

p.write_text(s, encoding='utf-8')

# RoadMapActivity: do not claim protection is ready when no hazard base exists.
p = app / 'app/src/main/java/com/estradaplay/app/RoadMapActivity.java'
s = p.read_text(encoding='utf-8')
old = '''            boolean hasHazard = hazard != null && !hazard.trim().isEmpty();\n            if (protectionText != null) protectionText.setText(hasHazard ? "ATENÇÃO À FRENTE" : "PROTEÇÃO ATIVA");\n            if (hazardTitle != null) hazardTitle.setText(hasHazard ? hazard.trim() : "Estrada livre à frente");\n            if (hazardDetail != null) {\n                String value;\n                if (hasHazard) {\n'''
new = '''            boolean hasHazard = hazard != null && !hazard.trim().isEmpty();\n            boolean hasAlertBase = count > 0;\n            if (protectionText != null) protectionText.setText(hasHazard ? "ATENÇÃO À FRENTE" : (hasAlertBase ? "PROTEÇÃO ATIVA" : "BASE DE ALERTAS VAZIA"));\n            if (hazardTitle != null) hazardTitle.setText(hasHazard ? hazard.trim() : (hasAlertBase ? "Estrada livre à frente" : "Sem radares carregados"));\n            if (hazardDetail != null) {\n                String value;\n                if (hasHazard) {\n'''
if old not in s:
    raise SystemExit('1.7.2 RoadMapActivity hazard state anchor not found')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

# 1.7.2 is built after the 1.7.1 touch transform.
gradle = app / 'app/build.gradle'
s = gradle.read_text(encoding='utf-8')
s = s.replace('versionCode 13', 'versionCode 14')
s = s.replace("versionName '1.7.1'", "versionName '1.7.2'")
if "versionName '1.7.2'" not in s or 'versionCode 14' not in s:
    raise SystemExit('1.7.2 version bump failed')
gradle.write_text(s, encoding='utf-8')

print('EstradaPlay 1.7.2 GPS speed + radar matching fix applied')
