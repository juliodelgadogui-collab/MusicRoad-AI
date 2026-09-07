package com.estradaplay.comunista;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

final class OfflineRoadStore {
    private static final long STATE_FRESH_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final long CORRIDOR_FRESH_MS = 30L * 60L * 60L * 1000L;
    private static final long MAX_AGE_MS = 180L * 24L * 60L * 60L * 1000L;

    // OFFLINE_NETWORK_V312: 30.000 km is a target length of road network kept in
    // offline regional packs, not a geographic radius around the vehicle.
    // The server/data pack decides the road-network content; this value is never shown in UI.
    private static final int OFFLINE_NETWORK_TARGET_KM = 30000;
    private static final int RESERVE_DISTANCE_M = 250000;
    private static final int RESERVE_WIDTH_M = 18000;
    private static final int MAX_CORRIDORS = 8;

    private final File dir;
    // ROAD_LIMIT_INDEX_V202: lightweight in-memory speed-limit segments, rebuilt only when map files change.
    private final ArrayList<SpeedSegment> speedSegments = new ArrayList<>();
    private long speedIndexRevision = Long.MIN_VALUE;
    private String speedIndexUf = "";

    OfflineRoadStore(android.content.Context context) {
        dir = new File(context.getApplicationContext().getFilesDir(), "offline_road_map");
        if (!dir.exists()) dir.mkdirs();
        cleanup();
    }

    boolean needsPreparation(double lat, double lon, float heading) {
        String uf = guessUfFast(lat, lon);
        if (supported(uf) && !fresh(stateFile(uf), STATE_FRESH_MS) && !hasRecentCorridorNear(lat, lon)) return true;
        return Float.isFinite(heading) && !hasFreshCorridor(lat, lon, heading);
    }

    boolean prepare(ApiClient api, double lat, double lon, float heading) {
        boolean ok = false;
        String uf = guessUfFast(lat, lon);
        if (api != null && supported(uf) && !fresh(stateFile(uf), STATE_FRESH_MS)) ok = fetchState(api, uf) || ok;
        if (api != null && Float.isFinite(heading) && !hasFreshCorridor(lat, lon, heading)) ok = fetchCorridor(api, lat, lon, heading) || ok;

        // Production downloads road geometry from Estrada Play. Public Overpass remains only as
        // legacy code and is not called automatically while the driver is travelling.
        cleanup();
        return ok;
    }

    String combinedGeoJson(double lat, double lon) {
        LinkedHashMap<String, JSONObject> unique = new LinkedHashMap<>();
        String uf = guessUfFast(lat, lon);
        if (supported(uf)) appendFeatures(stateFile(uf), unique, 12000);

        File[] files = corridorFiles();
        if (files != null) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            for (File f : files) {
                CorridorMeta meta = corridorMeta(f);
                if (meta == null) continue;
                if (RoadPackStore.distanceM(lat, lon, meta.lat, meta.lon) > 190000) continue;
                appendFeatures(f, unique, 9000);
                if (unique.size() >= 9000) break;
            }
        }

