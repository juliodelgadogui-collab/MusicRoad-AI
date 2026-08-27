from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "estradaplay-app-v2" / "app"
JAVA = APP / "src" / "main" / "java" / "com" / "estradaplay" / "app"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        if new in text:
            return text
        raise SystemExit(f"anchor not found: {label}")
    return text.replace(old, new, 1)

# Version
build = APP / "build.gradle"
s = build.read_text()
s = replace_once(s, "versionCode 200", "versionCode 201", "versionCode")
s = replace_once(s, "versionName '2.0.0'", "versionName '2.0.1'", "versionName")
build.write_text(s)

# Offline map: never parse multi-megabyte GeoJSON just to decide whether a corridor is nearby/fresh.
offline = JAVA / "OfflineRoadStore.java"
s = offline.read_text()
s = s.replace("// OSM_DIRECT_MAP_V200: local fallback (~30 km)", "// OSM_DIRECT_MAP_V200: local fallback (~18 km)")
s = s.replace('way(around:30000,%.6f,%.6f)', 'way(around:18000,%.6f,%.6f)')
s = s.replace('if (out.size() > 22_000_000)', 'if (out.size() > 10_000_000)')
s = s.replace('features.length() < 7000', 'features.length() < 2500')

old = '''            for (File f : files) {
                try {
                    JSONObject root = new JSONObject(readText(f));
                    JSONObject start = root.optJSONObject("start");
                    if (start == null) continue;
                    double slat = start.optDouble("lat", Double.NaN);
                    double slon = start.optDouble("lon", Double.NaN);
                    if (!Double.isFinite(slat) || !Double.isFinite(slon)) continue;
                    if (RoadPackStore.distanceM(lat, lon, slat, slon) > 190000) continue;
                    appendFeatures(root, unique, 18000);
                    if (unique.size() >= 18000) break;
                } catch (Throwable ignored) {}
            }
'''
new = '''            for (File f : files) {
                CorridorMeta meta = corridorMeta(f);
                if (meta == null) continue;
                if (RoadPackStore.distanceM(lat, lon, meta.lat, meta.lon) > 190000) continue;
                appendFeatures(f, unique, 9000);
                if (unique.size() >= 9000) break;
            }
'''
s = replace_once(s, old, new, "combinedGeoJson corridor scan")

old = '''    private boolean hasFreshCorridor(double lat, double lon, float heading) {
        File[] files = corridorFiles();
        if (files == null) return false;
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (now - f.lastModified() > CORRIDOR_FRESH_MS) continue;
            try {
                JSONObject j = new JSONObject(readText(f));
                JSONObject start = j.optJSONObject("start"); if (start == null) continue;
                double slat = start.optDouble("lat", Double.NaN), slon = start.optDouble("lon", Double.NaN);
                double sh = start.optDouble("heading", Double.NaN);
                if (!Double.isFinite(slat) || !Double.isFinite(slon) || !Double.isFinite(sh)) continue;
                if (RoadPackStore.distanceM(lat, lon, slat, slon) <= 45000 && angleDiff(heading, sh) <= 35.0) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private boolean hasRecentCorridorNear(double lat, double lon) {
        File[] files = corridorFiles();
        if (files == null) return false;
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (now - f.lastModified() > 10L * 24L * 60L * 60L * 1000L) continue;
            try {
                JSONObject j = new JSONObject(readText(f));
                JSONObject start = j.optJSONObject("start"); if (start == null) continue;
                double slat = start.optDouble("lat", Double.NaN), slon = start.optDouble("lon", Double.NaN);
                if (Double.isFinite(slat) && Double.isFinite(slon) && RoadPackStore.distanceM(lat, lon, slat, slon) <= 120000) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }
'''
new = '''    // ANR_GUARD_V201: these methods run from the GPS/service path. Corridor position
    // is encoded in the file name, so never read/parse the large road GeoJSON here.
    private boolean hasFreshCorridor(double lat, double lon, float heading) {
        File[] files = corridorFiles();
        if (files == null) return false;
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (now - f.lastModified() > CORRIDOR_FRESH_MS) continue;
            CorridorMeta meta = corridorMeta(f);
            if (meta == null) continue;
            if (RoadPackStore.distanceM(lat, lon, meta.lat, meta.lon) <= 52000 && angleDiff(heading, meta.heading) <= 45.0) return true;
        }
        return false;
    }

    private boolean hasRecentCorridorNear(double lat, double lon) {
        File[] files = corridorFiles();
        if (files == null) return false;
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (now - f.lastModified() > 10L * 24L * 60L * 60L * 1000L) continue;
            CorridorMeta meta = corridorMeta(f);
            if (meta != null && RoadPackStore.distanceM(lat, lon, meta.lat, meta.lon) <= 135000) return true;
        }
        return false;
    }

    private static CorridorMeta corridorMeta(File file) {
        if (file == null) return null;
        try {
            String n = file.getName();
            if (!n.startsWith("corr_") || !n.endsWith(".json")) return null;
            String core = n.substring(5, n.length() - 5);
            String[] p = core.split("_");
            if (p.length != 5) return null;
            double lat = decodeCoord(p[0], p[1]);
            double lon = decodeCoord(p[2], p[3]);
            double heading = Double.parseDouble(p[4]);
            if (!Double.isFinite(lat) || !Double.isFinite(lon) || !Double.isFinite(heading)) return null;
            return new CorridorMeta(lat, lon, heading);
        } catch (Throwable ignored) { return null; }
    }

    private static double decodeCoord(String whole, String fraction) {
        if (whole == null || whole.length() < 2) return Double.NaN;
        char sign = whole.charAt(0);
        double value = Double.parseDouble(whole.substring(1) + "." + fraction);
        return sign == 'm' ? -value : value;
    }

    private static final class CorridorMeta {
        final double lat, lon, heading;
        CorridorMeta(double lat, double lon, double heading) { this.lat = lat; this.lon = lon; this.heading = heading; }
    }
'''
s = replace_once(s, old, new, "lightweight corridor metadata")
offline.write_text(s)

