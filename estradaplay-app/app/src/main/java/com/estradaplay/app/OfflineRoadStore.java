package com.estradaplay.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
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
    private static final int RESERVE_DISTANCE_M = 250000;
    private static final int RESERVE_WIDTH_M = 18000;
    private static final int MAX_CORRIDORS = 8;

    private final File dir;

    OfflineRoadStore(android.content.Context context) {
        dir = new File(context.getApplicationContext().getFilesDir(), "offline_road_map");
        if (!dir.exists()) dir.mkdirs();
        cleanup();
    }

    boolean needsPreparation(double lat, double lon, float heading) {
        String uf = guessUfFast(lat, lon);
        if (supported(uf) && !fresh(stateFile(uf), STATE_FRESH_MS)) return true;
        return Float.isFinite(heading) && !hasFreshCorridor(lat, lon, heading);
    }

    boolean prepare(ApiClient api, double lat, double lon, float heading) {
        if (api == null || api.cookie() == null || api.cookie().trim().isEmpty()) return false;
        boolean ok = false;
        String uf = guessUfFast(lat, lon);
        if (supported(uf) && !fresh(stateFile(uf), STATE_FRESH_MS)) ok = fetchState(api, uf) || ok;
        if (Float.isFinite(heading) && !hasFreshCorridor(lat, lon, heading)) ok = fetchCorridor(api, lat, lon, heading) || ok;
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
        if (state && corridor) return "Mapa livre offline · " + uf + " + corredor 250 km";
        if (state) return "Mapa livre offline · base " + uf;
        if (corridor) return "Mapa livre offline · corredor preparado";
        return "Mapa livre · aguardando pacote offline";
    }

    private boolean fetchState(ApiClient api, String uf) {
        try {
            ApiClient.Response response = api.getLong("api/road_map_state.php?uf=" + uf);
            JSONObject json = response.json();
            JSONObject roads = json.optJSONObject("roads");
            if (!response.ok() || !json.optBoolean("ok", false) || roads == null || roads.optJSONArray("features") == null) return false;
            json.put("fetched_at", System.currentTimeMillis());
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

    private boolean hasFreshCorridor(double lat, double lon, float heading) {
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
