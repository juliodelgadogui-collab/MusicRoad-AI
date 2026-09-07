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
 * CLIMA_ROTA_V303
 * Clear local + route forecast backed by Open-Meteo.
 * The safety engine only consumes deterministic rain context from provider data.
 */
final class RoadWeatherMonitor {
    private static final String PREFS = "epc_weather_v191";
    private static final long FRESH_MS = 60L * 60L * 1000L;
    private static final long ROUTE_MAX_AGE_MS = 18L * 60L * 60L * 1000L;
    private static final int LOCAL_HOURS = 8;
    private static final int ROUTE_HOURS = 18;
    private static final int ROUTE_SAMPLES = 6;

    private RoadWeatherMonitor() {}

    static final class Snapshot {
        final boolean available;
        final boolean currentWet;
        final int currentChance;
        final double currentTempC;
        final double feelsLikeC;
        final double windKmh;
        final double gustKmh;
        final String condition;
        final int nextRainMinutes;
        final int nextRainChance;
        final int next6hMaxChance;
        final double next6hMinTempC;
        final double next6hMaxTempC;
        final double routeRainKm;
        final int routeRainMinutes;
        final int routeRainChance;
        final String routeLabel;
        final long updatedAt;
        final double currentPrecipMm;
        final double nextRainMm;
        final double next6hTotalMm;
        final double routeRainMm;
        final double routeRain3hMm;
        final String error;

        Snapshot(boolean available, boolean currentWet, int currentChance,
                 double currentTempC, double feelsLikeC, double windKmh, double gustKmh,
                 String condition, int nextRainMinutes, int nextRainChance,
                 int next6hMaxChance, double next6hMinTempC, double next6hMaxTempC,
                 double routeRainKm, int routeRainMinutes, int routeRainChance,
                 String routeLabel, long updatedAt, double currentPrecipMm, double nextRainMm,
                 double next6hTotalMm, double routeRainMm, double routeRain3hMm, String error) {
            this.available = available;
            this.currentWet = currentWet;
            this.currentChance = currentChance;
            this.currentPrecipMm = currentPrecipMm;
            this.currentTempC = currentTempC;
            this.feelsLikeC = feelsLikeC;
            this.windKmh = windKmh;
            this.gustKmh = gustKmh;
            this.condition = condition == null || condition.trim().isEmpty() ? "SEM DADOS" : condition.trim();
            this.nextRainMinutes = nextRainMinutes;
            this.nextRainChance = nextRainChance;
            this.next6hMaxChance = next6hMaxChance;
            this.next6hMinTempC = next6hMinTempC;
            this.next6hMaxTempC = next6hMaxTempC;
            this.routeRainKm = routeRainKm;
            this.routeRainMinutes = routeRainMinutes;
            this.routeRainChance = routeRainChance;
            this.routeLabel = routeLabel == null ? "" : routeLabel;
            this.updatedAt = updatedAt;
            this.nextRainMm = nextRainMm;
            this.next6hTotalMm = next6hTotalMm;
            this.routeRainMm = routeRainMm;
            this.routeRain3hMm = routeRain3hMm;
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
            if (gustKmh >= 55) return "VENTO FORTE";
            return condition;
        }

        String compact() {
            if (!available) return "CLIMA · atualizando…";
            String temp = Double.isFinite(currentTempC) ? Math.round(currentTempC) + "°" : "";
            String prefix = temp.isEmpty() ? "CLIMA" : "CLIMA · " + temp;
            if (currentWet) return prefix + " · CHUVA AGORA" + chanceText(currentChance);
            if (routeRisk()) {
                return prefix + " · CHUVA EM " + distance(routeRainKm) + " (~" + routeRainMinutes + " min)" + chanceText(routeRainChance);
            }
            if (nextRainMinutes >= 0) return prefix + " · CHUVA EM ~" + nextRainMinutes + " min" + chanceText(nextRainChance);
            if (gustKmh >= 55) return prefix + " · VENTO FORTE · rajadas " + Math.round(gustKmh) + " km/h";
            return prefix + " · " + condition + " · seco nas próximas 3 h";
        }

