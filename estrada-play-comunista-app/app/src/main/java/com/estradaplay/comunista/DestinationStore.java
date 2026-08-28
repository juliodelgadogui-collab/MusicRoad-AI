package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;

final class DestinationStore {
    private static final String PREFS = "epc_destination_v1";

    static final class Destination {
        final String label;
        final double lat;
        final double lon;

        Destination(String label, double lat, double lon) {
            this.label = label == null ? "Destino" : label.trim();
            this.lat = lat;
            this.lon = lon;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void save(Context c, Destination d) {
        if (d == null || !Double.isFinite(d.lat) || !Double.isFinite(d.lon)) return;
        prefs(c).edit()
                .putBoolean("active", true)
                .putString("label", d.label)
                .putLong("lat_bits", Double.doubleToLongBits(d.lat))
                .putLong("lon_bits", Double.doubleToLongBits(d.lon))
                .apply();
    }

    static Destination read(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("active", false)) return null;
        double lat = Double.longBitsToDouble(p.getLong("lat_bits", Double.doubleToLongBits(Double.NaN)));
        double lon = Double.longBitsToDouble(p.getLong("lon_bits", Double.doubleToLongBits(Double.NaN)));
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) return null;
        return new Destination(p.getString("label", "Destino"), lat, lon);
    }

    static void clear(Context c) {
        prefs(c).edit().clear().apply();
    }

    static boolean same(Destination a, Destination b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return Math.abs(a.lat - b.lat) < 0.000001 && Math.abs(a.lon - b.lon) < 0.000001 && a.label.equals(b.label);
    }
}
