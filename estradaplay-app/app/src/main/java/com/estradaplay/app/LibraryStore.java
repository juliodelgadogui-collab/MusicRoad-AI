package com.estradaplay.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class LibraryStore {
    private static final String PREFS = "estradaplay_library_v1";
    private static final String KEY_CATALOG = "catalog";
    private static final String KEY_DOWNLOADED = "downloaded";
    private static final String KEY_SETUP = "initial_music_setup_done";
    private final Context app;
    private final SharedPreferences prefs;

    LibraryStore(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void saveCatalog(List<Track> tracks) {
        JSONArray arr = new JSONArray();
        if (tracks != null) for (Track t : tracks) if (t != null) arr.put(t.toStored());
        prefs.edit().putString(KEY_CATALOG, arr.toString()).apply();
    }

    List<Track> catalog() {
        ArrayList<Track> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_CATALOG, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(Track.fromStored(o));
            }
        } catch (Exception ignored) {}
        return out;
    }

    void saveDownloaded(Track track, File file) {
        if (track == null || file == null || !file.isFile() || file.length() <= 0) return;
        try {
            JSONObject all = downloadedObject();
            all.put(track.key(), track.withLocal(file.getAbsolutePath()).toStored());
            prefs.edit().putString(KEY_DOWNLOADED, all.toString()).apply();
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
        } catch (Exception ignored) {}
        return null;
    }

    List<Track> downloadedTracks() {
        ArrayList<Track> out = new ArrayList<>();
        JSONObject all = downloadedObject();
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
            prefs.edit().putString(KEY_DOWNLOADED, all.toString()).apply();
        }
        return out;
    }

    boolean hasSetupDone() { return prefs.getBoolean(KEY_SETUP, false); }
    void setSetupDone(boolean done) { prefs.edit().putBoolean(KEY_SETUP, done).apply(); }

    Set<String> folderNames(List<Track> tracks) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (tracks != null) for (Track t : tracks) if (t != null) out.add(folderKey(t));
        return out;
    }

    Map<String, FolderStat> folderStats(List<Track> tracks) {
        LinkedHashMap<String, FolderStat> out = new LinkedHashMap<>();
        if (tracks == null) return out;
        for (Track t : tracks) {
            if (t == null) continue;
            String key = folderKey(t);
            FolderStat s = out.get(key);
            if (s == null) { s = new FolderStat(key); out.put(key, s); }
            s.total++;
            if (t.size > 0) s.knownBytes += t.size;
            if (localFor(t) != null) s.downloaded++;
        }
        return out;
    }

    List<Track> tracksForFolders(Set<String> folders) {
        ArrayList<Track> out = new ArrayList<>();
        if (folders == null || folders.isEmpty()) return out;
        for (Track t : catalog()) if (folders.contains(folderKey(t)) && localFor(t) == null) out.add(t);
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