        String spoken() {
            if (!available) return "Ainda não tenho uma previsão do tempo atualizada.";
            String temp = Double.isFinite(currentTempC) ? " Temperatura de " + Math.round(currentTempC) + " graus." : "";
            if (currentWet) {
                String base = "Chuva agora na sua região." + spokenMm(currentPrecipMm) + temp;
                return currentChance > 0 ? base + " Probabilidade de " + currentChance + " por cento." : base;
            }
            if (routeRisk()) {
                String base = "Chuva prevista na rota, a aproximadamente " + spokenDistance(routeRainKm)
                        + ". Você deve alcançar essa área em cerca de " + routeRainMinutes + " minutos.";
                if (routeRainChance > 0) base += " Chance de chuva de " + routeRainChance + " por cento.";
                if (Double.isFinite(routeRain3hMm) && routeRain3hMm > 0.04)
                    base += " O acumulado previsto na janela de três horas em torno da passagem é de cerca de "
                            + String.format(Locale.getDefault(), "%.1f", routeRain3hMm) + " milímetros.";
                else base += spokenMm(routeRainMm);
                return base + temp;
            }
            if (nextRainMinutes >= 0) {
                String base = "Há chuva prevista para esta região em cerca de " + nextRainMinutes + " minutos.";
                base += spokenMm(nextRainMm);
                if (nextRainChance > 0) base += " Probabilidade de " + nextRainChance + " por cento.";
                return base + temp;
            }
            String base = "Tempo " + condition.toLowerCase(Locale.ROOT) + "." + temp + " Sem chuva relevante nas próximas três horas.";
            if (gustKmh >= 55) base += " Atenção a rajadas de vento de até " + Math.round(gustKmh) + " quilômetros por hora.";
            return base;
        }

        boolean shouldAnnounce() {
            if (!available) return false;
            if (currentWet) return true;
            if (routeRisk() && routeRainKm <= 120.0 && routeRainMinutes <= 120 && routeRainChance >= 40) return true;
            return nextRainMinutes >= 0 && nextRainMinutes <= 60 && nextRainChance >= 40;
        }

        String voiceKey() {
            if (!shouldAnnounce()) return "";
            if (currentWet) return "now:" + Math.max(0, currentChance / 10);
            if (routeRisk()) return "route:" + Math.max(0, (int)Math.round(routeRainKm / 10.0)) + ":" + Math.max(0, routeRainChance / 10);
            return "local:" + Math.max(0, nextRainMinutes / 15) + ":" + Math.max(0, nextRainChance / 10);
        }

        String nextHoursSummary() {
            if (!available) return "Previsão indisponível";
            StringBuilder out = new StringBuilder();
            if (Double.isFinite(next6hMinTempC) && Double.isFinite(next6hMaxTempC)) {
                out.append(Math.round(next6hMinTempC)).append("° a ").append(Math.round(next6hMaxTempC)).append("°");
            }
            if (next6hMaxChance > 0) {
                if (out.length() > 0) out.append(" · ");
                out.append("chuva até ").append(next6hMaxChance).append("%");
            } else {
                if (out.length() > 0) out.append(" · ");
                out.append("baixa chance de chuva");
            }
            if (next6hTotalMm > 0.04) {
                if (out.length() > 0) out.append(" · ");
                out.append("acumulado ~").append(String.format(Locale.getDefault(), "%.1f", next6hTotalMm)).append(" mm em 6 h");
            }
            return out.toString();
        }

