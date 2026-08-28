package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

/** Small local trip recorder. No route history is uploaded to the server. */
final class TripRecorder {
    private static final String PREFS = "epc_trip_history_v140";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_ACTIVE = "active";
    private static final long IDLE_FINISH_MS = 5L * 60L * 1000L;

    private final SharedPreferences prefs;
    private JSONObject active;
    private Location last;
    private long lastMovingAt;
    private long lastPersistAt;

    TripRecorder(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            String raw = prefs.getString(KEY_ACTIVE, "");
            if (raw != null && !raw.isEmpty()) active = new JSONObject(raw);
        } catch (Throwable ignored) { active = null; }
    }

    synchronized void onLocation(Location loc, double speedKmh) {
        if (loc == null) return;
        long now = System.currentTimeMillis();
        if (speedKmh >= 4.0) {
            if (active == null) start(now, loc);
            lastMovingAt = now;
        } else if (active != null && lastMovingAt > 0 && now - lastMovingAt >= IDLE_FINISH_MS) {
            finish("parada");
            last = new Location(loc);
            return;
        }
        if (active == null) { last = new Location(loc); return; }
        try {
            double add = 0;
            if (last != null) {
                float d = last.distanceTo(loc);
                if (d >= 0 && d <= 1500f) add = d;
            }
            active.put("distance_m", active.optDouble("distance_m", 0) + add);
            active.put("max_speed", Math.max(active.optDouble("max_speed", 0), Math.max(0, speedKmh)));
            active.put("last_lat", loc.getLatitude());
            active.put("last_lon", loc.getLongitude());
            active.put("updated_at", now);
            if (now - lastPersistAt >= 12000L) persistActive();
        } catch (Throwable ignored) {}
        last = new Location(loc);
    }

    synchronized void onHazard(String type) {
        if (active == null) return;
        try {
            if ("RADAR".equals(type)) active.put("radars", active.optInt("radars",0)+1);
            else if ("QUEBRA_MOLAS".equals(type)) active.put("bumps", active.optInt("bumps",0)+1);
            else if ("CAMERA_MONITORAMENTO".equals(type)) active.put("cameras", active.optInt("cameras",0)+1);
            else active.put("other_alerts", active.optInt("other_alerts",0)+1);
            persistActive();
        } catch (Throwable ignored) {}
    }

    synchronized void finish(String reason) {
        if (active == null) return;
        try {
            long now = System.currentTimeMillis();
            active.put("ended_at", now);
            active.put("duration_ms", Math.max(0, now - active.optLong("started_at", now)));
            active.put("reason", reason == null ? "" : reason);
            JSONArray old = history(prefs);
            JSONArray out = new JSONArray();
            out.put(active);
            for (int i=0; i<old.length() && out.length()<30; i++) out.put(old.opt(i));
            prefs.edit().putString(KEY_HISTORY, out.toString()).remove(KEY_ACTIVE).apply();
        } catch (Throwable ignored) {}
        active = null;
        last = null;
        lastMovingAt = 0;
    }

    private void start(long now, Location loc) {
        active = new JSONObject();
        try {
            active.put("started_at", now);
            active.put("updated_at", now);
            active.put("start_lat", loc.getLatitude());
            active.put("start_lon", loc.getLongitude());
            active.put("distance_m", 0);
            active.put("max_speed", 0);
            active.put("radars", 0);
            active.put("bumps", 0);
            active.put("cameras", 0);
            active.put("other_alerts", 0);
        } catch (Throwable ignored) {}
        lastMovingAt = now;
        persistActive();
    }

    private void persistActive() {
        lastPersistAt = System.currentTimeMillis();
        if (active != null) prefs.edit().putString(KEY_ACTIVE, active.toString()).apply();
    }

    static JSONArray history(Context context) {
        return history(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    private static JSONArray history(SharedPreferences prefs) {
        try { return new JSONArray(prefs.getString(KEY_HISTORY, "[]")); }
        catch (Throwable ignored) { return new JSONArray(); }
    }

    static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_HISTORY).apply();
    }
}
