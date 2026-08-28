\
package com.estradaplay.comunista;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class DestinationResolver {
    private DestinationResolver() {}

    static List<DestinationStore.Destination> search(String query) throws Exception {
        ArrayList<DestinationStore.Destination> out = new ArrayList<>();
        String q = query == null ? "" : query.trim();
        if (q.length() < 3) return out;
        String target = "https://nominatim.openstreetmap.org/search?format=jsonv2&countrycodes=br&limit=5&addressdetails=1&q="
                + URLEncoder.encode(q, "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setConnectTimeout(9000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9");
        c.setRequestProperty("User-Agent", "EstradaPlayComunista/1.0 Android destination-test");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            c.disconnect();
            throw new Exception("HTTP " + code);
        }
        String raw;
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bytes.write(buf, 0, n);
                if (bytes.size() > 2_000_000) throw new Exception("Resposta grande demais");
            }
            raw = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
        JSONArray arr = new JSONArray(raw);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            double lat = parse(item.optString("lat", ""));
            double lon = parse(item.optString("lon", ""));
            String label = item.optString("display_name", "Destino").trim();
            if (Double.isFinite(lat) && Double.isFinite(lon)) out.add(new DestinationStore.Destination(label, lat, lon));
        }
        return out;
    }

    private static double parse(String value) {
        try { return Double.parseDouble(value); } catch (Throwable ignored) { return Double.NaN; }
    }
}