        private static String mmText(double mm) { return Double.isFinite(mm) && mm > 0.04 ? " · " + String.format(Locale.getDefault(), "%.1f mm", mm) : ""; }
        private static String spokenMm(double mm) { return Double.isFinite(mm) && mm > 0.04 ? " Previsão de cerca de " + String.format(Locale.getDefault(), "%.1f", mm) + " milímetros de precipitação." : ""; }
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
                p.getFloat("current_temp_c", Float.NaN),
                p.getFloat("feels_like_c", Float.NaN),
                p.getFloat("wind_kmh", Float.NaN),
                p.getFloat("gust_kmh", Float.NaN),
                p.getString("condition", "SEM DADOS"),
                p.getInt("next_rain_min", -1),
                p.getInt("next_rain_chance", 0),
                p.getInt("next_6h_max_chance", 0),
                p.getFloat("next_6h_min_temp", Float.NaN),
                p.getFloat("next_6h_max_temp", Float.NaN),
                routeKm,
                p.getInt("route_rain_min", -1),
                p.getInt("route_rain_chance", 0),
                p.getString("route_label", ""),
                at,
                p.getFloat("current_precip_mm", Float.NaN),
                p.getFloat("next_rain_mm", Float.NaN),
                p.getFloat("next_6h_total_mm", Float.NaN),
                p.getFloat("route_rain_mm", Float.NaN),
                p.getFloat("route_rain_3h_mm", Float.NaN),
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
            double nextRainMm = Double.NaN, totalMm6h = 0;
            int maxChance6h = 0;
            double minTemp6h = Double.NaN, maxTemp6h = Double.NaN;
            int horizon = Math.min(6, f.size());
            for (int i = 0; i < horizon; i++) {
                Risk r = f.riskAt(i);
                maxChance6h = Math.max(maxChance6h, r.chance);
                if (Double.isFinite(r.mm) && r.mm > 0) totalMm6h += r.mm;
                double t = f.temperatureAt(i);
                if (Double.isFinite(t)) {
                    minTemp6h = !Double.isFinite(minTemp6h) ? t : Math.min(minTemp6h, t);
                    maxTemp6h = !Double.isFinite(maxTemp6h) ? t : Math.max(maxTemp6h, t);
                }
                if (nextMin < 0 && r.rain && !(i == 0 && f.currentWet)) {
                    nextMin = i * 60;
                    nextChance = r.chance;
                    nextRainMm = r.mm;
                }
            }
            long now = System.currentTimeMillis();
            prefs(app).edit()
                    .putBoolean("current_wet", f.currentWet)
                    .putInt("current_chance", f.currentChance)
                    .putFloat("current_precip_mm", finiteFloat(f.currentPrecipMm))
                    .putFloat("current_temp_c", finiteFloat(f.currentTempC))
                    .putFloat("feels_like_c", finiteFloat(f.feelsLikeC))
                    .putFloat("wind_kmh", finiteFloat(f.windKmh))
                    .putFloat("gust_kmh", finiteFloat(f.gustKmh))
                    .putString("condition", conditionFromCode(f.currentCode))
                    .putInt("next_rain_min", nextMin)
                    .putInt("next_rain_chance", nextChance)
                    .putFloat("next_rain_mm", finiteFloat(nextRainMm))
                    .putFloat("next_6h_total_mm", finiteFloat(totalMm6h))
                    .putInt("next_6h_max_chance", maxChance6h)
                    .putFloat("next_6h_min_temp", finiteFloat(minTemp6h))
                    .putFloat("next_6h_max_temp", finiteFloat(maxTemp6h))
                    .putLong("updated_at", now)
                    .putString("last_error", "")
                    .apply();
            DriveSettings.setRainAutoDetected(app, f.currentWet || (nextMin >= 0 && nextMin <= 30 && nextChance >= 40));
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
            for (int n = 1; n <= ROUTE_SAMPLES; n++) {
                double fraction = n / (double)ROUTE_SAMPLES;
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
                .putFloat("route_rain_mm", Float.NaN)
                .putFloat("route_rain_3h_mm", Float.NaN)
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
            double riskMm = Double.NaN, risk3hMm = Double.NaN;
            int count = Math.min(samples.size(), forecasts.size());
            for (int i = 0; i < count; i++) {
                Sample s = samples.get(i);
                Forecast f = forecasts.get(i);
                if (f.size() <= 0) continue;
                int hour = Math.max(0, Math.min(f.size() - 1, (int)Math.round(s.minutes / 60.0)));
                Risk risk = f.riskAround(hour);
                if (!risk.rain) continue;
                riskKm = s.km;
                riskMin = s.minutes;
                riskChance = risk.chance;
                riskMm = risk.mm;
                risk3hMm = f.sumMmAround(hour);
                break;
            }
            p.edit()
                    .putLong("route_rain_km_bits", Double.doubleToRawLongBits(riskKm))
                    .putInt("route_rain_min", riskMin)
                    .putInt("route_rain_chance", riskChance)
                    .putFloat("route_rain_mm", finiteFloat(riskMm))
                    .putFloat("route_rain_3h_mm", finiteFloat(risk3hMm))
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
                .putFloat("route_rain_mm", Float.NaN)
                .putFloat("route_rain_3h_mm", Float.NaN)
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
                + "&current=temperature_2m,apparent_temperature,precipitation,rain,showers,weather_code,wind_speed_10m,wind_gusts_10m"
                + "&hourly=temperature_2m,apparent_temperature,precipitation_probability,precipitation,rain,showers,weather_code,wind_speed_10m,wind_gusts_10m"
                + "&forecast_hours=" + Math.max(6, hours)
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
            h.setRequestProperty("User-Agent", "EstradaPlayComunista/3.0 Android");
            int code = h.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("clima HTTP " + code);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && b.size() < 900000) b.write(buf, 0, n);
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
        final double mm;
        Risk(boolean rain, int chance, double mm) { this.rain = rain; this.chance = Math.max(0, Math.min(100, chance)); this.mm = Math.max(0, Double.isFinite(mm) ? mm : 0); }
    }

    private static final class Forecast {
        final boolean currentWet;
        final int currentChance;
        final double currentPrecipMm;
        final double currentTempC, feelsLikeC, windKmh, gustKmh;
        final int currentCode;
        final JSONArray temperature, probability, precipitation, rain, showers, code;

        Forecast(boolean currentWet, int currentChance, double currentPrecipMm, double currentTempC, double feelsLikeC,
                 double windKmh, double gustKmh, int currentCode,
                 JSONArray temperature, JSONArray probability, JSONArray precipitation,
                 JSONArray rain, JSONArray showers, JSONArray code) {
            this.currentWet = currentWet;
            this.currentChance = currentChance;
            this.currentPrecipMm = currentPrecipMm;
            this.currentTempC = currentTempC;
            this.feelsLikeC = feelsLikeC;
            this.windKmh = windKmh;
            this.gustKmh = gustKmh;
            this.currentCode = currentCode;
            this.temperature = temperature == null ? new JSONArray() : temperature;
            this.probability = probability == null ? new JSONArray() : probability;
            this.precipitation = precipitation == null ? new JSONArray() : precipitation;
            this.rain = rain == null ? new JSONArray() : rain;
            this.showers = showers == null ? new JSONArray() : showers;
            this.code = code == null ? new JSONArray() : code;
        }

