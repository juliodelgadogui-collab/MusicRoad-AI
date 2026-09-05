from pathlib import Path

root = Path('estradaplay-app-v2')
service_path = root / 'app/src/main/java/com/estradaplay/app/RoadSafetyService.java'
map_path = root / 'app/src/main/java/com/estradaplay/app/OfflineRoadStore.java'
gradle_path = root / 'app/build.gradle'

service = service_path.read_text(encoding='utf-8')
roadmap = map_path.read_text(encoding='utf-8')
gradle = gradle_path.read_text(encoding='utf-8')

if 'ROAD_LIMIT_V202' not in service:
    service = service.replace(
        '    private final ExecutorService io = Executors.newSingleThreadExecutor();\n',
        '    private final ExecutorService io = Executors.newSingleThreadExecutor();\n'
        '    private final ExecutorService limitIo = Executors.newSingleThreadExecutor();\n'
    )

    service = service.replace(
        '    private String lastStateText = "GPS ativo · preparando proteção";\n',
        '    private String lastStateText = "GPS ativo · preparando proteção";\n'
        '    // ROAD_LIMIT_V202: announce road limit changes, radar limits and one-shot overspeed.\n'
        '    private final AtomicBoolean roadLimitResolving = new AtomicBoolean(false);\n'
        '    private volatile int currentRoadLimitKmh;\n'
        '    private int announcedRoadLimitKmh;\n'
        '    private boolean roadOverspeedWarned;\n'
        '    private long lastRoadLimitCheckAt;\n'
    )

    service = service.replace(
        '        previous = new Location(loc);\n\n        ensureCoverage(loc.getLatitude(), loc.getLongitude(), heading);',
        '        previous = new Location(loc);\n\n'
        '        maybeResolveRoadLimit(loc.getLatitude(), loc.getLongitude(), heading);\n'
        '        evaluateRoadLimit(speedKmh);\n'
        '        ensureCoverage(loc.getLatitude(), loc.getLongitude(), heading);'
    )

    anchor = '    private void ensureCoverage(double lat, double lon, float heading) {\n'
    methods = '''    private void maybeResolveRoadLimit(double lat, double lon, float heading) {\n        long now = System.currentTimeMillis();\n        if (now - lastRoadLimitCheckAt < 7000L || roadLimitResolving.get()) return;\n        lastRoadLimitCheckAt = now;\n        if (!roadLimitResolving.compareAndSet(false, true)) return;\n        limitIo.execute(() -> {\n            int limit = 0;\n            try {\n                if (mapRoads != null) limit = mapRoads.speedLimitAt(lat, lon, heading);\n            } catch (Throwable ignored) {}\n            final int resolved = limit;\n            main.post(() -> applyRoadLimit(resolved));\n            roadLimitResolving.set(false);\n        });\n    }\n\n    private void applyRoadLimit(int limitKmh) {\n        if (limitKmh < 10 || limitKmh > 180) return;\n        boolean changed = currentRoadLimitKmh != limitKmh;\n        currentRoadLimitKmh = limitKmh;\n        if (changed) roadOverspeedWarned = false;\n        if (limitKmh != announcedRoadLimitKmh && ttsReady) {\n            announcedRoadLimitKmh = limitKmh;\n            speak("Limite da via, " + limitKmh + " quilômetros por hora.");\n        }\n    }\n\n    private void evaluateRoadLimit(double speedKmh) {\n        int limit = currentRoadLimitKmh;\n        if (limit <= 0 || !Double.isFinite(speedKmh)) return;\n        if (speedKmh <= limit) {\n            roadOverspeedWarned = false;\n            return;\n        }\n        // Small GPS tolerance prevents a 60/61 oscillation from becoming a false warning.\n        if (!roadOverspeedWarned && speedKmh >= limit + 2.0 && ttsReady) {\n            roadOverspeedWarned = true;\n            speak("Atenção. Você passou do limite da via. Limite de " + limit + " quilômetros por hora.");\n        }\n    }\n\n'''
    if anchor not in service:
        raise SystemExit('RoadSafetyService ensureCoverage anchor not found')
    service = service.replace(anchor, methods + anchor, 1)

    service = service.replace(
        'if (h.speed > 0) return "Radar à frente, a " + distance + ". Limite de " + h.speed + " quilômetros por hora.";',
        'if (h.speed > 0) return "Radar à frente, a " + distance + ". Limite do radar, " + h.speed + " quilômetros por hora.";'
    )

    service = service.replace(
        '            i.putExtra("limit_kmh", h.speed);\n',
        '            i.putExtra("limit_kmh", h.speed);\n'
        '            i.putExtra("radar_limit_kmh", h.speed);\n'
    )
    service = service.replace(
        '        i.putExtra("speed_kmh", speedKmh);\n',
        '        i.putExtra("speed_kmh", speedKmh);\n'
        '        i.putExtra("road_limit_kmh", currentRoadLimitKmh);\n'
    )
    service = service.replace(
        '        io.shutdownNow();\n        super.onDestroy();',
        '        io.shutdownNow();\n        limitIo.shutdownNow();\n        super.onDestroy();'
    )