# Road safety service: throttle coverage/status work on the main service thread.
service = JAVA / "RoadSafetyService.java"
s = service.read_text()
old = '''    private boolean stationaryConfirmed;
    private long lastNotificationAt;
'''
new = '''    private boolean stationaryConfirmed;
    private long lastNotificationAt;
    // ANR_GUARD_V201: keep repetitive disk/status/coverage work out of the 1 Hz GPS hot path.
    private long lastCoverageCheckAt;
    private long lastStateRefreshAt;
    private String lastStateText = "GPS ativo · preparando proteção";
'''
s = replace_once(s, old, new, "service ANR fields")

old = '''        } else {
            long now = System.currentTimeMillis();
            String state = packs.hasAnyCoverage(loc.getLatitude(), loc.getLongitude())
                    ? packs.status(loc.getLatitude(), loc.getLongitude()) + " · " + mapRoads.status(loc.getLatitude(), loc.getLongitude())
                    : (fetching.get() ? "Preparando alertas e mapa offline…" : "Aguardando proteção offline desta região");
            if (now - lastNotificationAt > 7000L) {
                updateNotification("Proteção na estrada ativa", state, false);
            }
'''
new = '''        } else {
            long now = System.currentTimeMillis();
            if (now - lastStateRefreshAt >= 5000L || lastStateText == null || lastStateText.isEmpty()) {
                lastStateText = packs.hasAnyCoverage(loc.getLatitude(), loc.getLongitude())
                        ? packs.status(loc.getLatitude(), loc.getLongitude()) + " · " + mapRoads.status(loc.getLatitude(), loc.getLongitude())
                        : (fetching.get() ? "Preparando alertas e mapa offline…" : "Aguardando proteção offline desta região");
                lastStateRefreshAt = now;
            }
            String state = lastStateText;
            if (now - lastNotificationAt > 7000L) {
                updateNotification("Proteção na estrada ativa", state, false);
            }
'''
s = replace_once(s, old, new, "throttled state status")

old = '''    private void ensureCoverage(double lat, double lon, float heading) {
        boolean alertNeeds = packs.needsPreparation(lat, lon, heading);
'''
new = '''    private void ensureCoverage(double lat, double lon, float heading) {
        long now = System.currentTimeMillis();
        if (now - lastCoverageCheckAt < 5000L) return;
        lastCoverageCheckAt = now;
        boolean alertNeeds = packs.needsPreparation(lat, lon, heading);
'''
s = replace_once(s, old, new, "coverage throttle")
service.write_text(s)

# Remove a harmless duplicate import while touching the consolidated source.
pack = JAVA / "RoadPackStore.java"
s = pack.read_text()
s = s.replace("import java.net.URLEncoder;\nimport java.io.InputStream;\nimport java.net.URLEncoder;\n", "import java.net.URLEncoder;\nimport java.io.InputStream;\n")
pack.write_text(s)

print("EstradaPlay 2.0.1 ANR hotfix applied to definitive v2 source")
