package com.musicroad.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Resolves MusicRoad Google Drive tracks to a short-lived Google media URL.
 *
 * Audio bytes should normally travel Google Drive -> Android. The MusicRoad
 * server is only asked for a small JSON resolution response and never needs to
 * store the song. Resolutions are kept briefly in memory and the next queue
 * item can be resolved ahead of time.
 */
final class DriveMediaResolver {
    private static final long TTL_MS = 7 * 60 * 1000L;
    private static final int MAX_CACHE = 48;
    private static final Object LOCK = new Object();
    private static final ExecutorService PREFETCH = Executors.newSingleThreadExecutor();
    private static final LinkedHashMap<String, CacheEntry> CACHE = new LinkedHashMap<String, CacheEntry>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > MAX_CACHE;
        }
    };

    private DriveMediaResolver() {}

    static final class Result {
        final boolean ok;
        final String url;
        final String mime;
        final String mode;
        final String error;
        final boolean cached;

        Result(boolean ok, String url, String mime, String mode, String error, boolean cached) {
            this.ok = ok;
            this.url = url == null ? "" : url;
            this.mime = mime == null ? "" : mime;
            this.mode = mode == null ? "" : mode;
            this.error = error == null ? "" : error;
            this.cached = cached;
        }
    }

    private static final class CacheEntry {
        final Result result;
        final long savedAt;
        CacheEntry(Result result, long savedAt) { this.result = result; this.savedAt = savedAt; }
    }

    static boolean isDriveTrack(MusicTrack track) {
        if (track == null) return false;
        String origin = track.origin == null ? "" : track.origin.toLowerCase(Locale.ROOT);
        String source = track.source == null ? "" : track.source.toLowerCase(Locale.ROOT);
        return origin.contains("drive") || source.contains("/api/drive_stream.php") || source.contains("drive.google.com") || source.contains("drive.usercontent.google.com");
    }

    static Result resolve(Context context, MusicTrack track, boolean forceRefresh) {
        if (track == null || track.source == null || track.source.trim().isEmpty()) {
            return new Result(false, "", "", "ERROR", "Fonte de áudio vazia.", false);
        }
        String source = track.source.trim();
        Uri uri = Uri.parse(source);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);

        if ("file".equals(scheme) || "content".equals(scheme)) {
            return new Result(true, source, track.mimeType, "OFFLINE", "", false);
        }
        if (!("http".equals(scheme) || "https".equals(scheme))) {
            return new Result(true, source, track.mimeType, "DEVICE", "", false);
        }
        if (isGoogleMediaHost(uri.getHost()) && !source.contains("/api/drive_stream.php")) {
            return new Result(true, source, track.mimeType, "DRIVE_DIRECT", "", false);
        }
        if (!isDriveTrack(track) || !source.contains("/api/drive_stream.php")) {
            return new Result(true, source, track.mimeType, "HTTP", "", false);
        }

        String key = source;
        if (!forceRefresh) {
            synchronized (LOCK) {
                CacheEntry e = CACHE.get(key);
                if (e != null && System.currentTimeMillis() - e.savedAt < TTL_MS && e.result.ok) {
                    return new Result(true, e.result.url, e.result.mime, e.result.mode, "", true);
                }
            }
        } else {
            synchronized (LOCK) { CACHE.remove(key); }
        }

        HttpURLConnection connection = null;
        try {
            String resolveUrl = source + (source.contains("?") ? "&" : "?") + "resolve=1&v=230";
            connection = (HttpURLConnection) new URL(resolveUrl).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(9000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "MusicRoadAndroid/" + BuildConfig.VERSION_NAME);
            String cookie = nativeCookie(context);
            if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 400 ? connection.getInputStream() : connection.getErrorStream();
            String body = readAll(stream);
            if (code < 200 || code >= 300) {
                return new Result(false, "", "", "DRIVE_RESOLVE", "Drive HTTP " + code + (body.isEmpty() ? "" : ": " + shortText(body)), false);
            }
            JSONObject json = new JSONObject(body);
            String direct = json.optString("direct_url", json.optString("url", "")).trim();
            String mime = json.optString("content_type", track.mimeType == null ? "" : track.mimeType);
            if (!json.optBoolean("ok", false) || direct.isEmpty()) {
                return new Result(false, "", mime, "DRIVE_RESOLVE", json.optString("message", "Google Drive não retornou uma URL de mídia."), false);
            }
            Uri directUri = Uri.parse(direct);
            if (!"https".equalsIgnoreCase(directUri.getScheme()) || !isGoogleMediaHost(directUri.getHost())) {
                return new Result(false, "", mime, "DRIVE_RESOLVE", "URL direta do Drive foi rejeitada por segurança.", false);
            }
            Result result = new Result(true, direct, mime, "DRIVE_DIRECT", "", json.optBoolean("cached", false));
            synchronized (LOCK) { CACHE.put(key, new CacheEntry(result, System.currentTimeMillis())); }
            return result;
        } catch (Exception e) {
            return new Result(false, "", "", "DRIVE_RESOLVE", e.getMessage() == null ? "Falha ao resolver Google Drive." : e.getMessage(), false);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static void invalidate(MusicTrack track) {
        if (track == null || track.source == null) return;
        synchronized (LOCK) { CACHE.remove(track.source.trim()); }
    }

    static void prefetch(Context context, MusicTrack track) {
        if (context == null || track == null || !isDriveTrack(track)) return;
        Context app = context.getApplicationContext();
        PREFETCH.execute(() -> resolve(app, track, false));
    }

    static String nativeCookie(Context context) {
        if (context == null) return "";
        try {
            SharedPreferences p = context.getSharedPreferences("musicroad_native_api_v1", Context.MODE_PRIVATE);
            String cookie = p.getString("cookie", "");
            return cookie == null ? "" : cookie.trim();
        } catch (Exception ignored) { return ""; }
    }

    private static boolean isGoogleMediaHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("google.com") || h.endsWith(".google.com") || h.equals("googleusercontent.com") || h.endsWith(".googleusercontent.com") || h.equals("googleapis.com") || h.endsWith(".googleapis.com");
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) {
                out.append(buf, 0, n);
                if (out.length() > 131072) break;
            }
        }
        return out.toString();
    }

    private static String shortText(String value) {
        String v = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return v.length() > 180 ? v.substring(0, 180) : v;
    }
}