if 'ROAD_LIMIT_INDEX_V202' not in roadmap:
    roadmap = roadmap.replace(
        '    private final File dir;\n',
        '    private final File dir;\n'
        '    // ROAD_LIMIT_INDEX_V202: lightweight in-memory speed-limit segments, rebuilt only when map files change.\n'
        '    private final ArrayList<SpeedSegment> speedSegments = new ArrayList<>();\n'
        '    private long speedIndexRevision = Long.MIN_VALUE;\n'
        '    private String speedIndexUf = "";\n'
    )

    roadmap = roadmap.replace(
        '                props.put("highway", tags.optString("highway", "road"));\n'
        '                props.put("name", tags.optString("name", "")); props.put("ref", tags.optString("ref", ""));\n',
        '                props.put("highway", tags.optString("highway", "road"));\n'
        '                props.put("name", tags.optString("name", "")); props.put("ref", tags.optString("ref", ""));\n'
        '                props.put("maxspeed", tags.optString("maxspeed", ""));\n'
        '                props.put("maxspeed:forward", tags.optString("maxspeed:forward", ""));\n'
        '                props.put("maxspeed:backward", tags.optString("maxspeed:backward", ""));\n'
    )

    anchor = '    private boolean fetchState(ApiClient api, String uf) {\n'
    methods = r'''    synchronized int speedLimitAt(double lat, double lon, float heading) {
        String uf = guessUfFast(lat, lon);
        long rev = revision();
        if (rev != speedIndexRevision || !uf.equals(speedIndexUf)) {
            rebuildSpeedIndex(lat, lon, uf, rev);
        }
        double best = Double.MAX_VALUE;
        int limit = 0;
        for (SpeedSegment s : speedSegments) {
            if (Math.abs(s.lat1 - lat) > 0.004 || Math.abs(s.lon1 - lon) > 0.004 ||
                    Math.abs(s.lat2 - lat) > 0.004 || Math.abs(s.lon2 - lon) > 0.004) continue;
            if (Float.isFinite(heading)) {
                double segHeading = bearing(s.lat1, s.lon1, s.lat2, s.lon2);
                double diff = Math.min(angleDiff(heading, segHeading), angleDiff(heading, (segHeading + 180.0) % 360.0));
                if (diff > 65.0) continue;
            }
            double d = pointSegmentDistanceM(lat, lon, s.lat1, s.lon1, s.lat2, s.lon2);
            if (d < best) { best = d; limit = s.limitKmh; }
        }
        return best <= 55.0 ? limit : 0;
    }

    private void rebuildSpeedIndex(double lat, double lon, String uf, long rev) {
        speedSegments.clear();
        File[] files = corridorFiles();
        if (files != null) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            int used = 0;
            for (File f : files) {
                CorridorMeta meta = corridorMeta(f);
                if (meta == null || RoadPackStore.distanceM(lat, lon, meta.lat, meta.lon) > 75000) continue;
                appendSpeedSegments(f, lat, lon, 5000);
                if (++used >= 3 || speedSegments.size() >= 18000) break;
            }
        }
        speedIndexUf = uf == null ? "" : uf;
        speedIndexRevision = rev;
    }

    private void appendSpeedSegments(File file, double lat, double lon, int maxSegments) {
        if (file == null || !file.isFile() || speedSegments.size() >= maxSegments) return;
        try {
            JSONObject root = new JSONObject(readText(file));
            JSONObject roads = root.optJSONObject("roads"); if (roads == null) return;
            JSONArray features = roads.optJSONArray("features"); if (features == null) return;
            for (int i = 0; i < features.length() && speedSegments.size() < maxSegments; i++) {
                JSONObject f = features.optJSONObject(i); if (f == null) continue;
                JSONObject props = f.optJSONObject("properties"); if (props == null) props = new JSONObject();
                int limit = speedFromProperties(props);
                if (limit <= 0) continue;
                JSONObject geometry = f.optJSONObject("geometry"); if (geometry == null) continue;
                if (!"LineString".equalsIgnoreCase(geometry.optString("type", ""))) continue;
                JSONArray coords = geometry.optJSONArray("coordinates"); if (coords == null || coords.length() < 2) continue;
                for (int j = 1; j < coords.length() && speedSegments.size() < maxSegments; j++) {
                    JSONArray a = coords.optJSONArray(j - 1), b = coords.optJSONArray(j);
                    if (a == null || b == null || a.length() < 2 || b.length() < 2) continue;
                    double lon1 = a.optDouble(0, Double.NaN), lat1 = a.optDouble(1, Double.NaN);
                    double lon2 = b.optDouble(0, Double.NaN), lat2 = b.optDouble(1, Double.NaN);
                    if (!Double.isFinite(lat1) || !Double.isFinite(lon1) || !Double.isFinite(lat2) || !Double.isFinite(lon2)) continue;
                    double midLat = (lat1 + lat2) * 0.5, midLon = (lon1 + lon2) * 0.5;
                    if (RoadPackStore.distanceM(lat, lon, midLat, midLon) > 45000) continue;
                    speedSegments.add(new SpeedSegment(lat1, lon1, lat2, lon2, limit));
                }
            }
        } catch (Throwable ignored) {}
    }

    private static int speedFromProperties(JSONObject p) {
        String[] keys = {"maxspeed", "maxspeed:forward", "maxspeed:backward", "max_speed", "speed_limit", "speed_kmh", "limit"};
        for (String key : keys) {
            int v = parseSpeedLimit(p.optString(key, ""));
            if (v > 0) return v;
        }
        return 0;
    }

    private static int parseSpeedLimit(String raw) {
        if (raw == null) return 0;
        String t = raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
            else if (digits.length() > 0) break;
        }
        if (digits.length() == 0) return 0;
        try {
            int v = Integer.parseInt(digits.toString());
            if (t.contains("mph")) v = (int)Math.round(v * 1.609344);
            return v >= 10 && v <= 180 ? v : 0;
        } catch (Throwable ignored) { return 0; }
    }

    private static double pointSegmentDistanceM(double lat, double lon, double lat1, double lon1, double lat2, double lon2) {
        double cos = Math.max(0.25, Math.cos(Math.toRadians(lat)));
        double x1 = (lon1 - lon) * 111320.0 * cos, y1 = (lat1 - lat) * 110540.0;
        double x2 = (lon2 - lon) * 111320.0 * cos, y2 = (lat2 - lat) * 110540.0;
        double dx = x2 - x1, dy = y2 - y1;
        double den = dx * dx + dy * dy;
        double t = den <= 0.0001 ? 0.0 : -(x1 * dx + y1 * dy) / den;
        t = Math.max(0.0, Math.min(1.0, t));
        double x = x1 + t * dx, y = y1 + t * dy;
        return Math.hypot(x, y);
    }

    private static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        double b = Math.toDegrees(Math.atan2(y, x));
        return (b + 360.0) % 360.0;
    }

    private static final class SpeedSegment {
        final double lat1, lon1, lat2, lon2;
        final int limitKmh;
        SpeedSegment(double lat1, double lon1, double lat2, double lon2, int limitKmh) {
            this.lat1 = lat1; this.lon1 = lon1; this.lat2 = lat2; this.lon2 = lon2; this.limitKmh = limitKmh;
        }
    }

'''
    if anchor not in roadmap:
        raise SystemExit('OfflineRoadStore fetchState anchor not found')
    roadmap = roadmap.replace(anchor, methods + anchor, 1)

# version bump only after source patch succeeds
gradle = gradle.replace("versionCode 201", "versionCode 202")
gradle = gradle.replace("versionName '2.0.1'", "versionName '2.0.2'")

if 'ROAD_LIMIT_V202' not in service or 'ROAD_LIMIT_INDEX_V202' not in roadmap:
    raise SystemExit('2.0.2 markers missing after patch')
if "versionName '2.0.2'" not in gradle or 'versionCode 202' not in gradle:
    raise SystemExit('2.0.2 version bump failed')

service_path.write_text(service, encoding='utf-8')
map_path.write_text(roadmap, encoding='utf-8')
gradle_path.write_text(gradle, encoding='utf-8')
print('EstradaPlay 2.0.2 road-limit voice patch applied')
