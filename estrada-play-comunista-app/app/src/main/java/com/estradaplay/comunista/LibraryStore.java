package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class LibraryStore {
    private static final String PREFS = "estradaplay_library_v1";
    private static final String FLAGS_PREFS = "estradaplay_library_flags_v211";
    private static final String KEY_DOWNLOADED_HINT = "downloaded_hint";
    private static final String KEY_CATALOG = "catalog";
    private static final String KEY_DOWNLOADED = "downloaded";
    private static final String KEY_SETUP = "initial_music_setup_done";
    private static final int MAX_CATALOG_BYTES = 32 * 1024 * 1024;
    private final Context app;
    private final SharedPreferences prefs;
    private final SharedPreferences flags;
    private final File catalogFile;
    // ANR_LIBRARY_INDEX_V210: large catalog/download indexes are cached in memory.
    private volatile List<Track> catalogCache;
    private volatile List<Track> downloadedCache;
    private volatile String downloadedCacheRaw;

    LibraryStore(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        flags = app.getSharedPreferences(FLAGS_PREFS, Context.MODE_PRIVATE);
        File dir = new File(app.getFilesDir(), "estradaplay_library");
        catalogFile = new File(dir, "catalog.json");
    }

    // CATALOG_FILE_V208: large catalogs are stored atomically as a file, not in SharedPreferences.
    synchronized void saveCatalog(List<Track> tracks) {
        JSONArray arr = new JSONArray();
        if (tracks != null) for (Track t : tracks) if (t != null) arr.put(t.toStored());
        byte[] bytes = arr.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CATALOG_BYTES) return;
        File dir = catalogFile.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) return;
        File tmp = new File(catalogFile.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.flush();
        } catch (Exception e) {
            tmp.delete();
            return;
        }
        if (catalogFile.exists() && !catalogFile.delete()) { tmp.delete(); return; }
        if (!tmp.renameTo(catalogFile)) { tmp.delete(); return; }
        catalogCache = tracks == null ? new ArrayList<>() : new ArrayList<>(tracks);
        prefs.edit().remove(KEY_CATALOG).apply();
    }

    synchronized List<Track> catalog() {
        List<Track> cached = catalogCache;
        if (cached != null) return new ArrayList<>(cached);
        ArrayList<Track> out = new ArrayList<>();
        String raw = readCatalogFile();
        if (raw == null || raw.isEmpty()) {
            raw = prefs.getString(KEY_CATALOG, "[]");
            if (raw != null && raw.length() > 2) {
                try {
                    JSONArray legacy = new JSONArray(raw);
                    ArrayList<Track> migrated = new ArrayList<>();
                    for (int i = 0; i < legacy.length(); i++) {
                        JSONObject o = legacy.optJSONObject(i);
                        if (o != null) migrated.add(Track.fromStored(o));
                    }
                    if (!migrated.isEmpty()) saveCatalog(migrated);
                } catch (Exception ignored) {}
            }
        }
        try {
            JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(Track.fromStored(o));
            }
        } catch (Exception ignored) {}
        catalogCache = new ArrayList<>(out);
        return out;
    }

    private String readCatalogFile() {
        if (!catalogFile.isFile() || catalogFile.length() <= 0 || catalogFile.length() > MAX_CATALOG_BYTES) return null;
        try (FileInputStream in = new FileInputStream(catalogFile); ByteArrayOutputStream out = new ByteArrayOutputStream((int)Math.min(catalogFile.length(), 1024 * 1024))) {
            byte[] buf = new byte[32768];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_CATALOG_BYTES) return null;
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return null;
        }
    }

    void saveDownloaded(Track track, File file) {
        if (track == null || file == null || !file.isFile() || file.length() <= 0) return;
        try {
            JSONObject all = downloadedObject();
            all.put(track.key(), track.withLocal(file.getAbsolutePath()).toStored());
            String raw = all.toString();
            prefs.edit().putString(KEY_DOWNLOADED, raw).apply();
            flags.edit().putBoolean(KEY_DOWNLOADED_HINT, true).apply();
            downloadedCache = null; downloadedCacheRaw = null;
        } catch (Exception ignored) {}
    }

    Track localFor(Track track) {
        if (track == null) return null;
        try {
            JSONObject all = downloadedObject();
            JSONObject o = all.optJSONObject(track.key());
            if (o == null) return null;
            Track stored = Track.fromStored(o);
            File f = stored.localPath.isEmpty() ? null : new File(stored.localPath);
            if (f != null && f.isFile() && f.length() > 0) return stored;
            all.remove(track.key());
            prefs.edit().putString(KEY_DOWNLOADED, all.toString()).apply();
            downloadedCache = null; downloadedCacheRaw = null;
        } catch (Exception ignored) {}
        return null;
    }

    List<Track> downloadedTracks() {
        String raw = prefs.getString(KEY_DOWNLOADED, "{}");
        if (raw == null) raw = "{}";
        List<Track> cached = downloadedCache;
        String cachedRaw = downloadedCacheRaw;
        if (cached != null && raw.equals(cachedRaw)) return new ArrayList<>(cached);

        ArrayList<Track> out = new ArrayList<>();
        JSONObject all;
        try { all = new JSONObject(raw); } catch (Exception e) { all = new JSONObject(); }
        ArrayList<String> stale = new ArrayList<>();
        java.util.Iterator<String> it = all.keys();
        while (it.hasNext()) {
            String key = it.next();
            JSONObject o = all.optJSONObject(key);
            if (o == null) continue;
            Track t = Track.fromStored(o);
            File f = t.localPath.isEmpty() ? null : new File(t.localPath);
            if (f != null && f.isFile() && f.length() > 0) out.add(t); else stale.add(key);
        }
        if (!stale.isEmpty()) {
            for (String key : stale) all.remove(key);
            raw = all.toString();
            prefs.edit().putString(KEY_DOWNLOADED, raw).apply();
        }
        downloadedCacheRaw = raw;
        downloadedCache = new ArrayList<>(out);
        flags.edit().putBoolean(KEY_DOWNLOADED_HINT, !out.isEmpty()).apply();
        return out;
    }

    // ANR_BOOT_FLAGS_V211: never read the potentially multi-megabyte download index on UI boot.
    // Default true preserves existing installs; tapping Music will validate the real index on the IO executor.
    boolean hasDownloadedHint() { return flags.getBoolean(KEY_DOWNLOADED_HINT, true); }

    boolean hasSetupDone() { return flags.getBoolean(KEY_SETUP, false); }
    void setSetupDone(boolean done) { flags.edit().putBoolean(KEY_SETUP, done).apply(); }

    Set<String> folderNames(List<Track> tracks) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (tracks != null) for (Track t : tracks) if (t != null) out.add(folderKey(t));
        return out;
    }

    Map<String, FolderStat> folderStats(List<Track> tracks) {
        LinkedHashMap<String, FolderStat> out = new LinkedHashMap<>();
        if (tracks == null) return out;
        LinkedHashSet<String> offlineKeys = new LinkedHashSet<>();
        for (Track local : downloadedTracks()) if (local != null) offlineKeys.add(local.key());
        for (Track t : tracks) {
            if (t == null) continue;
            String key = folderKey(t);
            FolderStat stat = out.get(key);
            if (stat == null) { stat = new FolderStat(key); out.put(key, stat); }
            stat.total++;
            if (t.size > 0) stat.knownBytes += t.size;
            if (offlineKeys.contains(t.key())) stat.downloaded++;
        }
        return out;
    }

    List<Track> tracksForFolders(Set<String> folders) {
        ArrayList<Track> out = new ArrayList<>();
        if (folders == null || folders.isEmpty()) return out;
        LinkedHashSet<String> offlineKeys = new LinkedHashSet<>();
        for (Track local : downloadedTracks()) if (local != null) offlineKeys.add(local.key());
        for (Track t : catalog()) {
            if (t == null) continue;
            if (folders.contains(folderKey(t)) && !offlineKeys.contains(t.key())) out.add(t);
        }
        return out;
    }

    File targetFile(Track track) {
        File root = app.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (root == null) root = app.getFilesDir();
        File dir = new File(root, "EstradaPlay");
        String path = track.folderPath.isEmpty() ? track.folder : track.folderPath;
        for (String part : path.replace(" / ", "/").replace('\\', '/').split("/+")) {
            String safe = safeSegment(part);
            if (!safe.isEmpty()) dir = new File(dir, safe);
        }
        String token = track.id.replaceAll("[^A-Za-z0-9_-]+", "");
        if (token.isEmpty()) token = Integer.toHexString(track.key().hashCode());
        if (token.length() > 28) token = token.substring(0, 28);
        return new File(dir, token + " - " + safeName(track.title) + extension(track));
    }

    long freeBytes() {
        File root = app.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (root == null) root = app.getFilesDir();
        return root.getUsableSpace();
    }

    int removeFolder(String folder) {
        int removed = 0;
        JSONObject all = downloadedObject();
        ArrayList<String> keys = new ArrayList<>();
        java.util.Iterator<String> it = all.keys();
        while (it.hasNext()) {
            String key = it.next();
            JSONObject o = all.optJSONObject(key);
            if (o == null) continue;
            Track t = Track.fromStored(o);
            if (!folder.equals(folderKey(t))) continue;
            File f = t.localPath.isEmpty() ? null : new File(t.localPath);
            if (f != null && f.isFile() && f.delete()) removed++;
            keys.add(key);
        }
        for (String key : keys) all.remove(key);
        prefs.edit().putString(KEY_DOWNLOADED, all.toString()).apply();
        flags.edit().putBoolean(KEY_DOWNLOADED_HINT, all.length() > 0).apply();
        downloadedCache = null; downloadedCacheRaw = null;
        return removed;
    }

    static String folderKey(Track t) {
        if (t == null) return "Sem pasta";
        String p = t.folderPath == null ? "" : t.folderPath.trim();
        if (p.isEmpty()) p = t.folder == null ? "" : t.folder.trim();
        return p.isEmpty() ? "Sem pasta" : p;
    }

    private JSONObject downloadedObject() {
        try { return new JSONObject(prefs.getString(KEY_DOWNLOADED, "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    private static String extension(Track t) {
        String m = t.mime == null ? "" : t.mime.toLowerCase(Locale.ROOT);
        if (m.contains("mpeg")) return ".mp3";
        if (m.contains("mp4") || m.contains("m4a")) return ".m4a";
        if (m.contains("aac")) return ".aac";
        if (m.contains("flac")) return ".flac";
        if (m.contains("wav")) return ".wav";
        if (m.contains("opus")) return ".opus";
        if (m.contains("ogg")) return ".ogg";
        String s = t.remoteSource == null ? "" : t.remoteSource.toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".mp3", ".m4a", ".aac", ".flac", ".wav", ".opus", ".ogg"}) if (s.contains(ext)) return ext;
        return ".mp3";
    }

    static String safeName(String value) {
        String s = value == null ? "musica" : value;
        s = s.replaceAll("[\\\\/:*?\"<>|]+", " ").replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) s = "musica";
        return s.length() > 90 ? s.substring(0, 90) : s;
    }

    private static String safeSegment(String value) {
        String s = safeName(value);
        return ".".equals(s) || "..".equals(s) ? "Pasta" : s;
    }

    static final class FolderStat {
        final String name;
        int total;
        int downloaded;
        long knownBytes;
        FolderStat(String name) { this.name = name; }
        boolean complete() { return total > 0 && downloaded >= total; }
    }
}
