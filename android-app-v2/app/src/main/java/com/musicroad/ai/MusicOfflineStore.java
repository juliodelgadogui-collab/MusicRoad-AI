package com.musicroad.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps downloaded MusicRoad tracks attached to their original library metadata.
 *
 * The old UI downloads into MusicRoadOffline/<title>.mp3. This store observes that
 * directory, matches the file against the server catalog and migrates it to the
 * logical MusicRoad/<origin>/<original folder>/ tree. Playback always prefers the
 * local copy while keeping origin/folder/genre unchanged, like Spotify offline.
 */
final class MusicOfflineStore {
    private static final String PREFS = "musicroad_offline_music_v2";
    private static final String KEY_CATALOG = "catalog";
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, JSONObject> CATALOG = new LinkedHashMap<>();
    private static Context app;
    private static boolean loaded = false;
    private static long lastReconcileAt = 0L;

    private static final Runnable FLUSH = MusicOfflineStore::flushNow;

    private MusicOfflineStore() {}

    static void init(Context context) {
        if (context == null) return;
        if (app == null) app = context.getApplicationContext();
        loadIfNeeded();
    }

    static void remember(MusicTrack track) {
        if (track == null) return;
        String src = track.source == null ? "" : track.source.trim().toLowerCase(Locale.ROOT);
        String origin = track.origin == null ? "" : track.origin.trim().toLowerCase(Locale.ROOT);
        if (!(src.startsWith("http://") || src.startsWith("https://"))) return;
        if (origin.contains("radio")) return;
        loadIfNeeded();
        if (app == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("id", track.id);
            o.put("title", track.title);
            o.put("artist", track.artist);
            o.put("album", track.album);
            o.put("source", track.source);
            o.put("origin", track.origin);
            o.put("mime_type", track.mimeType);
            o.put("duration_ms", track.durationMs);
            o.put("file_size", track.fileSize);
            o.put("album_id", track.albumId);
            o.put("genre", track.genre);
            o.put("folder", track.folder);
            o.put("folder_path", track.folderPath);
            synchronized (LOCK) { CATALOG.put(key(track), o); }
            MAIN.removeCallbacks(FLUSH);
            MAIN.postDelayed(FLUSH, 900L);
        } catch (Exception ignored) {}
    }

    static MusicTrack preferLocal(MusicTrack track) {
        if (track == null) return null;
        init(app);
        File f = findLocal(track, true);
        if (f == null || !f.isFile() || f.length() <= 0) return track;
        return cloneWithSource(track, Uri.fromFile(f).toString());
    }

    static boolean isDownloaded(MusicTrack track) {
        return track != null && findLocal(track, true) != null;
    }

    static boolean isDownloadedTitle(String title) {
        if (title == null || title.trim().isEmpty()) return false;
        loadIfNeeded();
        List<JSONObject> snapshot;
        synchronized (LOCK) { snapshot = new ArrayList<>(CATALOG.values()); }
        for (JSONObject o : snapshot) {
            if (!title.trim().equals(o.optString("title", "").trim())) continue;
            MusicTrack t = fromMeta(o);
            if (t != null && findLocal(t, true) != null) return true;
        }
        return false;
    }

    static List<MusicTrack> downloadedTracks() {
        loadIfNeeded();
        reconcileDownloads();
        List<JSONObject> snapshot;
        synchronized (LOCK) { snapshot = new ArrayList<>(CATALOG.values()); }
        ArrayList<MusicTrack> out = new ArrayList<>();
        for (JSONObject o : snapshot) {
            MusicTrack t = fromMeta(o);
            if (t == null) continue;
            File f = findLocal(t, true);
            if (f != null && f.isFile() && f.length() > 0) out.add(cloneWithSource(t, Uri.fromFile(f).toString()));
        }
        return out;
    }

    static void reconcileDownloads() {
        if (app == null) return;
        long now = System.currentTimeMillis();
        if (now - lastReconcileAt < 850L) return;
        lastReconcileAt = now;
        loadIfNeeded();
        File root = app.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (root == null) return;
        File legacy = new File(root, "MusicRoadOffline");
        File[] files = legacy.listFiles();
        if (files == null || files.length == 0) return;
        List<JSONObject> snapshot;
        synchronized (LOCK) { snapshot = new ArrayList<>(CATALOG.values()); }
        for (File f : files) {
            if (f == null || !f.isFile() || f.length() <= 0) continue;
            MusicTrack best = matchLegacy(f, snapshot);
            if (best != null) migrateLegacy(best, f);
        }
    }

