package com.estradaplay.patriota;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** CONTEXTO_INTELIGENTE_V209: one authenticated Server 7.0 call feeds the cockpit. */
final class RouteContextV7Client {
    private static final long FRESH_MS = 120_000L;

    static final class Snapshot {
        final long receivedAt;
        final String version;
        final String weatherText;
        final boolean rainSoon;
        final String attentionKind;
        final String attentionTitle;
        final String attentionDetail;
        final double attentionAheadM;
        final String trafficText;
        final String fuelText;
        final boolean trafficOptIn;
        final boolean trafficStored;
        final int hazardCount;
        final int liveCount;
        final int trafficCount;

        Snapshot(long receivedAt, String version, String weatherText, boolean rainSoon,
                 String attentionKind, String attentionTitle, String attentionDetail,
                 double attentionAheadM, String trafficText, String fuelText,
                 boolean trafficOptIn, boolean trafficStored,
                 int hazardCount, int liveCount, int trafficCount) {
            this.receivedAt = receivedAt;
            this.version = clean(version);
            this.weatherText = clean(weatherText);
            this.rainSoon = rainSoon;
            this.attentionKind = clean(attentionKind);
            this.attentionTitle = clean(attentionTitle);
            this.attentionDetail = clean(attentionDetail);
            this.attentionAheadM = Math.max(0, attentionAheadM);
            this.trafficText = clean(trafficText);
            this.fuelText = clean(fuelText);
            this.trafficOptIn = trafficOptIn;
            this.trafficStored = trafficStored;
            this.hazardCount = Math.max(0, hazardCount);
            this.liveCount = Math.max(0, liveCount);
            this.trafficCount = Math.max(0, trafficCount);
        }

        boolean fresh() { return System.currentTimeMillis() - receivedAt <= FRESH_MS; }

        String aheadLabel() {
            if (attentionTitle.isEmpty()) return "";
            return attentionTitle + (attentionAheadM > 0 ? " · " + formatAhead(attentionAheadM) : "");
        }

        String metaLine() {
            StringBuilder out = new StringBuilder("SERVER 7.0");
            if (!trafficText.isEmpty()) out.append(" · ").append(trafficText);
            if (!fuelText.isEmpty()) out.append(" · ").append(fuelText);
            out.append(trafficOptIn ? (trafficStored ? " · TRÂNSITO ENVIADO" : " · TRÂNSITO OPT-IN") : " · TRÂNSITO SOMENTE LEITURA");
            return out.toString();
        }
    }

    private RouteContextV7Client() {}

    static Snapshot fetch(Context context, double lat, double lon, double heading,
                          double speedKmh, int limitKmh, String road,
                          RouteEngine.Route route) throws Exception {
        JSONObject body = new JSONObject();
        body.put("lat", lat);
        body.put("lon", lon);
        body.put("heading", Double.isFinite(heading) ? heading : 0);
        body.put("speed_kmh", Math.max(0, Math.min(190, speedKmh)));
        if (limitKmh > 0) body.put("limit_kmh", limitKmh);
        body.put("road", road == null ? "" : road.trim());
        body.put("captured_at", System.currentTimeMillis());
        body.put("route_points", compactRoutePoints(route, lat, lon));
        body.put("include", new JSONArray().put("hazards").put("live").put("traffic").put("weather").put("fuel"));
        body.put("consumption_km_l", Math.max(3.0, DriveSettings.consumptionKml(context)));
        body.put("liters_to_buy", estimateLitersToBuy(context));

        boolean trafficOptIn = DriveSettings.contextTrafficOptIn(context);
        body.put("traffic_opt_in", trafficOptIn);

        ApiClient.Response response = new ApiClient(context).post("api/route_context.php", body);
        JSONObject root = response.json();
        if (!response.ok() || !root.optBoolean("ok", false)) {
            throw new Exception(root.optString("error", "Contexto Server 7.0 indisponível"));
        }
        return parse(root, trafficOptIn);
    }

