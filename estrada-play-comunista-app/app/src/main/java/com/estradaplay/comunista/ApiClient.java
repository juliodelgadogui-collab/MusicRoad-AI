package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

final class ApiClient {
    private static final String PREFS = "estradaplay_session_v1";
    private static final String KEY_COOKIE = "cookie";
    private final Context app;
    private final SharedPreferences prefs;
    private final String base;

    ApiClient(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String b = BuildConfig.SERVER_URL == null ? "" : BuildConfig.SERVER_URL.trim();
        if (!b.endsWith("/")) b += "/";
        base = b;
    }

    String base() { return base; }
    String cookie() { return prefs.getString(KEY_COOKIE, ""); }
    void clearSession() { prefs.edit().remove(KEY_COOKIE).apply(); }

    String absolute(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.startsWith("http://") || v.startsWith("https://") || v.startsWith("file://")) return v;
        while (v.startsWith("/")) v = v.substring(1);
        return base + v;
    }

    // LIBRARY_PAGED_V208: normal catalog pages stay small; legacy fallback remains bounded.
    Response getFast(String path) throws Exception { return request("GET", path, null, 5000, 10000, 4_000_000); }
    Response get(String path) throws Exception { return request("GET", path, null, 8000, 18000, 4_000_000); }
    Response getLong(String path) throws Exception { return request("GET", path, null, 10000, 120000, 50_000_000); }
    Response getCatalogLegacy(String path) throws Exception { return request("GET", path, null, 10000, 45000, 16_000_000); }
    Response post(String path, JSONObject data) throws Exception { return request("POST", path, data == null ? new JSONObject() : data, 8000, 18000, 4_000_000); }

    private Response request(String method, String path, JSONObject data, int connectTimeout, int readTimeout, int maxChars) throws Exception {
        String target = path.startsWith("http://") || path.startsWith("https://") ? path : absolute(path);
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setInstanceFollowRedirects("GET".equals(method));
        c.setConnectTimeout(connectTimeout);
        c.setReadTimeout(readTimeout);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Accept-Encoding", "gzip");
        c.setRequestProperty("User-Agent", "EstradaPlay/" + BuildConfig.VERSION_NAME + " Android");
        c.setRequestProperty("X-MusicRoad-Native", "1");
        c.setRequestProperty("X-EstradaPlay-Device", DeviceIdentity.token(app));
        c.setRequestProperty("X-EstradaPlay-Device-Label", DeviceIdentity.label());
        String cookie = cookie();
        if (cookie != null && !cookie.trim().isEmpty()) c.setRequestProperty("Cookie", cookie.trim());
        if (data != null) {
            byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
        }
        int code = c.getResponseCode();
        captureCookies(c.getHeaderFields());
        InputStream raw = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
        InputStream in = raw;
        String encoding = c.getContentEncoding();
        if (raw != null && encoding != null && encoding.toLowerCase().contains("gzip")) in = new java.util.zip.GZIPInputStream(raw);
        String body = read(in, maxChars);
        c.disconnect();
        return new Response(code, body);
    }

    private void captureCookies(Map<String, List<String>> headers) {
        if (headers == null) return;
        StringBuilder jar = new StringBuilder();
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() == null || !"set-cookie".equalsIgnoreCase(e.getKey())) continue;
            for (String raw : e.getValue()) {
                if (raw == null || raw.trim().isEmpty()) continue;
                String pair = raw.split(";", 2)[0].trim();
                if (pair.isEmpty()) continue;
                if (jar.length() > 0) jar.append("; ");
                jar.append(pair);
            }
        }
        if (jar.length() > 0) prefs.edit().putString(KEY_COOKIE, jar.toString()).apply();
    }

    private static String read(InputStream in, int maxChars) throws Exception {
        if (in == null) return "";
        StringBuilder out = new StringBuilder(Math.min(maxChars, 262144));
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 16384)) {
            char[] buf = new char[16384];
            int n;
            while ((n = r.read(buf)) > 0) {
                out.append(buf, 0, n);
                if (out.length() > maxChars) throw new IllegalStateException("Resposta maior que o limite permitido");
            }
        }
        return out.toString();
    }

    static final class Response {
        final int code;
        final String body;
        Response(int code, String body) { this.code = code; this.body = body == null ? "" : body; }
        boolean ok() { return code >= 200 && code < 300; }
        JSONObject json() {
            try { return new JSONObject(body); }
            catch (Exception e) { return new JSONObject(); }
        }
    }
}
