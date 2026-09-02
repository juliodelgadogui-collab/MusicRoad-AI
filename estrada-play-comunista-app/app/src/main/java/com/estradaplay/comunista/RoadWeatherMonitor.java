package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * CLIMA_ROTA_V191
 * Local + route weather forecast backed by Open-Meteo.
 * Safety decisions remain deterministic: this class only changes the rain context
 * and never invents weather when the provider is unavailable.
 */
final class RoadWeatherMonitor {
    private static final String PREFS = "epc_weather_v191";
    private static final long FRESH_MS = 75L * 60L * 1000L;
    private static final long ROUTE_MAX_AGE_MS = 18L * 60L * 60L * 1000L;
    private static final int LOCAL_HOURS = 6;
    private static final int ROUTE_HOURS = 12;

    private RoadWeatherMonitor() {}

    static final class Snapshot {
        final boolean available;
        final boolean currentWet;
        final int currentChance;
        final int nextRainMinutes;
        final int nextRainChance;
        final double routeRainKm;
        final int routeRainMinutes;
        final int routeRainChance;
        final String routeLabel;
        final long updatedAt;
        final String error;

        Snapshot(boolean available, boolean currentWet, int currentChance,
                 int nextRainMinutes, int nextRainChance,
                 double routeRainKm, int routeRainMinutes, int routeRainChance,
                 String routeLabel, long updatedAt, String error) {
            this.available = available;
            this.currentWet = currentWet;
            this.currentChance = currentChance;
            this.nextRainMinutes = nextRainMinutes;
            this.nextRainChance = nextRainChance;
            this.routeRainKm = routeRainKm;
            this.routeRainMinutes = routeRainMinutes;
            this.routeRainChance = routeRainChance;
            this.routeLabel = routeLabel == null ? "" : routeLabel;
            this.updatedAt = updatedAt;
            this.error = error == null ? "" : error;
        }

        boolean routeRisk() {
            return Double.isFinite(routeRainKm) && routeRainKm >= 0 && routeRainMinutes >= 0;
        }

        String headline() {
            if (!available) return "CLIMA SEM DADOS";
            if (currentWet) return "CHUVA AGORA";
            if (routeRisk()) return "CHUVA À FRENTE";
            if (nextRainMinutes >= 0) return "CHUVA PREVISTA";
            return "SECO À FRENTE";
        }

        String compact() {
            if (!available) return "CLIMA · aguardando atualização";
            if (currentWet) return "CHUVA AGORA" + chanceText(currentChance);
            if (routeRisk()) {
                return "CHUVA À FRENTE · " + distance(routeRainKm) + " · ~" + routeRainMinutes + " min" + chanceText(routeRainChance);
            }
            if (nextRainMinutes >= 0) return "CHUVA PREVISTA · ~" + nextRainMinutes + " min" + chanceText(nextRainChance);
            return "SECO · próximas 3 h";
        }

        String spoken() {
            if (!available) return "Ainda não tenho uma previsão de chuva atualizada.";
            if (currentWet) {
                return currentChance > 0
                        ? "Chuva detectada na sua região. Probabilidade atual de " + currentChance + " por cento."
                        : "Chuva detectada na sua região. Dirija com atenção à pista molhada.";
            }
            if (routeRisk()) {
                String base = "Chuva prevista à frente, a aproximadamente " + spokenDistance(routeRainKm)
                        + ", em cerca de " + routeRainMinutes + " minutos.";
                return routeRainChance > 0 ? base + " Probabilidade de " + routeRainChance + " por cento." : base;
            }
            if (nextRainMinutes >= 0) {
                String base = "Há chuva prevista para esta região em cerca de " + nextRainMinutes + " minutos.";
                return nextRainChance > 0 ? base + " Probabilidade de " + nextRainChance + " por cento." : base;
            }
            return "Não há chuva relevante prevista nas próximas três horas para sua região e rota conhecida.";
        }

        boolean shouldAnnounce() {
            if (!available) return false;
            if (currentWet) return true;
            if (routeRisk() && routeRainKm <= 120.0 && routeRainMinutes <= 120 && routeRainChance >= 40) return true;
            return nextRainMinutes >= 0 && nextRainMinutes <= 45 && nextRainChance >= 40;
        }