    private static Snapshot parse(JSONObject root, boolean requestedTrafficOptIn) {
        JSONArray weather = root.optJSONArray("weather");
        JSONObject weatherPick = null;
        boolean rainSoon = false;
        if (weather != null) {
            for (int i = 0; i < weather.length(); i++) {
                JSONObject w = weather.optJSONObject(i);
                if (w == null) continue;
                double ahead = Math.max(0, w.optDouble("ahead_m", 0));
                int eta = Math.max(0, w.optInt("eta_s", 0));
                String condition = upper(w.optString("condition", ""));
                int probability = w.optInt("precipitation_probability", 0);
                double precipitation = w.optDouble("precipitation_mm", 0);
                boolean wet = condition.contains("CHUVA") || condition.contains("GAROA") || condition.contains("TEMPESTADE")
                        || probability >= 60 || precipitation >= 1.0;
                if (wet && (eta <= 1800 || ahead <= 30_000)) rainSoon = true;
                if (weatherPick == null) weatherPick = w;
                if (!"OK".equalsIgnoreCase(w.optString("severity", "OK"))) {
                    weatherPick = w;
                    break;
                }
            }
        }
        String weatherText = weatherLine(weatherPick);

        JSONArray priorities = root.optJSONArray("priorities");
        JSONObject priority = null;
        if (priorities != null) {
            for (int i = 0; i < priorities.length(); i++) {
                JSONObject p = priorities.optJSONObject(i);
                if (p == null) continue;
                if (p.optDouble("ahead_m", 0) < -100) continue;
                priority = p;
                break;
            }
        }

        String kind = priority == null ? "" : upper(priority.optString("kind", ""));
        String message = priority == null ? "" : upper(priority.optString("message", ""));
        double ahead = priority == null ? 0 : Math.max(0, priority.optDouble("ahead_m", 0));
        String title = attentionTitle(kind, message);
        String detail = attentionDetail(kind, message, ahead, priority == null ? "" : priority.optString("severity", ""), root);

        JSONArray traffic = root.optJSONArray("traffic");
        String trafficText = trafficLine(traffic);
        JSONArray fuel = root.optJSONArray("fuel");
        String fuelText = fuelLine(fuel);

        JSONObject privacy = root.optJSONObject("privacy");
        boolean trafficOptIn = privacy != null ? privacy.optBoolean("traffic_opt_in", requestedTrafficOptIn) : requestedTrafficOptIn;
        JSONObject trafficSample = root.optJSONObject("traffic_sample");
        boolean trafficStored = trafficSample != null && trafficSample.optBoolean("stored", false);

        return new Snapshot(
                System.currentTimeMillis(), root.optString("version", "7.0"), weatherText, rainSoon,
                kind, title, detail, ahead, trafficText, fuelText,
                trafficOptIn, trafficStored,
                length(root.optJSONArray("hazards")), length(root.optJSONArray("live_events")), length(traffic));
    }

