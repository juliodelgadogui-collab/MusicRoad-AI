package com.estradaplay.comunista;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** NAVIGATION_REAL_V208: full maneuver sequence + local route map matching. */
final class RouteEngine {
    static final class Step {
        final String instruction, road, type, modifier;
        final double distanceM, durationS, maneuverLat, maneuverLon, alongM;

        Step(String instruction, String road, String type, String modifier,
             double distanceM, double durationS, double maneuverLat, double maneuverLon, double alongM) {
            this.instruction = instruction == null ? "" : instruction;
            this.road = road == null ? "" : road;
            this.type = type == null ? "" : type;
            this.modifier = modifier == null ? "" : modifier;
            this.distanceM = Math.max(0, distanceM);
            this.durationS = Math.max(0, durationS);
            this.maneuverLat = maneuverLat;
            this.maneuverLon = maneuverLon;
            this.alongM = Math.max(0, alongM);
        }
    }

    static final class Match {
        final boolean valid;
        final int segmentIndex;
        final double alongM, lateralM, snappedLat, snappedLon, segmentBearing;

        Match(boolean valid, int segmentIndex, double alongM, double lateralM,
              double snappedLat, double snappedLon, double segmentBearing) {
            this.valid = valid;
            this.segmentIndex = segmentIndex;
            this.alongM = alongM;
            this.lateralM = lateralM;
            this.snappedLat = snappedLat;
            this.snappedLon = snappedLon;
            this.segmentBearing = segmentBearing;
        }

        static Match invalid() {
            return new Match(false, -1, 0, Double.POSITIVE_INFINITY,
                    Double.NaN, Double.NaN, Double.NaN);
        }
    }

    static final class Route {
        final String geoJson;
        final double distanceM, durationS;
        // Legacy fields retained for older cockpit helpers.
        final double nextDistanceM;
        final String nextInstruction, nextRoad, maneuverType, maneuverModifier;
        final List<Step> steps;
        private final double[] lats, lons, cumulativeM;

        Route(String geoJson, double distanceM, double durationS,
              List<Step> steps, double[] lats, double[] lons, double[] cumulativeM) {
            this.geoJson = geoJson;
            this.distanceM = Math.max(0, distanceM);
            this.durationS = Math.max(0, durationS);
            this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
            this.lats = lats;
            this.lons = lons;
            this.cumulativeM = cumulativeM;
            Step first = upcomingStep(0);
            this.nextInstruction = first == null ? "Siga na rota" : first.instruction;
            this.nextRoad = first == null ? "" : first.road;
            this.maneuverType = first == null ? "" : first.type;
            this.maneuverModifier = first == null ? "" : first.modifier;
            this.nextDistanceM = first == null ? 0 : first.alongM;
        }

        String summary() {
            String d = distanceM >= 1000
                    ? String.format(Locale.getDefault(), "%.1f km", distanceM / 1000.0)
                    : Math.max(0, Math.round(distanceM)) + " m";
            long min = Math.max(1, Math.round(durationS / 60.0)), h = min / 60, m = min % 60;
            return d + " · " + (h > 0 ? h + "h " + m + "min" : m + " min");
        }

        Step upcomingStep(double alongM) {
            Step arrival = null;
            for (Step s : steps) {
                if ("arrive".equals(s.type)) arrival = s;
                if ("depart".equals(s.type)) continue;
                if (s.alongM >= alongM + 7.0) return s;
            }
            return arrival;
        }

        double nextManeuverDistance(double alongM) {
            Step s = upcomingStep(alongM);
            return s == null ? 0 : Math.max(0, s.alongM - alongM);
        }

        double remainingDistance(double alongM) {
            return Math.max(0, distanceM - Math.max(0, alongM));
        }

        double remainingDuration(double alongM) {
            if (distanceM <= 1) return 0;
            double ratio = Math.max(0, Math.min(1, remainingDistance(alongM) / distanceM));
            return durationS * ratio;
        }