        String voiceKey() {
            if (!shouldAnnounce()) return "";
            if (currentWet) return "now:" + Math.max(0, currentChance / 10);
            if (routeRisk()) return "route:" + Math.max(0, (int)Math.round(routeRainKm / 10.0)) + ":" + Math.max(0, routeRainChance / 10);
            return "local:" + Math.max(0, nextRainMinutes / 15) + ":" + Math.max(0, nextRainChance / 10);
        }

        private static String chanceText(int chance) { return chance > 0 ? " · " + chance + "%" : ""; }
        private static String distance(double km) { return km < 10 ? String.format(Locale.getDefault(), "%.1f km", km) : Math.round(km) + " km"; }
        private static String spokenDistance(double km) { return km < 10 ? String.format(Locale.getDefault(), "%.1f quilômetros", km) : Math.round(km) + " quilômetros"; }
    }

    static Snapshot snapshot(Context c) {
        SharedPreferences p = prefs(c);
        long at = p.getLong("updated_at", 0L);
        boolean fresh = at > 0L && System.currentTimeMillis() - at <= FRESH_MS;
        double routeKm = Double.longBitsToDouble(p.getLong("route_rain_km_bits", Double.doubleToRawLongBits(Double.NaN)));
        return new Snapshot(
                fresh,
                p.getBoolean("current_wet", false),
                p.getInt("current_chance", 0),
                p.getInt("next_rain_min", -1),
                p.getInt("next_rain_chance", 0),
                routeKm,
                p.getInt("route_rain_min", -1),
                p.getInt("route_rain_chance", 0),
                p.getString("route_label", ""),
                at,
                p.getString("last_error", "")
        );
    }

    static String compactStatus(Context c) {
        if (!DriveSettings.autoRain(c)) return "CLIMA AUTOMÁTICO · DESLIGADO";
        return snapshot(c).compact();
    }

    static String spokenStatus(Context c) { return snapshot(c).spoken(); }

    static long ageMinutes(Context c) {
        long at = snapshot(c).updatedAt;
        return at <= 0 ? -1 : Math.max(0, (System.currentTimeMillis() - at) / 60000L);
    }

    static void refresh(Context c, double lat, double lon) {
        if (c == null || DriveSettings.offlineTestMode(c) || !DriveSettings.autoRain(c)) return;
        Context app = c.getApplicationContext();
        try {
            Forecast f = fetchSingle(lat, lon, LOCAL_HOURS);
            int nextMin = -1, nextChance = 0;
            for (int i = 0; i < Math.min(4, f.size()); i++) {
                Risk r = f.riskAt(i);
                if (!r.rain) continue;
                if (i == 0 && f.currentWet) continue;
                nextMin = i * 60;
                nextChance = r.chance;
                break;
            }
            long now = System.currentTimeMillis();
            prefs(app).edit()
                    .putBoolean("current_wet", f.currentWet)
                    .putInt("current_chance", f.currentChance)
                    .putInt("next_rain_min", nextMin)
                    .putInt("next_rain_chance", nextChance)
                    .putLong("updated_at", now)
                    .putString("last_error", "")
                    .apply();
            // Start preparing for a wet road shortly before the first drops arrive.
            DriveSettings.setRainAutoDetected(app, f.currentWet || (nextMin >= 0 && nextMin <= 30));
            refreshRoute(app);
        } catch (Throwable e) {
            prefs(app).edit().putString("last_error", cleanError(e)).apply();
        }
    }

