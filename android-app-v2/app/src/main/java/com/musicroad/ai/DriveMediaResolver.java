package com.musicroad.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Google Drive media resolver used by the native Android player.
 *
 * MusicRoad 2.3.1 deliberately does NOT ask the server to follow Google's
 * download redirects and then reuse the server's final URL on the phone. Some
 * Google download URLs/tokens are tied to the request that created them and can
 * fail when reused from another network/client.
 *
 * Instead the phone builds a stable public Drive download URL from file_id +
 * resourcekey and follows Google's redirects itself. Audio bytes therefore go
 * Google Drive -> Android. The MusicRoad server remains only a compatibility
 * rescue path and never stores the song.
 */
final class DriveMediaResolver {
    private static final long TTL_MS = 30 * 60 * 1000L;
    private static final int MAX_CACHE = 96;
    private static final Object LOCK = new Object();
    private static final ExecutorService PREFETCH = Executors.newSingleThreadExecutor();
    private static final LinkedHashMap<String, CacheEntry> CACHE = new LinkedHashMap<String, CacheEntry>(128, .75f, true) {
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
        return origin.contains("drive")
                || source.contains("/api/drive_stream.php")
                || source.contains("drive.google.com")
                || source.contains("drive.usercontent.google.com")
                || source.contains("googleusercontent.com");
    }

    static Result resolve(Context context, MusicTrack track, boolean forceRefresh) {
        if (track == null || track.source == null || track.source.trim().isEmpty()) {
            return new Result(false, "", "", "ERROR", "Fonte de áudio vazia.", false);
        }

        String source = track.source.trim();
        Uri sourceUri = Uri.parse(source);
        String scheme = sourceUri.getScheme() == null ? "" : sourceUri.getScheme().toLowerCase(Locale.ROOT);

        if ("file".equals(scheme) || "content".equals(scheme)) {
            return new Result(true, source, track.mimeType, "OFFLINE", "", false);
        }
        if (!("http".equals(scheme) || "https".equals(scheme))) {
            return new Result(true, source, track.mimeType, "DEVICE", "", false);
        }
        if (!isDriveTrack(track)) {
            return new Result(true, source, track.mimeType, "HTTP", "", false);
        }

        DriveRef ref = extractDriveRef(sourceUri, source);
        if (ref.id.isEmpty()) {
            // A genuine Google media URL may already be usable as-is. For a
            // MusicRoad endpoint without an id, keep the server rescue source.
            if (isGoogleMediaHost(sourceUri.getHost())) {
                return new Result(true, source, track.mimeType, "DRIVE_DIRECT", "", false);
            }
            return new Result(false, "", track.mimeType, "DRIVE_REF", "ID do arquivo do Google Drive não encontrado.", false);
        }

        int candidate = forceRefresh ? 1 : 0;
        String cacheKey = ref.id + "|" + ref.resourceKey + "|" + candidate;
        if (!forceRefresh) {
            synchronized (LOCK) {
                CacheEntry e = CACHE.get(cacheKey);
                if (e != null && System.currentTimeMillis() - e.savedAt < TTL_MS && e.result.ok) {
                    return new Result(true, e.result.url, e.result.mime, e.result.mode, "", true);
                }
            }
        }

        String direct = candidateUrl(ref.id, ref.resourceKey, candidate);
        Result result = new Result(true, direct, track.mimeType,
                candidate == 0 ? "DRIVE_DIRECT" : "DRIVE_DIRECT_ALT", "", false);
        synchronized (LOCK) { CACHE.put(cacheKey, new CacheEntry(result, System.currentTimeMillis())); }
        return result;
    }

    static void invalidate(MusicTrack track) {
        if (track == null || track.source == null) return;
        DriveRef ref = extractDriveRef(Uri.parse(track.source), track.source);
        synchronized (LOCK) {
            if (ref.id.isEmpty()) {
                CACHE.clear();
                return;
            }
            String prefix = ref.id + "|" + ref.resourceKey + "|";
            CACHE.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
        }
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

    static String serverRescueUrl(MusicTrack track) {
        if (track == null || track.source == null) return "";
        String source = track.source.trim();
        if (source.contains("/api/drive_stream.php")) return source;
        DriveRef ref = extractDriveRef(Uri.parse(source), source);
        if (ref.id.isEmpty()) return source;
        String base = NativeApiClient.normalizeBase(BuildConfig.MUSICROAD_URL);
        String out = base + "api/drive_stream.php?id=" + Uri.encode(ref.id);
        if (!ref.resourceKey.isEmpty()) out += "&resourcekey=" + Uri.encode(ref.resourceKey);
        return out;
    }

    private static String candidateUrl(String id, String resourceKey, int candidate) {
        String encodedId = Uri.encode(id);
        String rk = resourceKey.isEmpty() ? "" : "&resourcekey=" + Uri.encode(resourceKey);
        if (candidate == 1) {
            return "https://drive.google.com/uc?export=download&confirm=t&id=" + encodedId + rk;
        }
        return "https://drive.usercontent.google.com/download?id=" + encodedId
                + "&export=download&authuser=0&confirm=t" + rk;
    }

    private static DriveRef extractDriveRef(Uri uri, String raw) {
        String id = "";
        String rk = "";
        try {
            if (uri != null) {
                String qid = uri.getQueryParameter("id");
                String qrk = uri.getQueryParameter("resourcekey");
                if (qid != null) id = sanitize(qid);
                if (qrk != null) rk = sanitize(qrk);
                if (id.isEmpty()) {
                    String path = uri.getPath() == null ? "" : uri.getPath();
                    int marker = path.indexOf("/file/d/");
                    if (marker >= 0) {
                        String tail = path.substring(marker + 8);
                        int slash = tail.indexOf('/');
                        id = sanitize(slash >= 0 ? tail.substring(0, slash) : tail);
                    }
                }
            }
        } catch (Exception ignored) {}

        if (id.isEmpty() && raw != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:[?&]id=|/file/d/)([A-Za-z0-9_-]{10,})", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(raw);
            if (m.find()) id = sanitize(m.group(1));
        }
        if (rk.isEmpty() && raw != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&]resourcekey=([A-Za-z0-9_-]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(raw);
            if (m.find()) rk = sanitize(m.group(1));
        }
        return new DriveRef(id, rk);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9_-]", "");
    }

    private static boolean isGoogleMediaHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("google.com") || h.endsWith(".google.com")
                || h.equals("googleusercontent.com") || h.endsWith(".googleusercontent.com")
                || h.equals("googleapis.com") || h.endsWith(".googleapis.com");
    }

    private static final class DriveRef {
        final String id;
        final String resourceKey;
        DriveRef(String id, String resourceKey) {
            this.id = id == null ? "" : id;
            this.resourceKey = resourceKey == null ? "" : resourceKey;
        }
    }
}