    private static JSONArray compactRoutePoints(RouteEngine.Route route, double lat, double lon) {
        JSONArray out = new JSONArray();
        putPoint(out, lat, lon);
        if (route == null || route.geoJson == null || route.geoJson.trim().isEmpty()) return out;
        try {
            JSONObject collection = new JSONObject(route.geoJson);
            JSONArray features = collection.optJSONArray("features");
            JSONObject feature = features == null ? null : features.optJSONObject(0);
            JSONObject geometry = feature == null ? null : feature.optJSONObject("geometry");
            JSONArray coords = geometry == null ? null : geometry.optJSONArray("coordinates");
            if (coords == null || coords.length() < 2) return out;

            int nearest = 0;
            double nearestM = Double.POSITIVE_INFINITY;
            for (int i = 0; i < coords.length(); i++) {
                JSONArray p = coords.optJSONArray(i);
                if (p == null || p.length() < 2) continue;
                double plon = p.optDouble(0, Double.NaN), plat = p.optDouble(1, Double.NaN);
                if (!Double.isFinite(plat) || !Double.isFinite(plon)) continue;
                double d = distanceM(lat, lon, plat, plon);
                if (d < nearestM) { nearestM = d; nearest = i; }
            }

            int start = Math.max(0, nearest - 1);
            int remaining = coords.length() - start;
            int wanted = Math.min(42, Math.max(2, remaining));
            for (int i = 0; i < wanted; i++) {
                int index = wanted == 1 ? start : start + (int)Math.round((i / (double)(wanted - 1)) * (remaining - 1));
                JSONArray p = coords.optJSONArray(Math.min(coords.length() - 1, index));
                if (p == null || p.length() < 2) continue;
                double plon = p.optDouble(0, Double.NaN), plat = p.optDouble(1, Double.NaN);
                if (!Double.isFinite(plat) || !Double.isFinite(plon)) continue;
                JSONObject last = out.optJSONObject(out.length() - 1);
                if (last != null && distanceM(last.optDouble("lat"), last.optDouble("lon"), plat, plon) < 20) continue;
                putPoint(out, plat, plon);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void putPoint(JSONArray out, double lat, double lon) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) return;
        JSONObject p = new JSONObject();
        try { p.put("lat", lat); p.put("lon", lon); out.put(p); } catch (Exception ignored) {}
    }

    private static double estimateLitersToBuy(Context context) {
        float tank = DriveSettings.tankLiters(context);
        float percent = DriveSettings.fuelPercent(context);
        double missing = Math.max(0, tank * (1.0 - Math.max(0, Math.min(100, percent)) / 100.0));
        return Math.max(5.0, Math.min(80.0, missing > 3 ? missing : 20.0));
    }

    private static String weatherLine(JSONObject w) {
        if (w == null) return "CLIMA SERVER 7.0";
        String condition = clean(w.optString("condition", "CLIMA"));
        double temp = w.optDouble("temperature_c", Double.NaN);
        int prob = w.optInt("precipitation_probability", 0);
        double ahead = Math.max(0, w.optDouble("ahead_m", 0));
        StringBuilder out = new StringBuilder(condition);
        if (Double.isFinite(temp)) out.append(" · ").append(Math.round(temp)).append("°C");
        if (prob > 0) out.append(" · ").append(prob).append("% chuva");
        if (ahead >= 1500) out.append(" · em ").append(formatAhead(ahead));
        return out.toString();
    }

    private static String trafficLine(JSONArray traffic) {
        if (traffic == null || traffic.length() == 0) return "TRÂNSITO SEM AMOSTRAS";
        JSONObject chosen = null;
        for (int i = 0; i < traffic.length(); i++) {
            JSONObject t = traffic.optJSONObject(i);
            if (t == null) continue;
            if (chosen == null) chosen = t;
            String level = upper(t.optString("level", ""));
            if (!level.isEmpty() && !"LIVRE".equals(level)) { chosen = t; break; }
        }
        if (chosen == null) return "TRÂNSITO SEM AMOSTRAS";
        String level = upper(chosen.optString("level", "LIVRE")).replace('_', ' ');
        double ahead = Math.max(0, chosen.optDouble("ahead_m", 0));
        return "TRÂNSITO " + level + (ahead > 300 ? " " + formatAhead(ahead) : "");
    }

    private static String fuelLine(JSONArray fuel) {
        if (fuel == null || fuel.length() == 0) return "";
        JSONObject f = fuel.optJSONObject(0);
        if (f == null) return "";
        String station = clean(f.optString("station", "POSTO"));
        if (station.length() > 20) station = station.substring(0, 20) + "…";
        double price = f.optDouble("price", 0);
        double detour = Math.max(0, f.optDouble("detour_roundtrip_km", 0));
        String value = price > 0 ? String.format(Locale.getDefault(), "R$ %.2f", price) : "preço recente";
        return "COMB. " + station + " " + value + (detour >= 1 ? String.format(Locale.getDefault(), " · +%.1f km", detour) : "");
    }

    private static String attentionTitle(String kind, String message) {
        if (kind.isEmpty()) return "";
        if ("TRAFFIC".equals(kind)) {
            if (message.contains("PARADO")) return "Trânsito parado à frente";
            if (message.contains("MUITO_LENTO")) return "Trânsito muito lento";
            return "Trânsito lento à frente";
        }
        if ("WEATHER".equals(kind)) {
            if (message.contains("VISIBILIDADE")) return "Visibilidade baixa à frente";
            if (message.contains("TEMPESTADE")) return "Tempestade à frente";
            if (message.contains("CHUVA")) return "Chuva à frente";
            if (message.contains("VENTO")) return "Vento forte à frente";
            return "Clima exige atenção";
        }
        if ("LIVE".equals(kind)) return liveLabel(message);
        return clean(message);
    }

    private static String attentionDetail(String kind, String message, double ahead, String severity, JSONObject root) {
        StringBuilder out = new StringBuilder();
        if (ahead > 0) out.append(formatAhead(ahead));
        if ("LIVE".equals(kind)) {
            JSONObject live = firstLiveMatching(root.optJSONArray("live_events"), message);
            if (live != null) {
                String road = clean(live.optString("road", ""));
                String note = clean(live.optString("note", ""));
                JSONObject confidence = live.optJSONObject("confidence");
                int score = confidence == null ? 0 : (int)Math.round(confidence.optDouble("score", 0) * 100);
                if (!road.isEmpty()) appendDot(out, road);
                if (!note.isEmpty()) appendDot(out, note);
                if (score > 0) appendDot(out, score + "% confiança");
            }
        } else if (!clean(severity).isEmpty()) {
            appendDot(out, "prioridade " + clean(severity).toLowerCase(Locale.ROOT));
        }
        return out.length() == 0 ? clean(message) : out.toString();
    }

    private static JSONObject firstLiveMatching(JSONArray live, String type) {
        if (live == null) return null;
        String wanted = upper(type);
        for (int i = 0; i < live.length(); i++) {
            JSONObject e = live.optJSONObject(i);
            if (e == null) continue;
            if (wanted.isEmpty() || upper(e.optString("type", "")).equals(wanted)) return e;
        }
        return live.optJSONObject(0);
    }

    private static String liveLabel(String type) {
        String t = upper(type);
        if (t.contains("ACIDENT")) return "Acidente reportado à frente";
        if (t.contains("OBRA")) return "Obra reportada à frente";
        if (t.contains("ANIMAL")) return "Animal na pista reportado";
        if (t.contains("BURACO")) return "Buraco reportado à frente";
        if (t.contains("ALAG")) return "Alagamento reportado";
        if (t.contains("POLIC")) return "Fiscalização reportada à frente";
        return t.isEmpty() ? "Evento da Estrada Viva" : "Evento: " + t.replace('_', ' ');
    }

    private static void appendDot(StringBuilder out, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (out.length() > 0) out.append(" · ");
        out.append(value.trim());
    }

    private static int length(JSONArray a) { return a == null ? 0 : a.length(); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String upper(String value) { return clean(value).toUpperCase(Locale.ROOT); }

    private static String formatAhead(double meters) {
        if (meters >= 1000) return String.format(Locale.getDefault(), "%.1f km", meters / 1000.0);
        return Math.max(0, Math.round(meters)) + " m";
    }

    private static double distanceM(double aLat, double aLon, double bLat, double bLon) {
        double r = 6371000.0;
        double p1 = Math.toRadians(aLat), p2 = Math.toRadians(bLat);
        double dp = Math.toRadians(bLat - aLat), dl = Math.toRadians(bLon - aLon);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(h)));
    }
}