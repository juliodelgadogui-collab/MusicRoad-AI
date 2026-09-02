package com.estradaplay.comunista;

import android.content.Context;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class KnownRoadCatalog {
    static final String[] PRESET_ROADS = {
            "BR-101", "BR-040", "BR-116", "BR-120", "BR-251", "BR-259", "BR-262", "BR-265", "BR-267", "BR-356", "BR-381", "BR-393", "BR-482", "BR-491",
            "RJ-106", "RJ-116", "RJ-124", "RJ-158", "RJ-186", "RJ-196", "RJ-230",
            "ES-010", "ES-060", "ES-080", "ES-164", "ES-248", "ES-261", "ES-482",
            "MG-010", "MG-050", "MG-135", "MG-167", "MG-179", "MG-184", "MG-188", "MG-290", "MG-353", "MG-458",
            "SP-055", "SP-070", "SP-075", "SP-125", "SP-150", "SP-160", "SP-270", "SP-280", "SP-300", "SP-310", "SP-330", "SP-348", "SP-425"
    };
    private static final Pattern ROAD = Pattern.compile("(?i)\\b(BR|RJ|MG|ES|SP)[-\\s]?(\\d{1,4})\\b");
    private static final String PREF = "epc_radio_road_fallback_v173", KEY = "road";

    static final class Region {
        final String key, label, uf; final double lat, lon, radiusKm;
        Region(String key, String label, String uf, double lat, double lon, double radiusKm) {
            this.key=key; this.label=label; this.uf=uf; this.lat=lat; this.lon=lon; this.radiusKm=radiusKm;
        }
    }

    private static final Region[] REGIONS = {
            new Region("CAMPOS_RJ", "Campos dos Goytacazes", "RJ", -21.7622, -41.3181, 58),
            new Region("MACAE_RJ", "Macaé", "RJ", -22.3768, -41.7848, 48),
            new Region("RIO_RJ", "Rio / Grande Rio", "RJ", -22.9068, -43.1729, 62),
            new Region("VOLTA_REDONDA_RJ", "Sul Fluminense", "RJ", -22.5202, -44.0996, 58),
            new Region("VITORIA_ES", "Vitória / Grande Vitória", "ES", -20.3155, -40.3128, 55),
            new Region("LINHARES_ES", "Linhares", "ES", -19.3946, -40.0643, 52),
            new Region("SAO_MATEUS_ES", "São Mateus", "ES", -18.7161, -39.8589, 52),
            new Region("CACHOEIRO_ES", "Cachoeiro de Itapemirim", "ES", -20.8480, -41.1120, 52),
            new Region("BELO_HORIZONTE_MG", "Grande Belo Horizonte", "MG", -19.9167, -43.9345, 65),
            new Region("JUIZ_FORA_MG", "Juiz de Fora", "MG", -21.7609, -43.3500, 58),
            new Region("GOV_VALADARES_MG", "Governador Valadares", "MG", -18.8511, -41.9494, 58),
            new Region("TEOFILO_OTONI_MG", "Teófilo Otoni", "MG", -17.8575, -41.5052, 55),
            new Region("UBERLANDIA_MG", "Uberlândia", "MG", -18.9186, -48.2772, 62)
    };

    private KnownRoadCatalog() {}

    static String canonical(String raw) {
        if (raw == null) return "";
        Matcher m = ROAD.matcher(raw.toUpperCase(Locale.ROOT));
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) + "-" + m.group(2) : "";
    }

    static void select(Context c, String road) {
        String v = canonical(road);
        if (c != null && !v.isEmpty()) c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, v).apply();
    }

    static String selected(Context c) {
        return c == null ? "" : canonical(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, ""));
    }

    // ROAD_SELECTION_V177: explicit manual choice can also be returned to automatic mode.
    static void clear(Context c) {
        if (c != null) c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }

    static Region region(double lat, double lon) {
        Region best = null; double bestKm = Double.POSITIVE_INFINITY;
        for (Region r : REGIONS) {
            double km = distanceKm(lat, lon, r.lat, r.lon);
            if (km <= r.radiusKm && km < bestKm) { best = r; bestKm = km; }
        }
        return best;
    }

    static String segmentKey(double lat, double lon) {
        Region r = region(lat, lon);
        if (r != null) return "R-" + r.key;
        int a = (int)Math.round(lat * 4.0), b = (int)Math.round(lon * 4.0); // ~25 km local cell
        return "G-" + a + "-" + b;
    }

    static String segmentLabel(double lat, double lon) {
        Region r = region(lat, lon);
        return r == null ? "trecho local" : "trecho " + r.label;
    }

    static String uf(double lat, double lon, String road) {
        String c = canonical(road);
        if (c.startsWith("RJ-")) return "RJ";
        if (c.startsWith("ES-")) return "ES";
        if (c.startsWith("MG-")) return "MG";
        if (c.startsWith("SP-")) return "SP";
        Region r = region(lat, lon); if (r != null) return r.uf;
        boolean rj = lat>=-23.45&&lat<=-20.65&&lon>=-44.95&&lon<=-40.75;
        boolean es = lat>=-21.40&&lat<=-17.75&&lon>=-41.95&&lon<=-39.55;
        if (rj && es) return "RJ/ES";
        if (es) return "ES";
        if (rj) return "RJ";
        if (lat>=-25.45&&lat<=-19.65&&lon>=-53.25&&lon<=-44.00) return "SP";
        if (lat>=-23.00&&lat<=-14.10&&lon>=-51.15&&lon<=-39.75) return "MG";
        return "BR";
    }

    private static double distanceKm(double a, double b, double c, double d) {
        double p1=Math.toRadians(a), p2=Math.toRadians(c), dl=Math.toRadians(d-b), dp=p2-p1;
        double x=Math.sin(dp/2)*Math.sin(dp/2)+Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)*Math.sin(dl/2);
        return 6371.0*2.0*Math.atan2(Math.sqrt(x),Math.sqrt(Math.max(0,1-x)));
    }
}