        JSONArray arr = new JSONArray();
        for (JSONObject feature : unique.values()) arr.put(feature);
        JSONObject collection = new JSONObject();
        try { collection.put("type", "FeatureCollection"); collection.put("features", arr); }
        catch (Throwable ignored) {}
        return collection.toString();
    }

    long revision() {
        long value = 17L;
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return value;
        for (File f : files) value = value * 31L + f.lastModified() + f.length();
        return value;
    }

    int packCount() {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        return files == null ? 0 : files.length;
    }

    String status(double lat, double lon) {
        String uf = guessUfFast(lat, lon);
        boolean state = supported(uf) && stateFile(uf).isFile();
        boolean corridor = hasRecentCorridorNear(lat, lon);
        return state || corridor ? "Proteção offline pronta" : "Proteção offline preparando";
    }

    synchronized int speedLimitAt(double lat, double lon, float heading) {
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

    private boolean fetchState(ApiClient api, String uf) {
        try {
            ApiClient.Response response = api.getLong("api/road_map_state.php?uf=" + uf);
            JSONObject json = response.json();
            JSONObject roads = json.optJSONObject("roads");
            if (!response.ok() || !json.optBoolean("ok", false) || roads == null || roads.optJSONArray("features") == null) return false;
            json.put("fetched_at", System.currentTimeMillis());
            JSONObject coverage = json.optJSONObject("coverage");
            if (coverage == null) { coverage = new JSONObject(); json.put("coverage", coverage); }
            coverage.put("target_network_km", OFFLINE_NETWORK_TARGET_KM);
            return writeJson(stateFile(uf), json);
        } catch (Throwable ignored) { return false; }
    }

    private boolean fetchCorridor(ApiClient api, double lat, double lon, float heading) {
        try {
            String path = String.format(Locale.US,
                    "api/road_map_corridor.php?lat=%.6f&lon=%.6f&heading=%.1f&distance=%d&width=%d",
                    lat, lon, heading, RESERVE_DISTANCE_M, RESERVE_WIDTH_M);
            ApiClient.Response response = api.getLong(path);
            JSONObject json = response.json();
            JSONObject roads = json.optJSONObject("roads");
            if (!response.ok() || !json.optBoolean("ok", false) || roads == null || roads.optJSONArray("features") == null) return false;
            json.put("fetched_at", System.currentTimeMillis());
            String key = corridorKey(lat, lon, heading);
            return writeJson(new File(dir, key + ".json"), json);
        } catch (Throwable ignored) { return false; }
    }

    // Local OSM fallback only; it does not define the total offline coverage target.
    private boolean fetchDirectLocalMap(double lat, double lon, float heading) {
        try {
            String query = String.format(Locale.US,
                    "[out:json][timeout:35];way(around:18000,%.6f,%.6f)[\"highway\"~\"motorway|trunk|primary|secondary|tertiary\"];out geom tags;",
                    lat, lon);
            String target = "https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(query, "UTF-8");
            HttpURLConnection c = (HttpURLConnection)new URL(target).openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(60000); c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "EstradaPlayComunista/1.0 Android");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) { c.disconnect(); return false; }
            JSONObject osm;
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[32768]; int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 10_000_000) throw new Exception("Mapa OSM grande demais");
                }
                osm = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            } finally { c.disconnect(); }

            JSONArray elements = osm.optJSONArray("elements");
            if (elements == null || elements.length() == 0) return false;
            JSONArray features = new JSONArray();
            for (int i = 0; i < elements.length() && features.length() < 2500; i++) {
                JSONObject e = elements.optJSONObject(i); if (e == null) continue;
                JSONArray geom = e.optJSONArray("geometry"); if (geom == null || geom.length() < 2) continue;
                JSONArray coords = new JSONArray();
                for (int j = 0; j < geom.length(); j++) {
                    JSONObject g = geom.optJSONObject(j); if (g == null) continue;
                    double glat = g.optDouble("lat", Double.NaN), glon = g.optDouble("lon", Double.NaN);
                    if (!Double.isFinite(glat) || !Double.isFinite(glon)) continue;
                    JSONArray xy = new JSONArray(); xy.put(glon); xy.put(glat); coords.put(xy);
                }
                if (coords.length() < 2) continue;
                JSONObject tags = e.optJSONObject("tags"); if (tags == null) tags = new JSONObject();
                JSONObject feature = new JSONObject();
                feature.put("type", "Feature"); feature.put("id", "osm-way-" + e.optLong("id", i));
                JSONObject geometry = new JSONObject(); geometry.put("type", "LineString"); geometry.put("coordinates", coords);
                feature.put("geometry", geometry);
                JSONObject props = new JSONObject();
                props.put("highway", tags.optString("highway", "road"));
                props.put("name", tags.optString("name", "")); props.put("ref", tags.optString("ref", ""));
                props.put("maxspeed", tags.optString("maxspeed", ""));
                props.put("maxspeed:forward", tags.optString("maxspeed:forward", ""));
                props.put("maxspeed:backward", tags.optString("maxspeed:backward", ""));
                feature.put("properties", props); features.put(feature);
            }
            if (features.length() == 0) return false;

            JSONObject root = new JSONObject();
            root.put("ok", true); root.put("kind", "local_map"); root.put("fetched_at", System.currentTimeMillis());
            JSONObject start = new JSONObject(); start.put("lat", lat); start.put("lon", lon);
            start.put("heading", Float.isFinite(heading) ? heading : 0.0); root.put("start", start);
            JSONObject roads = new JSONObject(); roads.put("type", "FeatureCollection"); roads.put("features", features); root.put("roads", roads);
            JSONObject coverage = new JSONObject(); coverage.put("source", "local"); coverage.put("features", features.length());
            root.put("coverage", coverage);
            String key = corridorKey(lat, lon, Float.isFinite(heading) ? heading : 0f);
            return writeJson(new File(dir, key + ".json"), root);
        } catch (Throwable ignored) { return false; }
    }

    // ANR_GUARD_V201: these methods run from the GPS/service path. Corridor position
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

    private void appendFeatures(File file, LinkedHashMap<String, JSONObject> out, int limit) {
        if (!file.isFile()) return;
        try { appendFeatures(new JSONObject(readText(file)), out, limit); }
        catch (Throwable ignored) {}
    }

    private void appendFeatures(JSONObject root, LinkedHashMap<String, JSONObject> out, int limit) {
        JSONObject roads = root.optJSONObject("roads"); if (roads == null) return;
        JSONArray features = roads.optJSONArray("features"); if (features == null) return;
        for (int i = 0; i < features.length() && out.size() < limit; i++) {
            JSONObject f = features.optJSONObject(i); if (f == null) continue;
            String id = f.optString("id", "");
            if (id.isEmpty()) id = "f-" + Integer.toHexString(f.toString().hashCode());
            out.put(id, f);
        }
    }

    private void cleanup() {
        File[] all = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (all == null) return;
        long now = System.currentTimeMillis();
        for (File f : all) if (now - f.lastModified() > MAX_AGE_MS) f.delete();
        File[] corridors = corridorFiles();
        if (corridors == null || corridors.length <= MAX_CORRIDORS) return;
        Arrays.sort(corridors, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < corridors.length - MAX_CORRIDORS; i++) corridors[i].delete();
    }

    private File[] corridorFiles() { return dir.listFiles((d, n) -> n.startsWith("corr_") && n.endsWith(".json")); }
    private File stateFile(String uf) { return new File(dir, "state_" + uf.toLowerCase(Locale.ROOT) + ".json"); }

    private static boolean fresh(File f, long age) { return f.isFile() && System.currentTimeMillis() - f.lastModified() <= age; }

    private static String corridorKey(double lat, double lon, float heading) {
        int hb = ((int)Math.round(heading / 30.0)) * 30 % 360;
        double glat = Math.round(lat / 0.30) * 0.30;
        double glon = Math.round(lon / 0.30) * 0.30;
        return String.format(Locale.US, "corr_%+.2f_%+.2f_%03d", glat, glon, hb).replace('+','p').replace('-','m').replace('.','_');
    }

    private static String guessUfFast(double lat,double lon){
        if(lat>=-23.45&&lat<=-20.65&&lon>=-44.95&&lon<=-40.75)return "RJ";
        if(lat>=-21.40&&lat<=-17.75&&lon>=-41.95&&lon<=-39.55)return "ES";
        if(lat>=-25.45&&lat<=-19.65&&lon>=-53.25&&lon<=-44.00)return "SP";
        if(lat>=-23.00&&lat<=-14.10&&lon>=-51.15&&lon<=-39.75)return "MG";
        return "";
    }

    private static boolean supported(String uf) { return "SP".equals(uf) || "RJ".equals(uf) || "MG".equals(uf) || "ES".equals(uf); }
    private static double angleDiff(double a,double b){double d=Math.abs(a-b)%360.0;return d>180.0?360.0-d:d;}

    private static boolean writeJson(File file, JSONObject json) {
        try {
            byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
            if (data.length > 35_000_000) return false;
            File tmp = new File(file.getParentFile(), file.getName() + ".part");
            try (FileOutputStream out = new FileOutputStream(tmp, false)) { out.write(data); out.flush(); }
            if (file.exists()) file.delete();
            if (!tmp.renameTo(file)) {
                try (FileOutputStream out = new FileOutputStream(file, false)) { out.write(data); out.flush(); }
                tmp.delete();
            }
            file.setLastModified(System.currentTimeMillis());
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private static String readText(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[32768]; int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 40_000_000) throw new IllegalStateException("Mapa offline grande demais");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