    static boolean isNetworkConnected(Context context) {
        if (context == null) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                android.net.Network n = cm.getActiveNetwork();
                if (n == null) return false;
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                return c != null && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
            }
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception ignored) { return false; }
    }

    private static MusicTrack matchLegacy(File file, List<JSONObject> catalog) {
        String stem = stripExt(file.getName());
        MusicTrack first = null;
        MusicTrack exactSize = null;
        for (JSONObject o : catalog) {
            MusicTrack t = fromMeta(o);
            if (t == null) continue;
            if (!safeName(t.title).equalsIgnoreCase(stem)) continue;
            if (first == null) first = t;
            if (t.fileSize > 0 && Math.abs(t.fileSize - file.length()) <= 2048L) {
                exactSize = t;
                break;
            }
        }
        return exactSize != null ? exactSize : first;
    }

    private static File findLocal(MusicTrack track, boolean migrate) {
        if (app == null || track == null) return null;
        String src = track.source == null ? "" : track.source;
        if (src.startsWith("file://")) {
            try {
                File direct = new File(Uri.parse(src).getPath());
                if (direct.isFile() && direct.length() > 0) return direct;
            } catch (Exception ignored) {}
        }
        File root = app.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (root == null) return null;
        File dir = logicalDir(root, track);
        String prefix = stableToken(track) + " - " + safeName(track.title);
        File[] existing = dir.listFiles();
        if (existing != null) {
            for (File f : existing) if (f.isFile() && f.length() > 0 && stripExt(f.getName()).equalsIgnoreCase(prefix)) return f;
        }
        File legacyDir = new File(root, "MusicRoadOffline");
        String legacyStem = safeName(track.title);
        File[] legacyFiles = legacyDir.listFiles();
        if (legacyFiles != null) {
            File fallback = null;
            for (File f : legacyFiles) {
                if (!f.isFile() || f.length() <= 0 || !stripExt(f.getName()).equalsIgnoreCase(legacyStem)) continue;
                if (track.fileSize > 0 && Math.abs(track.fileSize - f.length()) <= 2048L) {
                    fallback = f; break;
                }
                if (fallback == null) fallback = f;
            }
            if (fallback != null) return migrate ? migrateLegacy(track, fallback) : fallback;
        }
        return null;
    }

    private static File migrateLegacy(MusicTrack track, File legacy) {
        if (app == null || track == null || legacy == null || !legacy.isFile()) return null;
        File root = app.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (root == null) return legacy;
        File dir = logicalDir(root, track);
        if (!dir.exists() && !dir.mkdirs()) return legacy;
        String ext = extension(legacy.getName());
        if (ext.isEmpty()) ext = extensionForMime(track.mimeType);
        File target = new File(dir, stableToken(track) + " - " + safeName(track.title) + ext);
        if (target.isFile() && target.length() > 0) {
            if (!target.equals(legacy)) legacy.delete();
            return target;
        }
        if (legacy.renameTo(target)) return target;
        try (FileInputStream in = new FileInputStream(legacy); FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            if (target.length() > 0) { legacy.delete(); return target; }
        } catch (Exception ignored) {}
        if (target.exists() && target.length() == 0) target.delete();
        return legacy;
    }

    private static File logicalDir(File musicRoot, MusicTrack track) {
        File root = new File(musicRoot, "MusicRoad");
        String origin = safeSegment(track.origin == null || track.origin.trim().isEmpty() ? "Biblioteca" : track.origin);
        File dir = new File(root, origin);
        String path = track.folderPath == null || track.folderPath.trim().isEmpty() ? track.folder : track.folderPath;
        if (path != null && !path.trim().isEmpty()) {
            String normalized = path.replace(" \/ ", "/").replace('\\', '/');
            for (String part : normalized.split("/+")) {
                String s = safeSegment(part);
                if (!s.isEmpty() && !"Google Drive".equalsIgnoreCase(s)) dir = new File(dir, s);
            }
        }
        return dir;
    }

    private static String stableToken(MusicTrack t) {
        String id = t.id == null ? "" : t.id.replaceAll("[^A-Za-z0-9_-]+", "");
        if (!id.isEmpty()) return id.length() > 28 ? id.substring(0, 28) : id;
        return Integer.toHexString(key(t).hashCode());
    }

    private static String key(MusicTrack t) {
        String origin = t.origin == null ? "" : t.origin.trim().toLowerCase(Locale.ROOT);
        String id = t.id == null ? "" : t.id.trim();
        if (!id.isEmpty()) return origin + ":" + id;
        return origin + ":" + (t.title == null ? "" : t.title.trim()) + ":" + (t.artist == null ? "" : t.artist.trim());
    }

    private static MusicTrack cloneWithSource(MusicTrack t, String source) {
        return new MusicTrack(t.id, t.title, t.artist, t.album, source, t.origin, t.mimeType,
                t.durationMs, t.fileSize, t.albumId, t.genre, t.folder, t.folderPath);
    }

    private static MusicTrack fromMeta(JSONObject o) {
        if (o == null) return null;
        String source = o.optString("source", "");
        if (!(source.startsWith("http://") || source.startsWith("https://"))) return null;
        return new MusicTrack(o.optString("id", ""), o.optString("title", "Sem título"),
                o.optString("artist", "Artista desconhecido"), o.optString("album", ""), source,
                o.optString("origin", "Servidor"), o.optString("mime_type", ""),
                o.optLong("duration_ms", 0), o.optLong("file_size", 0), o.optLong("album_id", 0),
                o.optString("genre", ""), o.optString("folder", ""), o.optString("folder_path", ""));
    }

    private static void loadIfNeeded() {
        if (loaded || app == null) return;
        synchronized (LOCK) {
            if (loaded) return;
            try {
                SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                JSONArray arr = new JSONArray(p.getString(KEY_CATALOG, "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    MusicTrack t = fromMeta(o);
                    if (t != null) CATALOG.put(key(t), o);
                }
            } catch (Exception ignored) {}
            loaded = true;
        }
    }

    private static void flushNow() {
        if (app == null) return;
        JSONArray arr = new JSONArray();
        synchronized (LOCK) { for (JSONObject o : CATALOG.values()) arr.put(o); }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CATALOG, arr.toString()).apply();
    }

    static String safeName(String value) {
        String s = value == null ? "musica" : value;
        s = s.replaceAll("[\\\\/:*?\"<>|]+", " ").replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) s = "musica";
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    private static String safeSegment(String value) {
        String s = safeName(value);
        if (".".equals(s) || "..".equals(s)) return "Pasta";
        return s;
    }

    private static String stripExt(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    private static String extensionForMime(String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.contains("mp4") || m.contains("m4a")) return ".m4a";
        if (m.contains("aac")) return ".aac";
        if (m.contains("flac")) return ".flac";
        if (m.contains("wav")) return ".wav";
        if (m.contains("ogg")) return ".ogg";
        if (m.contains("opus")) return ".opus";
        return ".mp3";
    }
}