    static void saveActiveRoute(Context c, RouteEngine.Route route, String destinationLabel) {
        if (c == null || route == null) return;
        try {
            JSONObject root = new JSONObject(route.geoJson);
            JSONArray features = root.optJSONArray("features");
            JSONObject feature = features == null ? null : features.optJSONObject(0);
            JSONObject geometry = feature == null ? null : feature.optJSONObject("geometry");
            JSONArray coords = geometry == null ? null : geometry.optJSONArray("coordinates");
            if (coords == null || coords.length() < 2) return;
            JSONArray samples = new JSONArray();
            // Four points are enough to catch weather fronts while keeping network use small.
            for (int n = 1; n <= 4; n++) {
                double fraction = n / 4.0;
                int idx = Math.min(coords.length() - 1, Math.max(0, (int)Math.round((coords.length() - 1) * fraction)));
                JSONArray point = coords.optJSONArray(idx);
                if (point == null || point.length() < 2) continue;
                JSONObject s = new JSONObject();
                s.put("lat", point.optDouble(1));
                s.put("lon", point.optDouble(0));
                s.put("km", route.distanceM / 1000.0 * fraction);
                s.put("min", Math.max(0, (int)Math.round(route.durationS / 60.0 * fraction)));
                samples.put(s);
            }
            prefs(c).edit()
                    .putString("route_samples", samples.toString())
                    .putString("route_label", destinationLabel == null ? "" : destinationLabel.trim())
                    .putLong("route_saved_at", System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {}
    }

    static void clearActiveRoute(Context c) {
        if (c == null) return;
        prefs(c).edit()
                .remove("route_samples")
                .remove("route_label")
                .remove("route_saved_at")
                .putLong("route_rain_km_bits", Double.doubleToRawLongBits(Double.NaN))
                .putInt("route_rain_min", -1)
                .putInt("route_rain_chance", 0)
                .apply();
    }

    static void refreshRoute(Context c) {
        if (c == null || DriveSettings.offlineTestMode(c) || !DriveSettings.autoRain(c)) return;
        SharedPreferences p = prefs(c);
        long savedAt = p.getLong("route_saved_at", 0L);
        if (savedAt <= 0 || System.currentTimeMillis() - savedAt > ROUTE_MAX_AGE_MS) {
            clearRouteRisk(c);
            return;
        }
        try {
            JSONArray rawSamples = new JSONArray(p.getString("route_samples", "[]"));
            if (rawSamples.length() == 0) { clearRouteRisk(c); return; }
            ArrayList<Sample> samples = new ArrayList<>();
            for (int i = 0; i < rawSamples.length(); i++) {
                JSONObject o = rawSamples.optJSONObject(i);
                if (o == null) continue;
                double lat = o.optDouble("lat", Double.NaN), lon = o.optDouble("lon", Double.NaN);
                if (!Double.isFinite(lat) || !Double.isFinite(lon)) continue;
                samples.add(new Sample(lat, lon, o.optDouble("km", 0), o.optInt("min", 0)));
            }
            if (samples.isEmpty()) { clearRouteRisk(c); return; }
            ArrayList<Forecast> forecasts = fetchMany(samples, ROUTE_HOURS);
            double riskKm = Double.NaN;
            int riskMin = -1, riskChance = 0;
            int count = Math.min(samples.size(), forecasts.size());
            for (int i = 0; i < count; i++) {
                Sample s = samples.get(i);
                Forecast f = forecasts.get(i);
                int hour = Math.max(0, Math.min(f.size() - 1, (int)Math.round(s.minutes / 60.0)));
                Risk risk = f.riskAround(hour);
                if (!risk.rain) continue;
                riskKm = s.km;
                riskMin = s.minutes;
                riskChance = risk.chance;
                break;
            }
            p.edit()
                    .putLong("route_rain_km_bits", Double.doubleToRawLongBits(riskKm))
                    .putInt("route_rain_min", riskMin)
                    .putInt("route_rain_chance", riskChance)
                    .apply();
        } catch (Throwable e) {
            p.edit().putString("last_error", cleanError(e)).apply();
        }
    }

    private static void clearRouteRisk(Context c) {
        prefs(c).edit()
                .putLong("route_rain_km_bits", Double.doubleToRawLongBits(Double.NaN))
                .putInt("route_rain_min", -1)
                .putInt("route_rain_chance", 0)
                .apply();
    }

    private static Forecast fetchSingle(double lat, double lon, int hours) throws Exception {
        ArrayList<Sample> one = new ArrayList<>();
        one.add(new Sample(lat, lon, 0, 0));
        ArrayList<Forecast> result = fetchMany(one, hours);
        if (result.isEmpty()) throw new Exception("previsão vazia");
        return result.get(0);
    }

    private static ArrayList<Forecast> fetchMany(ArrayList<Sample> samples, int hours) throws Exception {
        StringBuilder lats = new StringBuilder(), lons = new StringBuilder();
        for (Sample s : samples) {
            if (lats.length() > 0) { lats.append(','); lons.append(','); }
            lats.append(String.format(Locale.US, "%.5f", s.lat));
            lons.append(String.format(Locale.US, "%.5f", s.lon));
        }
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lats
                + "&longitude=" + lons
                + "&current=precipitation,rain,showers,weather_code"
                + "&hourly=precipitation_probability,precipitation,rain,showers,weather_code"
                + "&forecast_hours=" + Math.max(4, hours)
                + "&timezone=auto";
        Object root = requestJson(url);
        ArrayList<Forecast> out = new ArrayList<>();
        if (root instanceof JSONArray) {
            JSONArray a = (JSONArray) root;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(Forecast.parse(o));
            }
        } else if (root instanceof JSONObject) {
            out.add(Forecast.parse((JSONObject) root));
        }
        return out;
    }

    private static Object requestJson(String target) throws Exception {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection)new URL(target).openConnection();
            h.setConnectTimeout(7000);
            h.setReadTimeout(10000);
            h.setInstanceFollowRedirects(true);
            h.setRequestProperty("Accept", "application/json");
            h.setRequestProperty("User-Agent", "EstradaPlayComunista/1.9.1 Android");
            int code = h.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("clima HTTP " + code);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && b.size() < 700000) b.write(buf, 0, n);
            }
            return new JSONTokener(new String(b.toByteArray(), StandardCharsets.UTF_8)).nextValue();
        } finally {
            if (h != null) h.disconnect();
        }
    }

    private static final class Sample {
        final double lat, lon, km;
        final int minutes;
        Sample(double lat, double lon, double km, int minutes) { this.lat = lat; this.lon = lon; this.km = km; this.minutes = minutes; }
    }

    private static final class Risk {
        final boolean rain;
        final int chance;
        Risk(boolean rain, int chance) { this.rain = rain; this.chance = Math.max(0, Math.min(100, chance)); }
    }

    private static final class Forecast {
        final boolean currentWet;
        final int currentChance;
        final JSONArray probability, precipitation, rain, showers, code;

        Forecast(boolean currentWet, int currentChance, JSONArray probability, JSONArray precipitation,
                 JSONArray rain, JSONArray showers, JSONArray code) {
            this.currentWet = currentWet;
            this.currentChance = currentChance;
            this.probability = probability == null ? new JSONArray() : probability;
            this.precipitation = precipitation == null ? new JSONArray() : precipitation;
            this.rain = rain == null ? new JSONArray() : rain;
            this.showers = showers == null ? new JSONArray() : showers;
            this.code = code == null ? new JSONArray() : code;
        }

        static Forecast parse(JSONObject root) {
            JSONObject current = root.optJSONObject("current");
            JSONObject hourly = root.optJSONObject("hourly");
            JSONArray probability = hourly == null ? null : hourly.optJSONArray("precipitation_probability");
            JSONArray precipitation = hourly == null ? null : hourly.optJSONArray("precipitation");
            JSONArray rain = hourly == null ? null : hourly.optJSONArray("rain");
            JSONArray showers = hourly == null ? null : hourly.optJSONArray("showers");
            JSONArray code = hourly == null ? null : hourly.optJSONArray("weather_code");
            double nowP = current == null ? 0 : current.optDouble("precipitation", 0) + current.optDouble("rain", 0) + current.optDouble("showers", 0);
            int nowCode = current == null ? 0 : current.optInt("weather_code", 0);
            boolean wet = nowP > 0.05 || rainyCode(nowCode);
            int chance = probability == null || probability.length() == 0 ? (wet ? 100 : 0) : probability.optInt(0, wet ? 100 : 0);
            return new Forecast(wet, chance, probability, precipitation, rain, showers, code);
        }

        int size() {
            return Math.max(probability.length(), Math.max(precipitation.length(), code.length()));
        }

        Risk riskAt(int i) {
            if (i < 0) return new Risk(false, 0);
            int chance = probability.optInt(i, 0);
            double mm = precipitation.optDouble(i, 0) + rain.optDouble(i, 0) + showers.optDouble(i, 0);
            int weather = code.optInt(i, 0);
            boolean risk = chance >= 45 || mm >= 0.15 || rainyCode(weather);
            if (risk && chance <= 0) chance = mm >= 0.15 || rainyCode(weather) ? 70 : 45;
            return new Risk(risk, chance);
        }

        Risk riskAround(int hour) {
            boolean rain = false;
            int chance = 0;
            int from = Math.max(0, hour - 1), to = Math.min(Math.max(0, size() - 1), hour + 1);
            for (int i = from; i <= to; i++) {
                Risk r = riskAt(i);
                rain = rain || r.rain;
                chance = Math.max(chance, r.chance);
            }
            return new Risk(rain, chance);
        }
    }

    private static boolean rainyCode(int code) {
        return (code >= 51 && code <= 82) || code >= 95;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String cleanError(Throwable e) {
        String s = e == null ? "falha de clima" : e.getMessage();
        if (s == null || s.trim().isEmpty()) s = e == null ? "falha de clima" : e.getClass().getSimpleName();
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 120 ? s.substring(0, 120) : s;
    }
}
