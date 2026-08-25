package com.estradaplay.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class RoadPackStore {
    private static final long FRESH_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final long MAX_AGE_MS = 45L * 24L * 60L * 60L * 1000L;
    private static final int PACK_RADIUS_M = 16000;
    private static final double GRID_DEG = 0.12;
    private static final double CELL_DEG = 0.01;
    private static final int MAX_PACKS = 14;

    private final Context app;
    private final File dir;
    private final ArrayList<Pack> packs = new ArrayList<>();

    RoadPackStore(Context context) {
        app = context.getApplicationContext();
        dir = new File(app.getFilesDir(), "road_safety_packs");
        if (!dir.exists()) dir.mkdirs();
        loadDisk();
    }

    synchronized int packCount() { return packs.size(); }

    synchronized int hazardCount() {
        LinkedHashMap<String, RoadHazard> unique = new LinkedHashMap<>();
        for (Pack p : packs) for (RoadHazard h : p.hazards) unique.put(h.id, h);
        return unique.size();
    }

    synchronized boolean hasAnyCoverage(double lat, double lon) {
        for (Pack p : packs) if (distanceM(lat, lon, p.lat, p.lon) <= p.radiusM * 0.92) return true;
        return false;
    }

    synchronized boolean hasFreshCoreCoverage(double lat, double lon) {
        long now = System.currentTimeMillis();
        for (Pack p : packs) {
            if (now - p.fetchedAt > FRESH_MS) continue;
            if (distanceM(lat, lon, p.lat, p.lon) <= p.radiusM * 0.58) return true;
        }
        return false;
    }

    synchronized String status(double lat, double lon) {
        if (packs.isEmpty()) return "Baixando alertas da região…";
        int nearby = nearby(lat, lon, 2400).size();
        return "Offline · " + packCount() + " área(s) · " + hazardCount() + " pontos" + (nearby > 0 ? " · " + nearby + " próximos" : "");
    }

    boolean fetchCoverage(ApiClient api, double lat, double lon) {
        if (api == null || api.cookie() == null || api.cookie().trim().isEmpty()) return false;
        if (hasFreshCoreCoverage(lat, lon)) return true;
        double centerLat = Math.round(lat / GRID_DEG) * GRID_DEG;
        double centerLon = Math.round(lon / GRID_DEG) * GRID_DEG;
        String key = packKey(centerLat, centerLon);
        synchronized (this) {
            Pack existing = findByKey(key);
            if (existing != null && System.currentTimeMillis() - existing.fetchedAt <= FRESH_MS) return true;
        }
        try {
            String path = String.format(Locale.US, "api/road_pack.php?lat=%.6f&lon=%.6f&radius=%d", centerLat, centerLon, PACK_RADIUS_M);
            ApiClient.Response response = api.get(path);
            JSONObject json = response.json();
            JSONArray hazards = json.optJSONArray("hazards");
            if (!response.ok() || !json.optBoolean("ok", false) || hazards == null) return false;
            JSONObject stored = new JSONObject();
            stored.put("key", key);
            stored.put("center_lat", centerLat);
            stored.put("center_lon", centerLon);
            stored.put("radius_m", json.optInt("radius_m", PACK_RADIUS_M));
            stored.put("fetched_at", System.currentTimeMillis());
            stored.put("hazards", hazards);
            stored.put("coverage", json.optJSONObject("coverage"));
            File target = new File(dir, key + ".json");
            writeText(target, stored.toString());
            Pack p = parsePack(target, stored);
            if (p == null) return false;
            synchronized (this) {
                Pack old = findByKey(key);
                if (old != null) packs.remove(old);
                packs.add(p);
                cleanupLocked();
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    synchronized List<RoadHazard> nearby(double lat, double lon, double maxMeters) {
        LinkedHashMap<String, RoadHazard> out = new LinkedHashMap<>();
        int baseY = cell(lat), baseX = cell(lon);
        int span = Math.max(1, (int)Math.ceil(maxMeters / 900.0));
        for (Pack p : packs) {
            if (distanceM(lat, lon, p.lat, p.lon) > p.radiusM + maxMeters + 1500) continue;
            for (int y = baseY - span; y <= baseY + span; y++) {
                for (int x = baseX - span; x <= baseX + span; x++) {
                    List<RoadHazard> bucket = p.index.get(cellKey(y, x));
                    if (bucket == null) continue;
                    for (RoadHazard h : bucket) {
                        if (distanceM(lat, lon, h.lat, h.lon) <= maxMeters) out.put(h.id, h);
                    }
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    private synchronized Pack findByKey(String key) {
        for (Pack p : packs) if (p.key.equals(key)) return p;
        return null;
    }

    private void loadDisk() {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File f : files) {
            try {
                JSONObject json = new JSONObject(readText(f));
                long fetched = json.optLong("fetched_at", f.lastModified());
                if (now - fetched > MAX_AGE_MS) { f.delete(); continue; }
                Pack p = parsePack(f, json);
                if (p != null) packs.add(p);
            } catch (Exception ignored) {}
        }
        synchronized (this) { cleanupLocked(); }
    }

    private Pack parsePack(File file, JSONObject json) {
        try {
            String key = json.optString("key", file.getName().replace(".json", ""));
            double lat = json.optDouble("center_lat", Double.NaN);
            double lon = json.optDouble("center_lon", Double.NaN);
            int radius = json.optInt("radius_m", PACK_RADIUS_M);
            long fetched = json.optLong("fetched_at", file.lastModified());
            JSONArray arr = json.optJSONArray("hazards");
            if (!Double.isFinite(lat) || !Double.isFinite(lon) || arr == null) return null;
            ArrayList<RoadHazard> hazards = new ArrayList<>();
            HashMap<Long, List<RoadHazard>> index = new HashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                RoadHazard h = RoadHazard.fromJson(arr.optJSONObject(i));
                if (h == null) continue;
                hazards.add(h);
                long ck = cellKey(cell(h.lat), cell(h.lon));
                List<RoadHazard> bucket = index.get(ck);
                if (bucket == null) { bucket = new ArrayList<>(); index.put(ck, bucket); }
                bucket.add(h);
            }
            return new Pack(key, lat, lon, radius, fetched, file, hazards, index);
        } catch (Exception e) {
            return null;
        }
    }

    private static String readText(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 6_000_000) throw new IllegalStateException("Pacote grande demais");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File file, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(data); out.flush();
        }
    }

    private void cleanupLocked() {
        long now = System.currentTimeMillis();
        ArrayList<Pack> stale = new ArrayList<>();
        for (Pack p : packs) if (now - p.fetchedAt > MAX_AGE_MS) stale.add(p);
        for (Pack p : stale) { packs.remove(p); p.file.delete(); }
        if (packs.size() <= MAX_PACKS) return;
        Collections.sort(packs, Comparator.comparingLong(p -> p.fetchedAt));
        while (packs.size() > MAX_PACKS) {
            Pack p = packs.remove(0);
            p.file.delete();
        }
    }

    private static int cell(double v) { return (int)Math.floor(v / CELL_DEG); }
    private static long cellKey(int y, int x) { return (((long)y) << 32) ^ (x & 0xffffffffL); }
    private static String packKey(double lat, double lon) {
        return String.format(Locale.US, "p_%+.3f_%+.3f", lat, lon).replace('+','p').replace('-','m').replace('.','_');
    }

    static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2-lat1), dl = Math.toRadians(lon2-lon1);
        double a = Math.sin(dp/2)*Math.sin(dp/2) + Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)*Math.sin(dl/2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1.0-a)));
    }

    private static final class Pack {
        final String key;
        final double lat, lon;
        final int radiusM;
        final long fetchedAt;
        final File file;
        final List<RoadHazard> hazards;
        final Map<Long, List<RoadHazard>> index;
        Pack(String key, double lat, double lon, int radiusM, long fetchedAt, File file, List<RoadHazard> hazards, Map<Long,List<RoadHazard>> index) {
            this.key=key; this.lat=lat; this.lon=lon; this.radiusM=radiusM; this.fetchedAt=fetchedAt; this.file=file; this.hazards=hazards; this.index=index;
        }
    }
}
