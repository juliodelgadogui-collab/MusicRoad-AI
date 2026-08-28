package com.estradaplay.comunista;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class RouteEngine {
    static final class Route {
        final String geoJson;
        final double distanceM;
        final double durationS;
        final String nextInstruction;

        Route(String geoJson, double distanceM, double durationS, String nextInstruction) {
            this.geoJson = geoJson;
            this.distanceM = distanceM;
            this.durationS = durationS;
            this.nextInstruction = nextInstruction == null ? "" : nextInstruction;
        }

        String summary() {
            String distance = distanceM >= 1000
                    ? String.format(Locale.getDefault(), "%.1f km", distanceM / 1000.0)
                    : Math.max(0, Math.round(distanceM)) + " m";
            long min = Math.max(1, Math.round(durationS / 60.0));
            long h = min / 60;
            long m = min % 60;
            String time = h > 0 ? h + "h " + m + "min" : m + " min";
            return distance + " · " + time;
        }
    }

    private RouteEngine() {}

    static Route fetch(double fromLat, double fromLon, double toLat, double toLon) throws Exception {
        String url = String.format(Locale.US,
                "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true&alternatives=false",
                fromLon, fromLat, toLon, toLat);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(25000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "EstradaPlayComunista/1.0 Android route-test");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            c.disconnect();
            throw new Exception("HTTP " + code);
        }
        String raw;
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) {
                bytes.write(buf, 0, n);
                if (bytes.size() > 8_000_000) throw new Exception("Rota grande demais");
            }
            raw = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }

        JSONObject root = new JSONObject(raw);
        JSONArray routes = root.optJSONArray("routes");
        if (routes == null || routes.length() == 0) throw new Exception("Rota não encontrada");
        JSONObject r = routes.optJSONObject(0);
        if (r == null) throw new Exception("Rota inválida");
        JSONObject geometry = r.optJSONObject("geometry");
        if (geometry == null) throw new Exception("Geometria ausente");

        JSONObject feature = new JSONObject();
        feature.put("type", "Feature");
        feature.put("properties", new JSONObject());
        feature.put("geometry", geometry);
        JSONArray features = new JSONArray();
        features.put(feature);
        JSONObject collection = new JSONObject();
        collection.put("type", "FeatureCollection");
        collection.put("features", features);

        String instruction = firstInstruction(r);
        return new Route(collection.toString(), r.optDouble("distance", 0), r.optDouble("duration", 0), instruction);
    }

    private static String firstInstruction(JSONObject route) {
        JSONArray legs = route.optJSONArray("legs");
        if (legs == null || legs.length() == 0) return "";
        JSONObject leg = legs.optJSONObject(0);
        JSONArray steps = leg == null ? null : leg.optJSONArray("steps");
        if (steps == null) return "";
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            JSONObject maneuver = step.optJSONObject("maneuver");
            if (maneuver == null) continue;
            String type = maneuver.optString("type", "");
            if ("depart".equals(type)) continue;
            String modifier = maneuver.optString("modifier", "");
            String road = step.optString("name", "").trim();
            double distance = step.optDouble("distance", 0);
            String action;
            if ("arrive".equals(type)) action = "Chegada ao destino";
            else if ("roundabout".equals(type) || "rotary".equals(type)) action = "Entre na rotatória";
            else if (modifier.contains("right")) action = "Vire à direita";
            else if (modifier.contains("left")) action = "Vire à esquerda";
            else action = "Siga em frente";
            if (!road.isEmpty() && !"arrive".equals(type)) action += " em " + road;
            if (distance > 40 && !"arrive".equals(type)) action += " · " + Math.round(distance) + " m";
            return action;
        }
        return "";
    }
}