        Match match(double lat, double lon, double headingDeg, int hintSegment, double previousAlongM) {
            if (!Double.isFinite(lat) || !Double.isFinite(lon) || lats.length < 2) return Match.invalid();
            int n = lats.length - 1;
            Candidate best = null;
            if (hintSegment >= 0 && hintSegment < n) {
                int from = Math.max(0, hintSegment - 90);
                int to = Math.min(n - 1, hintSegment + 260);
                best = search(lat, lon, headingDeg, previousAlongM, from, to, best);
            }
            if (best == null || best.lateralM > 115) {
                best = search(lat, lon, headingDeg, previousAlongM, 0, n - 1, best);
            }
            if (best == null) return Match.invalid();
            return new Match(true, best.segment, best.alongM, best.lateralM,
                    best.snapLat, best.snapLon, best.bearing);
        }

        private Candidate search(double lat, double lon, double headingDeg, double previousAlongM,
                                 int from, int to, Candidate initial) {
            Candidate best = initial;
            double cos = Math.cos(Math.toRadians(lat));
            double mx = 111320.0 * Math.max(0.2, Math.abs(cos));
            double my = 110540.0;
            for (int i = from; i <= to; i++) {
                double ax = (lons[i] - lon) * mx;
                double ay = (lats[i] - lat) * my;
                double bx = (lons[i + 1] - lon) * mx;
                double by = (lats[i + 1] - lat) * my;
                double dx = bx - ax, dy = by - ay;
                double len2 = dx * dx + dy * dy;
                if (len2 < 0.25) continue;
                double t = -(ax * dx + ay * dy) / len2;
                t = Math.max(0, Math.min(1, t));
                double px = ax + t * dx, py = ay + t * dy;
                double lateral = Math.hypot(px, py);
                double segLen = Math.max(0.1, cumulativeM[i + 1] - cumulativeM[i]);
                double along = cumulativeM[i] + segLen * t;
                double bearing = bearingDeg(lats[i], lons[i], lats[i + 1], lons[i + 1]);
                double score = lateral;
                if (Double.isFinite(headingDeg) && headingDeg >= 0 && segLen >= 8) {
                    score += Math.min(45.0, angleDiff(headingDeg, bearing) * 0.23);
                }
                if (previousAlongM > 100 && along < previousAlongM - 150) {
                    score += Math.min(140, (previousAlongM - along - 150) * 0.28 + 45);
                }
                if (best == null || score < best.score) {
                    double snapLat = lat + py / my;
                    double snapLon = lon + px / mx;
                    best = new Candidate(i, score, lateral, along, snapLat, snapLon, bearing);
                }
            }
            return best;
        }
    }

    private static final class Candidate {
        final int segment;
        final double score, lateralM, alongM, snapLat, snapLon, bearing;
        Candidate(int segment, double score, double lateralM, double alongM,
                  double snapLat, double snapLon, double bearing) {
            this.segment = segment;
            this.score = score;
            this.lateralM = lateralM;
            this.alongM = alongM;
            this.snapLat = snapLat;
            this.snapLon = snapLon;
            this.bearing = bearing;
        }
    }

    private RouteEngine() {}

    // NAVIGATION_REAL_COMPAT_V208: legacy planner overload during consolidation.
    static Route fetch(double fromLat, double fromLon, double toLat, double toLon) throws Exception {
        return fetchOsrmFallback(fromLat, fromLon, toLat, toLon);
    }

    static Route fetch(Context context, double fromLat, double fromLon, double toLat, double toLon) throws Exception {
        Exception serverError = null;
        try {
            JSONObject body = new JSONObject();
            body.put("from_lat", fromLat);
            body.put("from_lon", fromLon);
            body.put("to_lat", toLat);
            body.put("to_lon", toLon);
            body.put("language", "pt-BR");
            ApiClient.Response response = new ApiClient(context).post("api/navigation_route.php", body);
            JSONObject root = response.json();
            JSONObject route = root.optJSONObject("route");
            if (response.ok() && root.optBoolean("ok") && route != null) return parseRoute(route);
            serverError = new Exception(root.optString("error", "Servidor de rota indisponível"));
        } catch (Exception e) {
            serverError = e;
        }

        // Compatibility fallback while a 2.0.7 server is being upgraded to 2.0.8.
        try {
            return fetchOsrmFallback(fromLat, fromLon, toLat, toLon);
        } catch (Exception fallback) {
            if (serverError != null) fallback.addSuppressed(serverError);
            throw fallback;
        }
    }