        static Forecast parse(JSONObject root) {
            JSONObject current = root.optJSONObject("current");
            JSONObject hourly = root.optJSONObject("hourly");
            JSONArray temperature = hourly == null ? null : hourly.optJSONArray("temperature_2m");
            JSONArray probability = hourly == null ? null : hourly.optJSONArray("precipitation_probability");
            JSONArray precipitation = hourly == null ? null : hourly.optJSONArray("precipitation");
            JSONArray rain = hourly == null ? null : hourly.optJSONArray("rain");
            JSONArray showers = hourly == null ? null : hourly.optJSONArray("showers");
            JSONArray code = hourly == null ? null : hourly.optJSONArray("weather_code");
            double nowP = current == null ? 0 : current.optDouble("precipitation", Double.NaN);
            if (!Double.isFinite(nowP)) nowP = current == null ? 0 : current.optDouble("rain", 0) + current.optDouble("showers", 0);
            int nowCode = current == null ? 0 : current.optInt("weather_code", 0);
            boolean wet = nowP > 0.05 || rainyCode(nowCode);
            int chance = probability == null || probability.length() == 0 ? (wet ? 100 : 0) : probability.optInt(0, wet ? 100 : 0);
            return new Forecast(
                    wet, chance, nowP,
                    current == null ? Double.NaN : current.optDouble("temperature_2m", Double.NaN),
                    current == null ? Double.NaN : current.optDouble("apparent_temperature", Double.NaN),
                    current == null ? Double.NaN : current.optDouble("wind_speed_10m", Double.NaN),
                    current == null ? Double.NaN : current.optDouble("wind_gusts_10m", Double.NaN),
                    nowCode, temperature, probability, precipitation, rain, showers, code);
        }

        int size() {
            return Math.max(temperature.length(), Math.max(probability.length(), Math.max(precipitation.length(), code.length())));
        }

        double temperatureAt(int i) { return i < 0 ? Double.NaN : temperature.optDouble(i, Double.NaN); }

        Risk riskAt(int i) {
            if (i < 0) return new Risk(false, 0, 0);
            int chance = probability.optInt(i, 0);
            double mm = precipitation.optDouble(i, Double.NaN);
            if (!Double.isFinite(mm)) mm = rain.optDouble(i, 0) + showers.optDouble(i, 0);
            int weather = code.optInt(i, 0);
            boolean risk = chance >= 40 || mm >= 0.10 || rainyCode(weather);
            if (risk && chance <= 0) chance = mm >= 0.10 || rainyCode(weather) ? 70 : 40;
            return new Risk(risk, chance, mm);
        }

        Risk riskAround(int hour) {
            boolean rain = false;
            int chance = 0;
            double mm = 0;
            int from = Math.max(0, hour - 1), to = Math.min(Math.max(0, size() - 1), hour + 1);
            for (int i = from; i <= to; i++) {
                Risk r = riskAt(i);
                rain = rain || r.rain;
                chance = Math.max(chance, r.chance);
                mm = Math.max(mm, r.mm);
            }
            return new Risk(rain, chance, mm);
        }

        double sumMmAround(int hour) {
            double total = 0;
            int from = Math.max(0, hour - 1), to = Math.min(Math.max(0, size() - 1), hour + 1);
            for (int i = from; i <= to; i++) total += riskAt(i).mm;
            return total;
        }
    }

    private static boolean rainyCode(int code) {
        return (code >= 51 && code <= 82) || code >= 95;
    }

    private static String conditionFromCode(int code) {
        if (code == 0) return "CÉU LIMPO";
        if (code == 1) return "POUCO NUBLADO";
        if (code == 2) return "PARCIALMENTE NUBLADO";
        if (code == 3) return "NUBLADO";
        if (code == 45 || code == 48) return "NEBLINA";
        if (code >= 51 && code <= 57) return "GAROA";
        if (code >= 61 && code <= 67) return "CHUVA";
        if (code >= 71 && code <= 77) return "PRECIPITAÇÃO FRIA";
        if (code >= 80 && code <= 82) return "PANCADAS DE CHUVA";
        if (code == 85 || code == 86) return "PANCADAS FRIAS";
        if (code >= 95) return "TEMPESTADE";
        return "TEMPO ESTÁVEL";
    }

    private static float finiteFloat(double value) {
        return Double.isFinite(value) ? (float)value : Float.NaN;
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
