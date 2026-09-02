#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path('estrada-play-comunista-app')
J = ROOT / 'app/src/main/java/com/estradaplay/comunista'

def write(path: Path, text: str):
    path.write_text(text, encoding='utf-8')

def sub_once(text: str, pattern: str, repl: str, label: str) -> str:
    out, n = re.subn(pattern, repl, text, count=1, flags=re.S)
    if n != 1:
        raise SystemExit(f'{label}: expected 1 replacement, got {n}')
    return out

# Version bump.
gradle = ROOT / 'app/build.gradle'
s = gradle.read_text(encoding='utf-8')
s = s.replace('versionCode 202', 'versionCode 203').replace("versionName '2.0.2'", "versionName '2.0.3'")
write(gradle, s)

# LibraryStore: discover every real app-owned audio file, not only the expected catalog directory.
p = J / 'LibraryStore.java'
s = p.read_text(encoding='utf-8')
if 'OFFLINE_DISCOVERY_V203' not in s:
    reconcile = r'''    // OFFLINE_DISCOVERY_V203: rebuild from every real app-owned audio file, even if IDs/folders changed.
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

    List<Track> downloadedTracks() {'''
    s = sub_once(s,
        r'    synchronized ReconcileResult reconcileOffline\(\) \{.*?\n    List<Track> downloadedTracks\(\) \{',
        reconcile,
        'reconcile block')

    roots = r'''    // New downloads live in internal no-backup storage: uninstall removes them with the package.
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

'''
    s = sub_once(s,
        r'    File musicRoot\(\) \{.*?\n    long freeBytes\(\) \{.*?\n    \}\n\n',
        roots,
        'musicRoot block')

    storage = r'''    synchronized StorageStats storageStats() {
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

    int removeFolder(String folder) {'''
    s = sub_once(s,
        r'    synchronized StorageStats storageStats\(\) \{.*?\n    int removeFolder\(String folder\) \{',
        storage,
        'storage block')
    write(p, s)

# MusicStorageActivity: detect legacy/public copies by brand OR by matching the live catalog.
p = J / 'MusicStorageActivity.java'
s = p.read_text(encoding='utf-8')
if 'PUBLIC_DISCOVERY_V203' not in s:
    s = s.replace('Button p=button("PERMITIR E PROCURAR MÚSICAS ANTIGAS",false);', 'Button p=button("PROCURAR MÚSICAS NO CELULAR",false);')
    s = s.replace('old.addView(text("Para localizar músicas antigas em pastas compartilhadas, permita acesso aos arquivos de áudio. O EPC só procura caminhos identificados como EstradaPlay.",12,MUTED,false));',
                  'old.addView(text("Permita acesso aos arquivos de áudio para procurar cópias antigas. A busca compara nomes e IDs com o catálogo do EPC, mesmo que a pasta tenha outro nome.",12,MUTED,false));')
    scan = r'''    // PUBLIC_DISCOVERY_V203: public/legacy music may live in a folder that is not named EstradaPlay.
    private List<LegacyItem> scanLegacy(){
        ArrayList<LegacyItem> out=new ArrayList<>();
        List<Track> catalog=library.catalog();
        ContentResolver cr=getContentResolver();Uri base=MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        ArrayList<String> cols=new ArrayList<>();cols.add(MediaStore.Audio.Media._ID);cols.add(MediaStore.Audio.Media.DISPLAY_NAME);cols.add(MediaStore.Audio.Media.SIZE);
        if(Build.VERSION.SDK_INT>=29)cols.add(MediaStore.Audio.Media.RELATIVE_PATH);else cols.add(MediaStore.Audio.Media.DATA);
        try(Cursor c=cr.query(base,cols.toArray(new String[0]),null,null,MediaStore.Audio.Media.DATE_ADDED+" DESC")){
            if(c==null)return out;
            int id=c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID),name=c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME),size=c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE),path=c.getColumnIndex(Build.VERSION.SDK_INT>=29?MediaStore.Audio.Media.RELATIVE_PATH:MediaStore.Audio.Media.DATA);
            while(c.moveToNext()){
                String n=c.getString(name),p=path>=0?c.getString(path):"";
                LegacyItem item=new LegacyItem(ContentUris.withAppendedId(base,c.getLong(id)),n==null?"Música":n,p==null?"":p,Math.max(0,c.getLong(size)));
                String probe=((p==null?"":p)+"/"+(n==null?"":n)).toLowerCase(Locale.ROOT).replace(" ","");
                boolean branded=probe.contains("estradaplay")||probe.contains("musicroad")||probe.contains("estradaplaycomunista");
                if(!branded && matchLegacy(item,catalog)==null)continue;
                out.add(item);if(out.size()>=5000)break;
            }
        }catch(Throwable ignored){}
        return out;
    }

    private void requestDeleteLegacy(){'''
    s = sub_once(s,
        r'    private List<LegacyItem> scanLegacy\(\)\{.*?\n    private void requestDeleteLegacy\(\)\{',
        scan,
        'scanLegacy block')
    write(p, s)

# Main download manager: make recovery/cleanup reachable from the screen in the user's screenshot.
p = J / 'MainActivity.java'
s = p.read_text(encoding='utf-8')
if 'LIBRARY_REPAIR_ENTRY_V203' not in s:
    needle = '''        refresh.setEnabled(online());
        refresh.setOnClickListener(v -> refreshLibraryAndOpenChooser());

        FolderDownloadAdapter adapter = new FolderDownloadAdapter(stats, storage, initial);'''
    repl = '''        refresh.setEnabled(online());
        refresh.setOnClickListener(v -> refreshLibraryAndOpenChooser());

        // LIBRARY_REPAIR_ENTRY_V203: recovery must be visible from Gerenciar downloads.
        Button repair = compactButton("PROCURAR / LIMPAR MÚSICAS DO CELULAR");
        page.addView(repair, lp(-1, 46)); margins(repair, 0, 0, 0, 10);
        repair.setOnClickListener(v -> startActivity(new Intent(this, MusicStorageActivity.class)));

        FolderDownloadAdapter adapter = new FolderDownloadAdapter(stats, storage, initial);'''
    if needle not in s:
        raise SystemExit('MainActivity repair insertion anchor missing')
    s = s.replace(needle, repl, 1)
    write(p, s)

# Make zero-library recovery action explicit in player too.
p = J / 'MusicPlayerActivity.java'
s = p.read_text(encoding='utf-8')
s = s.replace('REINDEXAR / LIMPAR ARMAZENAMENTO', 'PROCURAR MÚSICAS NO CELULAR')
write(p, s)

print('Estrada Play Universal 2.0.3 offline discovery repair applied.')
