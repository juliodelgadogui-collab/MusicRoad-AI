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
import java.util.HashMap;
import java.util.HashSet;
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

    synchronized void saveCatalog(List<Track> tracks) {
        JSONArray arr = new JSONArray();
        if (tracks != null) for (Track t : tracks) if (t != null) arr.put(t.toStored());
        byte[] bytes = arr.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CATALOG_BYTES) return;
        File dir = catalogFile.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) return;
        File tmp = new File(catalogFile.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes); out.flush();
        } catch (Exception e) { tmp.delete(); return; }
        if (catalogFile.exists() && !catalogFile.delete()) { tmp.delete(); return; }
        if (!tmp.renameTo(catalogFile)) { tmp.delete(); return; }
        catalogCache = tracks == null ? new ArrayList<>() : new ArrayList<>(tracks);
        prefs.edit().remove(KEY_CATALOG).apply();
        // BIBLIOTECA_SEGURA_V202: a catalog refresh can reveal files whose index was lost.
        downloadedCache = null; downloadedCacheRaw = null;
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
            byte[] buf = new byte[32768]; int n; int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n; if (total > MAX_CATALOG_BYTES) return null; out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) { return null; }
    }

    synchronized void saveDownloaded(Track track, File file) {
        if (track == null || file == null || !file.isFile() || file.length() <= 0) return;
        try {
            JSONObject all = downloadedObject();
            all.put(track.key(), track.withLocal(file.getAbsolutePath()).toStored());
            persistDownloaded(all);
        } catch (Exception ignored) {}
    }

    Track localFor(Track track) {
        if (track == null) return null;
        // Always give reconciliation a chance before declaring a catalog item missing.
        reconcileOffline();
        try {
            JSONObject all = downloadedObject();
            JSONObject o = all.optJSONObject(track.key());
            if (o == null) return null;
            Track stored = Track.fromStored(o);
            File f = stored.localPath.isEmpty() ? null : new File(stored.localPath);
            if (validAudioFile(f)) return stored;
            all.remove(track.key()); persistDownloaded(all);
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Rebuilds the offline index from the real files on disk. This repairs cases where the APK was
     * upgraded/restored and SharedPreferences no longer reflects files that are still present.
     */
    // OFFLINE_DISCOVERY_V203: rebuild from every real app-owned audio file, even if IDs/folders changed.
    synchronized ReconcileResult reconcileOffline() {
        JSONObject previous = downloadedObject();
        List<File> roots = ownedMusicRoots();
        List<File> files = allOwnedAudioFiles(roots);
        Set<String> livePaths = new HashSet<>();
        for (File f : files) livePaths.add(canonical(f));

        int before = previous.length();
        int stale = 0;
        Map<String, Track> previousByPath = new HashMap<>();
        java.util.Iterator<String> oldIt = previous.keys();
        while (oldIt.hasNext()) {
            String key = oldIt.next();
            JSONObject o = previous.optJSONObject(key);
            if (o == null) { stale++; continue; }
            Track t = Track.fromStored(o);
            if (t.localPath.isEmpty()) { stale++; continue; }
            File f = new File(t.localPath);
            String cp = canonical(f);
            if (!validAudioFile(f) || !livePaths.contains(cp)) { stale++; continue; }
            previousByPath.put(cp, t);
        }

        JSONObject next = new JSONObject();
        Set<String> used = new HashSet<>();
        int recovered = 0;
        List<Track> currentCatalog = catalog();
        for (Track t : currentCatalog) {
            if (t == null) continue;
            File found = bestOwnedFile(t, files, used, previous);
            if (!validAudioFile(found)) continue;
            String cp = canonical(found);
            try {
                next.put(t.key(), t.withLocal(found.getAbsolutePath()).toStored());
                used.add(cp);
                JSONObject old = previous.optJSONObject(t.key());
                String oldPath = old == null ? "" : Track.fromStored(old).localPath;
                if (oldPath.isEmpty() || !canonical(new File(oldPath)).equals(cp)) recovered++;
            } catch (Exception ignored) {}
        }

        // Never hide a valid local file merely because the server catalog changed.
        for (File f : files) {
            String cp = canonical(f);
            if (used.contains(cp)) continue;
            Track existing = previousByPath.get(cp);
            Track local = existing != null ? existing.withLocal(f.getAbsolutePath()) : syntheticLocalTrack(f, roots);
            String key = local.key();
            if (next.has(key)) {
                local = syntheticLocalTrack(f, roots);
                key = local.key();
            }
            try {
                next.put(key, local.toStored());
                used.add(cp);
                if (existing == null) recovered++;
            } catch (Exception ignored) {}
        }

        persistDownloaded(next);
        return new ReconcileResult(before, next.length(), recovered, stale);
    }

    private File bestOwnedFile(Track track, List<File> files, Set<String> used, JSONObject previous) {
        if (track == null) return null;
        JSONObject old = previous.optJSONObject(track.key());
        if (old != null) {
            Track stored = Track.fromStored(old);
            if (!stored.localPath.isEmpty()) {
                File exactOld = new File(stored.localPath);
                if (validAudioFile(exactOld) && !used.contains(canonical(exactOld))) return exactOld;
            }
        }
        File expected = targetFile(track);
        if (validAudioFile(expected) && !used.contains(canonical(expected))) return expected;

        String id = track.id == null ? "" : track.id.replaceAll("[^A-Za-z0-9_-]+", "").toLowerCase(Locale.ROOT);
        String title = normalizeFileToken(track.title);
        String folder = normalizeFileToken(folderKey(track));
        File best = null; int bestScore = 0;
        for (File f : files) {
            String cp = canonical(f);
            if (used.contains(cp)) continue;
            String rawName = stripExtension(f.getName());
            String name = normalizeFileToken(rawName);
            String path = normalizeFileToken(cp);
            int score = 0;
            if (!id.isEmpty() && f.getName().toLowerCase(Locale.ROOT).contains(id)) score += 1000;
            if (title.length() >= 3) {
                if (name.equals(title)) score += 650;
                else if (name.endsWith(title)) score += 560;
                else if (name.contains(title)) score += 430;
            }
            if (!folder.isEmpty() && path.contains(folder)) score += 120;
            if (score > bestScore) { bestScore = score; best = f; }
        }
        return bestScore >= 430 ? best : null;
    }

    private Track syntheticLocalTrack(File file, List<File> roots) {
        String base = stripExtension(file.getName());
        int sep = base.indexOf(" - ");
        String title = sep >= 0 && sep + 3 < base.length() ? base.substring(sep + 3).trim() : base.trim();
        if (title.isEmpty()) title = "Música recuperada";
        String folder = relativeFolder(file, roots);
        String id = "local_" + Integer.toHexString(canonical(file).hashCode());
        return new Track(id, title, "Biblioteca recuperada", "", folder, folder,
                mimeForName(file.getName()), "", file.getAbsolutePath(), file.length(), 0L);
    }

    private String relativeFolder(File file, List<File> roots) {
        String cp = canonical(file);
        for (File root : roots) {
            String rp = canonical(root);
            if (cp.startsWith(rp + File.separator)) {
                String rel = cp.substring(rp.length() + 1);
                int slash = rel.lastIndexOf(File.separatorChar);
                if (slash > 0) return rel.substring(0, slash).replace(File.separatorChar, '/');
            }
        }
        File parent = file.getParentFile();
        return parent == null ? "Recuperadas" : parent.getName();
    }

    private static String mimeForName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".aac")) return "audio/aac";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".opus")) return "audio/opus";
        if (n.endsWith(".ogg")) return "audio/ogg";
        return "audio/*";
    }

    List<Track> downloadedTracks() {
        reconcileOffline();
        String raw = prefs.getString(KEY_DOWNLOADED, "{}"); if (raw == null) raw = "{}";
        List<Track> cached = downloadedCache; String cachedRaw = downloadedCacheRaw;
        if (cached != null && raw.equals(cachedRaw)) return new ArrayList<>(cached);
        ArrayList<Track> out = new ArrayList<>(); JSONObject all;
        try { all = new JSONObject(raw); } catch (Exception e) { all = new JSONObject(); }
        ArrayList<String> stale = new ArrayList<>(); java.util.Iterator<String> it = all.keys();
        while (it.hasNext()) {
            String key = it.next(); JSONObject o = all.optJSONObject(key); if (o == null) continue;
            Track t = Track.fromStored(o); File f = t.localPath.isEmpty() ? null : new File(t.localPath);
            if (validAudioFile(f)) out.add(t); else stale.add(key);
        }
        if (!stale.isEmpty()) { for (String key : stale) all.remove(key); raw = all.toString(); persistDownloaded(all); }
        downloadedCacheRaw = raw; downloadedCache = new ArrayList<>(out); updateDownloadedHint(!out.isEmpty()); return out;
    }

    boolean hasDownloadedHint() { return flags.getBoolean(KEY_DOWNLOADED_HINT, true); }
    boolean hasSetupDone() { return flags.getBoolean(KEY_SETUP, false); }
    void setSetupDone(boolean done) { flags.edit().putBoolean(KEY_SETUP, done).apply(); }

    Set<String> folderNames(List<Track> tracks) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (tracks != null) for (Track t : tracks) if (t != null) out.add(folderKey(t));
        return out;
    }

    Map<String, FolderStat> folderStats(List<Track> tracks) {
        LinkedHashMap<String, FolderStat> out = new LinkedHashMap<>(); if (tracks == null) return out;
        LinkedHashSet<String> offlineKeys = new LinkedHashSet<>(); for (Track local : downloadedTracks()) if (local != null) offlineKeys.add(local.key());
        for (Track t : tracks) {
            if (t == null) continue; String key = folderKey(t); FolderStat stat = out.get(key);
            if (stat == null) { stat = new FolderStat(key); out.put(key, stat); }
            stat.total++; if (t.size > 0) stat.knownBytes += t.size; if (offlineKeys.contains(t.key())) stat.downloaded++;
        }
        return out;
    }

    List<Track> tracksForFolders(Set<String> folders) {
        ArrayList<Track> out = new ArrayList<>(); if (folders == null || folders.isEmpty()) return out;
        LinkedHashSet<String> offlineKeys = new LinkedHashSet<>(); for (Track local : downloadedTracks()) if (local != null) offlineKeys.add(local.key());
        for (Track t : catalog()) if (t != null && folders.contains(folderKey(t)) && !offlineKeys.contains(t.key())) out.add(t);
        return out;
    }

    File targetFile(Track track) {
        File dir = musicRoot();
        String path = track.folderPath.isEmpty() ? track.folder : track.folderPath;
        for (String part : path.replace(" / ", "/").replace('\\', '/').split("/+")) {
            String safe = safeSegment(part); if (!safe.isEmpty()) dir = new File(dir, safe);
        }
        String token = track.id.replaceAll("[^A-Za-z0-9_-]+", "");
        if (token.isEmpty()) token = Integer.toHexString(track.key().hashCode());
        if (token.length() > 28) token = token.substring(0, 28);
        return new File(dir, token + " - " + safeName(track.title) + extension(track));
    }

    // New downloads live in internal no-backup storage: uninstall removes them with the package.
    File musicRoot() {
        return new File(app.getNoBackupFilesDir(), "EstradaPlayMusic");
    }

    private List<File> ownedMusicRoots() {
        LinkedHashMap<String, File> roots = new LinkedHashMap<>();
        addOwnedRoot(roots, musicRoot());
        File[] external = app.getExternalFilesDirs(Environment.DIRECTORY_MUSIC);
        if (external != null) for (File base : external) if (base != null) addOwnedRoot(roots, new File(base, "EstradaPlay"));
        addOwnedRoot(roots, new File(new File(app.getFilesDir(), "Music"), "EstradaPlay"));
        return new ArrayList<>(roots.values());
    }

    private static void addOwnedRoot(Map<String, File> roots, File root) {
        if (root == null) return;
        roots.put(canonical(root), root);
    }

    private List<File> allOwnedAudioFiles(List<File> roots) {
        LinkedHashMap<String, File> out = new LinkedHashMap<>();
        if (roots != null) for (File root : roots) collectAudioFiles(root, out);
        return new ArrayList<>(out.values());
    }

    private static void collectAudioFiles(File dir, Map<String, File> out) {
        if (dir == null || !dir.isDirectory()) return;
        File[] children = dir.listFiles(); if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) collectAudioFiles(f, out);
            else if (validAudioFile(f)) out.put(canonical(f), f);
        }
    }

    long freeBytes() {
        File root = musicRoot();
        File parent = root.getParentFile();
        return (parent == null ? app.getFilesDir() : parent).getUsableSpace();
    }

    synchronized StorageStats storageStats() {
        reconcileOffline();
        List<Track> indexed = rawDownloadedTracks();
        Set<String> referenced = new HashSet<>(); long indexedBytes = 0;
        for (Track t : indexed) {
            if (t.localPath.isEmpty()) continue;
            File f = new File(t.localPath);
            if (!validAudioFile(f)) continue;
            String cp = canonical(f);
            if (referenced.add(cp)) indexedBytes += f.length();
        }
        MutableStats disk = new MutableStats();
        Set<String> scanned = new HashSet<>();
        for (File root : ownedMusicRoots()) scanFilesDedup(root, referenced, scanned, disk);
        return new StorageStats(indexed.size(), indexedBytes, disk.files, disk.bytes, disk.orphans, disk.orphanBytes, freeBytes());
    }

    private static void scanFilesDedup(File dir, Set<String> referenced, Set<String> scanned, MutableStats out) {
        if (dir == null || !dir.isDirectory()) return;
        File[] children = dir.listFiles(); if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) scanFilesDedup(f, referenced, scanned, out);
            else if (validAudioFile(f)) {
                String cp = canonical(f); if (!scanned.add(cp)) continue;
                out.files++; out.bytes += f.length();
                if (!referenced.contains(cp)) { out.orphans++; out.orphanBytes += f.length(); }
            }
        }
    }

    synchronized int removeOrphanFiles() {
        reconcileOffline();
        Set<String> referenced = new HashSet<>();
        for (Track t : rawDownloadedTracks()) if (!t.localPath.isEmpty()) referenced.add(canonical(new File(t.localPath)));
        int removed = 0;
        for (File root : ownedMusicRoots()) { removed += removeOrphansRecursive(root, referenced); cleanupEmptyDirs(root); }
        return removed;
    }

    synchronized DeleteResult removeAllOffline() {
        long bytes = 0; int files = 0;
        Set<String> visited = new HashSet<>();
        for (File root : ownedMusicRoots()) {
            String rp = canonical(root); if (!visited.add(rp)) continue;
            long[] extra = deleteTreeContents(root); files += (int) extra[0]; bytes += extra[1]; cleanupEmptyDirs(root);
        }
        persistDownloaded(new JSONObject());
        return new DeleteResult(files, bytes);
    }

    int removeFolder(String folder) {
        reconcileOffline(); int removed = 0; JSONObject all = downloadedObject(); ArrayList<String> keys = new ArrayList<>();
        java.util.Iterator<String> it = all.keys();
        while (it.hasNext()) {
            String key = it.next(); JSONObject o = all.optJSONObject(key); if (o == null) continue; Track t = Track.fromStored(o);
            if (!folder.equals(folderKey(t))) continue; File f = t.localPath.isEmpty() ? null : new File(t.localPath); if (validAudioFile(f) && f.delete()) removed++; keys.add(key);
        }
        for (String key : keys) all.remove(key); persistDownloaded(all); cleanupEmptyDirs(musicRoot()); return removed;
    }

    private List<Track> rawDownloadedTracks() {
        ArrayList<Track> out = new ArrayList<>(); JSONObject all = downloadedObject(); java.util.Iterator<String> it = all.keys();
        while (it.hasNext()) { JSONObject o = all.optJSONObject(it.next()); if (o != null) { Track t = Track.fromStored(o); if (!t.localPath.isEmpty() && validAudioFile(new File(t.localPath))) out.add(t); } }
        return out;
    }

    private void persistDownloaded(JSONObject all) {
        String raw = all.toString(); prefs.edit().putString(KEY_DOWNLOADED, raw).apply(); updateDownloadedHint(all.length() > 0); downloadedCache = null; downloadedCacheRaw = null;
    }
    private void updateDownloadedHint(boolean value) { flags.edit().putBoolean(KEY_DOWNLOADED_HINT, value).apply(); }

    static String folderKey(Track t) {
        if (t == null) return "Sem pasta"; String p = t.folderPath == null ? "" : t.folderPath.trim(); if (p.isEmpty()) p = t.folder == null ? "" : t.folder.trim(); return p.isEmpty() ? "Sem pasta" : p;
    }

    private JSONObject downloadedObject() { try { return new JSONObject(prefs.getString(KEY_DOWNLOADED, "{}")); } catch (Exception e) { return new JSONObject(); } }

    private static boolean validAudioFile(File f) { return f != null && f.isFile() && f.length() > 0 && isAudioName(f.getName()); }
    private static boolean isAudioName(String name) { String n = name == null ? "" : name.toLowerCase(Locale.ROOT); for (String ext : new String[]{".mp3",".m4a",".aac",".flac",".wav",".opus",".ogg"}) if (n.endsWith(ext)) return true; return false; }
    private static String stripExtension(String name) { int i = name == null ? -1 : name.lastIndexOf('.'); return i > 0 ? name.substring(0,i) : (name == null ? "" : name); }
    static String normalizeFileToken(String value) { String s = value == null ? "" : value.toLowerCase(Locale.ROOT); s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", ""); return s.replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " "); }
    private static String canonical(File f) { try { return f.getCanonicalPath(); } catch (Exception e) { return f.getAbsolutePath(); } }

    private static void scanFiles(File dir, Set<String> referenced, MutableStats out) {
        if (dir == null || !dir.isDirectory()) return; File[] children = dir.listFiles(); if (children == null) return;
        for (File f : children) { if (f.isDirectory()) scanFiles(f,referenced,out); else if (validAudioFile(f)) { out.files++; out.bytes += f.length(); if (!referenced.contains(canonical(f))) { out.orphans++; out.orphanBytes += f.length(); } } }
    }
    private static int removeOrphansRecursive(File dir, Set<String> referenced) {
        if (dir == null || !dir.isDirectory()) return 0; int count = 0; File[] children = dir.listFiles(); if (children == null) return 0;
        for (File f : children) { if (f.isDirectory()) count += removeOrphansRecursive(f,referenced); else if (validAudioFile(f) && !referenced.contains(canonical(f)) && f.delete()) count++; } return count;
    }
    private static long[] deleteTreeContents(File dir) {
        long files=0,bytes=0; if (dir == null || !dir.isDirectory()) return new long[]{0,0}; File[] children=dir.listFiles(); if(children==null)return new long[]{0,0};
        for(File f:children){if(f.isDirectory()){long[] r=deleteTreeContents(f);files+=r[0];bytes+=r[1];f.delete();}else if(validAudioFile(f)){long len=f.length();if(f.delete()){files++;bytes+=len;}}}return new long[]{files,bytes};
    }
    private static void cleanupEmptyDirs(File dir) { if (dir == null || !dir.isDirectory()) return; File[] children=dir.listFiles(); if(children==null)return; for(File f:children)if(f.isDirectory()){cleanupEmptyDirs(f);File[] rest=f.listFiles();if(rest!=null&&rest.length==0)f.delete();} }

    private static String extension(Track t) {
        String m = t.mime == null ? "" : t.mime.toLowerCase(Locale.ROOT);
        if (m.contains("mpeg")) return ".mp3"; if (m.contains("mp4") || m.contains("m4a")) return ".m4a"; if (m.contains("aac")) return ".aac"; if (m.contains("flac")) return ".flac"; if (m.contains("wav")) return ".wav"; if (m.contains("opus")) return ".opus"; if (m.contains("ogg")) return ".ogg";
        String s = t.remoteSource == null ? "" : t.remoteSource.toLowerCase(Locale.ROOT); for (String ext : new String[]{".mp3", ".m4a", ".aac", ".flac", ".wav", ".opus", ".ogg"}) if (s.contains(ext)) return ext; return ".mp3";
    }

    static String safeName(String value) { String s = value == null ? "musica" : value; s = s.replaceAll("[\\\\/:*?\"<>|]+", " ").replaceAll("\\s+", " ").trim(); if (s.isEmpty()) s = "musica"; return s.length() > 90 ? s.substring(0, 90) : s; }
    private static String safeSegment(String value) { String s = safeName(value); return ".".equals(s) || "..".equals(s) ? "Pasta" : s; }

    static String humanBytes(long bytes) { double b=Math.max(0,bytes); String[] u={"B","KB","MB","GB","TB"}; int i=0; while(b>=1024&&i<u.length-1){b/=1024;i++;} return String.format(Locale.getDefault(), i==0?"%.0f %s":"%.1f %s",b,u[i]); }

    static final class ReconcileResult { final int before, after, recovered, staleRemoved; ReconcileResult(int before,int after,int recovered,int staleRemoved){this.before=before;this.after=after;this.recovered=recovered;this.staleRemoved=staleRemoved;} }
    static final class StorageStats { final int indexedFiles; final long indexedBytes; final int diskFiles; final long diskBytes; final int orphanFiles; final long orphanBytes; final long freeBytes; StorageStats(int i,long ib,int d,long db,int o,long ob,long f){indexedFiles=i;indexedBytes=ib;diskFiles=d;diskBytes=db;orphanFiles=o;orphanBytes=ob;freeBytes=f;} }
    static final class DeleteResult { final int files; final long bytes; DeleteResult(int f,long b){files=f;bytes=b;} }
    private static final class MutableStats { int files,orphans; long bytes,orphanBytes; }
    static final class FolderStat { final String name; int total; int downloaded; long knownBytes; FolderStat(String name) { this.name = name; } boolean complete() { return total > 0 && downloaded >= total; } }
}
