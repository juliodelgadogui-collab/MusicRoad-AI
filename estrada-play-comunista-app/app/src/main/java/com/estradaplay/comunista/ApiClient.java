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
    private final SecureDeviceCredential credential;
    private final String base;
    private boolean secureBootstrapAttempted;
    private long lastDeviceRecoveryAt;

    ApiClient(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        credential = new SecureDeviceCredential(app);
        String fallback = BuildConfig.SERVER_URL == null ? "" : BuildConfig.SERVER_URL.trim();
        base = ServerEndpointStore.base(app, fallback);
    }

    String base() { return base; }
    String cookie() { return prefs.getString(KEY_COOKIE, ""); }
    void clearSession() {
        prefs.edit().remove(KEY_COOKIE).apply();
        credential.clearTokens();
        secureBootstrapAttempted = false;
        lastDeviceRecoveryAt = 0L;
    }

    boolean hasSecureSession() {
        return !credential.accessToken().isEmpty() || !credential.refreshToken().isEmpty();
    }

    String absolute(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.startsWith("http://") || v.startsWith("https://") || v.startsWith("file://")) return v;
        while (v.startsWith("/")) v = v.substring(1);
        return base + v;
    }

    Response getFast(String path) throws Exception { return request("GET", path, null, 5000, 10000, 4_000_000); }
    Response get(String path) throws Exception { return request("GET", path, null, 8000, 18000, 4_000_000); }
    Response getLong(String path) throws Exception { return request("GET", path, null, 10000, 120000, 50_000_000); }
    Response getCatalogLegacy(String path) throws Exception { return request("GET", path, null, 10000, 45000, 16_000_000); }
    Response post(String path, JSONObject data) throws Exception { return request("POST", path, data == null ? new JSONObject() : data, 8000, 18000, 4_000_000); }

    private Response request(String method, String path, JSONObject data, int connectTimeout, int readTimeout, int maxChars) throws Exception {
        return requestInternal(method, path, data, connectTimeout, readTimeout, maxChars, true, false);
    }

    private Response requestInternal(String method, String path, JSONObject data, int connectTimeout, int readTimeout,
                                     int maxChars, boolean allowRefresh, boolean refreshCall) throws Exception {
        String target = path.startsWith("http://") || path.startsWith("https://") ? path : absolute(path);
        boolean trusted = isTrustedTarget(target);
        boolean credentialAction = isCredentialAction(target);

        if (trusted && !credentialAction && !refreshCall) bootstrapSecureSessionIfNeeded();

        // SESSION_HOST_COMPAT_V233: some shared PHP hosts do not expose custom credential headers
        // consistently to PHP/FastCGI. Credential endpoints therefore receive the same random secret
        // in their JSON body as a same-origin fallback. It never goes to external URLs.
        if (trusted && data != null && (credentialAction || refreshCall) && !data.has("device_secret")) {
            try { data.put("device_secret", credential.secret()); } catch (Exception ignored) {}
        }

        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setInstanceFollowRedirects("GET".equals(method));
        c.setConnectTimeout(connectTimeout);
        c.setReadTimeout(readTimeout);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Accept-Encoding", "gzip");
        c.setRequestProperty("User-Agent", "EstradaPlay/" + BuildConfig.VERSION_NAME + " Android");

        if (trusted) {
            c.setRequestProperty("X-MusicRoad-Native", "1");
            c.setRequestProperty("X-EstradaPlay-Device", DeviceIdentity.token(app));
            c.setRequestProperty("X-EstradaPlay-Device-Label", DeviceIdentity.label());

            if (credentialAction || refreshCall) {
                c.setRequestProperty("X-EstradaPlay-Device-Secret", credential.secret());
            }

            String access = credentialAction || refreshCall ? "" : credential.accessToken();
            if (!access.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + access);

            // SESSION_HOST_COMPAT_V233: keep the same-origin PHP session cookie alongside Bearer.
            // Apache/FastCGI installations often strip Authorization before PHP sees it. In that
            // environment the secure login still creates a PHP session, so Cookie is a safe TLS-only
            // compatibility path instead of turning every protected endpoint into HTTP 401.
            String cookie = cookie();
            if (cookie != null && !cookie.trim().isEmpty()) {
                c.setRequestProperty("Cookie", cookie.trim());
            }
        }

        if (data != null) {
            byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
        }
        int code = c.getResponseCode();
        if (trusted) captureCookies(c.getHeaderFields());
        InputStream raw = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
        InputStream in = raw;
        String encoding = c.getContentEncoding();
        if (raw != null && encoding != null && encoding.toLowerCase().contains("gzip")) in = new java.util.zip.GZIPInputStream(raw);
        String body = read(in, maxChars);
        c.disconnect();
        Response response = new Response(code, body);
        if (trusted) captureAuth(response);

        if (code == 401 && allowRefresh && trusted && !credentialAction) {
            if (refreshIfPossible()) {
                return requestInternal(method, path, data, connectTimeout, readTimeout, maxChars, false, false);
            }
            if (recoverDeviceSessionIfPossible()) {
                return requestInternal(method, path, data, connectTimeout, readTimeout, maxChars, false, false);
            }
        }
        return response;
    }

    private void bootstrapSecureSessionIfNeeded() {
        if (secureBootstrapAttempted) return;
        if (!credential.accessToken().isEmpty() || !credential.refreshToken().isEmpty()) return;
        String legacyCookie = cookie();
        if (legacyCookie == null || legacyCookie.trim().isEmpty()) return;
        secureBootstrapAttempted = true;
        try {
            JSONObject d = devicePayload();
            requestInternal("POST", "api/native_app.php?action=device_login", d, 7000, 14000, 2_000_000, false, false);
        } catch (Exception ignored) {}
    }

    private JSONObject devicePayload() {
        JSONObject d = new JSONObject();
        try {
            d.put("device_token", DeviceIdentity.token(app));
            d.put("device_label", DeviceIdentity.label());
            d.put("app_version", BuildConfig.VERSION_NAME);
            d.put("device_secret", credential.secret());
        } catch (Exception ignored) {}
        return d;
    }

    private boolean refreshIfPossible() {
        String refresh = credential.refreshToken();
        if (refresh.isEmpty()) return false;
        try {
            JSONObject d = devicePayload();
            d.put("refresh_token", refresh);
            Response r = requestInternal("POST", "api/native_app.php?action=refresh", d, 8000, 18000, 2_000_000, false, true);
            if (r.ok() && r.json().optBoolean("ok", false)) {
                secureBootstrapAttempted = true;
                return !credential.accessToken().isEmpty() || !cookie().isEmpty();
            }
        } catch (Exception ignored) {}
        credential.clearTokens();
        return false;
    }

    private boolean recoverDeviceSessionIfPossible() {
        long now = System.currentTimeMillis();
        if (now - lastDeviceRecoveryAt < 5000L) return false;
        lastDeviceRecoveryAt = now;
        try {
            Response r = requestInternal("POST", "api/native_app.php?action=device_login", devicePayload(),
                    8000, 18000, 2_000_000, false, false);
            JSONObject j = r.json();
            boolean ok = r.ok() && j.optBoolean("ok", false) && j.optJSONObject("account") != null;
            if (ok) {
                secureBootstrapAttempted = true;
                return !credential.accessToken().isEmpty() || !cookie().isEmpty();
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void captureAuth(Response response) {
        if (response == null || !response.ok() || response.body.isEmpty()) return;
        try {
            JSONObject auth = response.json().optJSONObject("auth");
            if (auth == null) return;
            String access = auth.optString("access_token", "").trim();
            String refresh = auth.optString("refresh_token", "").trim();
            long accessSec = auth.optLong("access_expires_at", 0L);
            long refreshSec = auth.optLong("refresh_expires_at", 0L);
            if (!access.isEmpty() && !refresh.isEmpty()) {
                credential.saveTokens(access, refresh,
                        accessSec > 0L ? accessSec * 1000L : 0L,
                        refreshSec > 0L ? refreshSec * 1000L : 0L);
                secureBootstrapAttempted = true;
            }
        } catch (Exception ignored) {}
    }

    private boolean isTrustedTarget(String target) {
        try {
            URL server = new URL(base);
            URL url = new URL(target);
            int serverPort = server.getPort() >= 0 ? server.getPort() : server.getDefaultPort();
            int urlPort = url.getPort() >= 0 ? url.getPort() : url.getDefaultPort();
            return server.getProtocol().equalsIgnoreCase(url.getProtocol())
                    && server.getHost().equalsIgnoreCase(url.getHost())
                    && serverPort == urlPort;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isCredentialAction(String target) {
        String lower = target == null ? "" : target.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("native_app.php?action=login")
                || lower.contains("native_app.php?action=register")
                || lower.contains("native_app.php?action=device_login")
                || lower.contains("native_app.php?action=refresh");
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
