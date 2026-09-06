package com.estradaplay.comunista;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * SESSION_VALIDATION_READ_ONLY_V300
 * Performs the remembered-session device check without accepting Set-Cookie/auth responses,
 * refreshing credentials or otherwise mutating the live login while a background check is in flight.
 */
final class SessionValidationClientV300 {
    private static final int MAX_BODY_CHARS = 2_000_000;

    private SessionValidationClientV300() {}

    static ApiClient.Response validate(Context context, JSONObject payload) throws Exception {
        Context app = context.getApplicationContext();
        ApiClient api = new ApiClient(app);
        SecureDeviceCredential credential = new SecureDeviceCredential(app);
        URL url = new URL(api.absolute("api/native_app.php?action=device_login"));
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalStateException("Validação de sessão exige HTTPS");
        }

        JSONObject body = payload == null ? new JSONObject() : new JSONObject(payload.toString());
        if (!body.has("device_secret")) body.put("device_secret", credential.secret());
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(18000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.setRequestProperty("User-Agent", "EstradaPlay/" + BuildConfig.VERSION_NAME + " Android");
            connection.setRequestProperty("X-MusicRoad-Native", "1");
            connection.setRequestProperty("X-EstradaPlay-Device", DeviceIdentity.token(app));
            connection.setRequestProperty("X-EstradaPlay-Device-Label", DeviceIdentity.label());
            connection.setRequestProperty("X-EstradaPlay-Device-Secret", credential.secret());

            String cookie = api.cookie();
            if (cookie != null && !cookie.trim().isEmpty()) {
                connection.setRequestProperty("Cookie", cookie.trim());
            }

            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }

            int code = connection.getResponseCode();
            InputStream raw = code >= 200 && code < 400
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            InputStream in = raw;
            String encoding = connection.getContentEncoding();
            if (raw != null && encoding != null && encoding.toLowerCase(java.util.Locale.ROOT).contains("gzip")) {
                in = new java.util.zip.GZIPInputStream(raw);
            }
            return new ApiClient.Response(code, read(in));
        } finally {
            if (connection != null) {
                try { connection.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    /** Fingerprint only; raw cookie/tokens never leave process memory or enter logs. */
    static String sessionFingerprint(Context context) {
        try {
            Context app = context.getApplicationContext();
            ApiClient api = new ApiClient(app);
            SecureDeviceCredential credential = new SecureDeviceCredential(app);
            String raw = safe(api.cookie()) + "\n"
                    + safe(credential.accessToken()) + "\n"
                    + safe(credential.refreshToken());
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder out = new StringBuilder(16384);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8), 16384)) {
            char[] buffer = new char[16384];
            int count;
            while ((count = reader.read(buffer)) > 0) {
                out.append(buffer, 0, count);
                if (out.length() > MAX_BODY_CHARS) {
                    throw new IllegalStateException("Resposta de validação maior que o limite permitido");
                }
            }
        }
        return out.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