    private static Route fetchOsrmFallback(double fromLat, double fromLon, double toLat, double toLon) throws Exception {
        String url = String.format(Locale.US,
                "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true&alternatives=false&continue_straight=true",
                fromLon, fromLat, toLon, toLat);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(25000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "EstradaPlayComunista/2.0.8 Android");
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
                if (bytes.size() > 10_000_000) throw new Exception("Rota grande demais");
            }
            raw = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
        JSONObject root = new JSONObject(raw);
        JSONArray routes = root.optJSONArray("routes");
        if (routes == null || routes.length() == 0) throw new Exception("Rota não encontrada");
        return parseRoute(routes.getJSONObject(0));
    }

    private static Route parseRoute(JSONObject r) throws Exception {
        JSONObject geometry = r.optJSONObject("geometry");
        JSONArray coords = geometry == null ? null : geometry.optJSONArray("coordinates");
        if (coords == null || coords.length() < 2) throw new Exception("Geometria de rota ausente");

        int n = coords.length();
        double[] lats = new double[n], lons = new double[n], cumulative = new double[n];
        for (int i = 0; i < n; i++) {
            JSONArray p = coords.optJSONArray(i);
            if (p == null || p.length() < 2) throw new Exception("Geometria de rota inválida");
            lons[i] = p.optDouble(0, Double.NaN);
            lats[i] = p.optDouble(1, Double.NaN);
            if (!Double.isFinite(lats[i]) || !Double.isFinite(lons[i])) throw new Exception("Coordenada inválida");
            if (i > 0) cumulative[i] = cumulative[i - 1] + distanceM(lats[i - 1], lons[i - 1], lats[i], lons[i]);
        }

        JSONObject feature = new JSONObject();
        feature.put("type", "Feature");
        feature.put("properties", new JSONObject());
        feature.put("geometry", geometry);
        JSONArray features = new JSONArray();
        features.put(feature);
        JSONObject collection = new JSONObject();
        collection.put("type", "FeatureCollection");
        collection.put("features", features);

        ArrayList<Step> steps = new ArrayList<>();
        JSONArray legs = r.optJSONArray("legs");
        if (legs != null) {
            for (int li = 0; li < legs.length(); li++) {
                JSONObject leg = legs.optJSONObject(li);
                JSONArray rawSteps = leg == null ? null : leg.optJSONArray("steps");
                if (rawSteps == null) continue;
                for (int si = 0; si < rawSteps.length(); si++) {
                    JSONObject step = rawSteps.optJSONObject(si);
                    if (step == null) continue;
                    JSONObject man = step.optJSONObject("maneuver");
                    if (man == null) continue;
                    String type = man.optString("type", "").trim().toLowerCase(Locale.ROOT);
                    String modifier = man.optString("modifier", "").trim().toLowerCase(Locale.ROOT);
                    String road = step.optString("name", "").trim();
                    String instruction = man.optString("instruction", "").trim();
                    if (instruction.isEmpty()) instruction = maneuverInstruction(type, modifier, road, man.optInt("exit", 0));
                    JSONArray loc = man.optJSONArray("location");
                    double mlon = loc == null ? Double.NaN : loc.optDouble(0, Double.NaN);
                    double mlat = loc == null ? Double.NaN : loc.optDouble(1, Double.NaN);
                    double along = Double.isFinite(mlat) && Double.isFinite(mlon)
                            ? projectAlong(lats, lons, cumulative, mlat, mlon) : 0;
                    steps.add(new Step(instruction, road, type, modifier,
                            step.optDouble("distance", 0), step.optDouble("duration", 0), mlat, mlon, along));
                }
            }
        }
        if (steps.isEmpty()) {
            steps.add(new Step("Siga na rota", "", "depart", "", 0, 0, lats[0], lons[0], 0));
            steps.add(new Step("Chegada ao destino", "", "arrive", "", 0, 0, lats[n - 1], lons[n - 1], cumulative[n - 1]));
        }

        double providerDistance = r.optDouble("distance", cumulative[n - 1]);
        double scale = cumulative[n - 1] > 1 && providerDistance > 1 ? providerDistance / cumulative[n - 1] : 1.0;
        if (Math.abs(scale - 1.0) > 0.01) {
            for (int i = 0; i < cumulative.length; i++) cumulative[i] *= scale;
            ArrayList<Step> scaled = new ArrayList<>(steps.size());
            for (Step s : steps) {
                scaled.add(new Step(s.instruction, s.road, s.type, s.modifier, s.distanceM, s.durationS,
                        s.maneuverLat, s.maneuverLon, s.alongM * scale));
            }
            steps = scaled;
        }

        return new Route(collection.toString(), providerDistance,
                r.optDouble("duration", 0), steps, lats, lons, cumulative);
    }

    private static double projectAlong(double[] lats, double[] lons, double[] cumulative, double lat, double lon) {
        double cos = Math.cos(Math.toRadians(lat));
        double mx = 111320.0 * Math.max(0.2, Math.abs(cos)), my = 110540.0;
        double best = Double.POSITIVE_INFINITY, along = 0;
        for (int i = 0; i < lats.length - 1; i++) {
            double ax = (lons[i] - lon) * mx, ay = (lats[i] - lat) * my;
            double bx = (lons[i + 1] - lon) * mx, by = (lats[i + 1] - lat) * my;
            double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
            if (len2 < 0.25) continue;
            double t = Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
            double d = Math.hypot(ax + t * dx, ay + t * dy);
            if (d < best) {
                best = d;
                along = cumulative[i] + (cumulative[i + 1] - cumulative[i]) * t;
            }
        }
        return along;
    }

    private static String maneuverInstruction(String type, String modifier, String road, int exit) {
        String action;
        if ("arrive".equals(type)) action = "Chegada ao destino";
        else if ("roundabout".equals(type) || "rotary".equals(type)) action = exit > 0 ? "Na rotatória, pegue a " + exit + "ª saída" : "Entre na rotatória";
        else if (modifier.contains("slight right")) action = "Mantenha-se levemente à direita";
        else if (modifier.contains("slight left")) action = "Mantenha-se levemente à esquerda";
        else if (modifier.contains("sharp right")) action = "Faça uma curva acentuada à direita";
        else if (modifier.contains("sharp left")) action = "Faça uma curva acentuada à esquerda";
        else if (modifier.contains("right")) action = "Vire à direita";
        else if (modifier.contains("left")) action = "Vire à esquerda";
        else if ("merge".equals(type)) action = "Entre na via";
        else if ("fork".equals(type)) action = "Mantenha-se na bifurcação";
        else if ("on ramp".equals(type) || "off ramp".equals(type)) action = "Acesse a alça";
        else if ("uturn".equals(type)) action = "Faça o retorno";
        else action = "Siga em frente";
        return road == null || road.trim().isEmpty() || "arrive".equals(type) ? action : action + " em " + road.trim();
    }

    static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        if (!Double.isFinite(lat1) || !Double.isFinite(lon1) || !Double.isFinite(lat2) || !Double.isFinite(lon2)) return Double.POSITIVE_INFINITY;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dLat = p2 - p1, dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371000.0 * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
    }

    private static double bearingDeg(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    private static double angleDiff(double a, double b) {
        double d = Math.abs((a - b) % 360.0);
        return d > 180 ? 360 - d : d;
    }
}
