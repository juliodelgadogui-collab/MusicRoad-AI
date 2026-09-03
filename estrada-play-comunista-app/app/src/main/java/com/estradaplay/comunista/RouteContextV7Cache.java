package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;

/** CONTEXTO_VIVO_V220: small persisted snapshot shared by cockpit/HUD/background bridge. */
final class RouteContextV7Cache {
    private static final String PREFS = "estradaplay_context_v7_cache_v220";

    private RouteContextV7Cache() {}

    static void save(Context context, RouteContextV7Client.Snapshot s) {
        if (context == null || s == null) return;
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("received_at", s.receivedAt)
                .putString("version", s.version)
                .putString("weather", s.weatherText)
                .putBoolean("rain_soon", s.rainSoon)
                .putString("attention_kind", s.attentionKind)
                .putString("attention_title", s.attentionTitle)
                .putString("attention_detail", s.attentionDetail)
                .putLong("attention_ahead_cm", Math.round(s.attentionAheadM * 100.0))
                .putString("traffic", s.trafficText)
                .putString("fuel", s.fuelText)
                .putBoolean("traffic_opt_in", s.trafficOptIn)
                .putBoolean("traffic_stored", s.trafficStored)
                .putInt("hazard_count", s.hazardCount)
                .putInt("live_count", s.liveCount)
                .putInt("traffic_count", s.trafficCount)
                .apply();
    }

    static RouteContextV7Client.Snapshot load(Context context) {
        if (context == null) return null;
        SharedPreferences p = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long receivedAt = p.getLong("received_at", 0L);
        if (receivedAt <= 0L) return null;
        return new RouteContextV7Client.Snapshot(
                receivedAt,
                p.getString("version", "7.0"),
                p.getString("weather", ""),
                p.getBoolean("rain_soon", false),
                p.getString("attention_kind", ""),
                p.getString("attention_title", ""),
                p.getString("attention_detail", ""),
                p.getLong("attention_ahead_cm", 0L) / 100.0,
                p.getString("traffic", ""),
                p.getString("fuel", ""),
                p.getBoolean("traffic_opt_in", false),
                p.getBoolean("traffic_stored", false),
                p.getInt("hazard_count", 0),
                p.getInt("live_count", 0),
                p.getInt("traffic_count", 0));
    }

    static void clear(Context context) {
        if (context == null) return;
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
