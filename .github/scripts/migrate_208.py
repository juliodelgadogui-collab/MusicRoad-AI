from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label}: anchor not found")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    out, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 replacement, got {count}")
    return out


# Version ---------------------------------------------------------------------
gradle = Path('estradaplay-app-v2/app/build.gradle')
s = gradle.read_text(encoding='utf-8')
s = replace_once(s, 'versionCode 207', 'versionCode 208', 'versionCode')
s = replace_once(s, "versionName '2.0.7'", "versionName '2.0.8'", 'versionName')
gradle.write_text(s, encoding='utf-8')


# ApiClient: keep pages small, but retain a large legacy fallback ----------------
api = Path('estradaplay-app-v2/app/src/main/java/com/estradaplay/app/ApiClient.java')
s = api.read_text(encoding='utf-8')
s = replace_once(
    s,
    '''    // LIBRARY_FAST_V207: catalog requests must never trap the interface.\n    Response getFast(String path) throws Exception { return request("GET", path, null, 5000, 8000, 4_000_000); }\n    Response get(String path) throws Exception { return request("GET", path, null, 8000, 18000, 4_000_000); }\n    Response getLong(String path) throws Exception { return request("GET", path, null, 10000, 120000, 50_000_000); }\n    Response post(String path, JSONObject data) throws Exception { return request("POST", path, data == null ? new JSONObject() : data, 8000, 18000, 4_000_000); }\n''',
    '''    // LIBRARY_PAGED_V208: normal catalog pages stay small; legacy fallback remains bounded.\n    Response getFast(String path) throws Exception { return request("GET", path, null, 5000, 10000, 4_000_000); }\n    Response get(String path) throws Exception { return request("GET", path, null, 8000, 18000, 4_000_000); }\n    Response getLong(String path) throws Exception { return request("GET", path, null, 10000, 120000, 50_000_000); }\n    Response getCatalogLegacy(String path) throws Exception { return request("GET", path, null, 10000, 45000, 16_000_000); }\n    Response post(String path, JSONObject data) throws Exception { return request("POST", path, data == null ? new JSONObject() : data, 8000, 18000, 4_000_000); }\n''',
    'ApiClient library methods'
)
api.write_text(s, encoding='utf-8')


# MainActivity: paginated catalog + sync-only endpoint + legacy compatibility ----
main = Path('estradaplay-app-v2/app/src/main/java/com/estradaplay/app/MainActivity.java')
s = main.read_text(encoding='utf-8')
replacement = r'''// LIBRARY_PAGED_V208: fetch the catalog in bounded pages so library size cannot break the app.
private static final int LIBRARY_PAGE_SIZE = 500;
private static final int LIBRARY_MAX_PAGES = 100;

private ArrayList<Track> decodeCatalog(JSONObject j) {
    ArrayList<Track> tracks = new ArrayList<>();
    JSONArray arr = j == null ? null : j.optJSONArray("tracks");
    if (arr == null) return tracks;
    for (int i = 0; i < arr.length(); i++) {
        JSONObject o = arr.optJSONObject(i);
        if (o == null) continue;
        Track t = Track.fromServer(o, api);
        if (!t.remoteSource.isEmpty()) tracks.add(t);
    }
    return tracks;
}

private boolean restoreLibrarySession() {
    try {
        ApiClient.Response r = api.post("api/native_app.php?action=device_login", devicePayload());
        JSONObject j = r.json();
        if (r.ok() && j.optBoolean("ok") && j.optJSONObject("account") != null) {
            saveAccount(j.optJSONObject("account"));
            return true;
        }
    } catch (Exception ignored) {}
    return false;
}

private ApiClient.Response libraryPageRequest(int page) throws Exception {
    String path = "api/native_app.php?action=library_page&page=" + page + "&limit=" + LIBRARY_PAGE_SIZE;
    ApiClient.Response r = api.getFast(path);
    if (r.code == 401 && restoreLibrarySession()) r = api.getFast(path);
    return r;
}

private ArrayList<Track> fetchLegacyCatalog() throws Exception {
    ApiClient.Response r = api.getCatalogLegacy("api/native_app.php?action=library_fast");
    if (r.code == 401 && restoreLibrarySession()) r = api.getCatalogLegacy("api/native_app.php?action=library_fast");
    JSONObject j = r.json();
    if (!r.ok() || j.optJSONArray("tracks") == null) {
        throw new Exception("HTTP " + r.code + " · " + j.optString("error", "Biblioteca indisponível"));
    }
    return decodeCatalog(j);
}

private ArrayList<Track> fetchCatalogFast() throws Exception {
    ArrayList<Track> all = new ArrayList<>();
    int page = 1;
    while (page <= LIBRARY_MAX_PAGES) {
        ApiClient.Response r = libraryPageRequest(page);
        JSONObject j = r.json();
        if (!r.ok()) {
            // Server 2.0.7 compatibility until the matching server ZIP is installed.
            if (r.code == 400 || r.code == 404) return fetchLegacyCatalog();
            throw new Exception("HTTP " + r.code + " · " + j.optString("error", "Falha ao carregar biblioteca"));
        }
        JSONArray raw = j.optJSONArray("tracks");
        if (raw == null) throw new Exception("Resposta da biblioteca sem faixas");
        all.addAll(decodeCatalog(j));

        JSONObject paging = j.optJSONObject("paging");
        boolean hasMore = paging != null && paging.optBoolean("has_more", false);
        if (!hasMore) return all;
        int next = paging.optInt("next_page", page + 1);
        if (next <= page) next = page + 1;
        page = next;
    }
    throw new Exception("Biblioteca excedeu o limite de páginas de segurança");
}

private ArrayList<Track> forceCatalogSync() throws Exception {
    ApiClient.Response r = api.getLong("api/native_app.php?action=library_sync_only");
    if (r.code == 401 && restoreLibrarySession()) r = api.getLong("api/native_app.php?action=library_sync_only");
    JSONObject j = r.json();
    if (r.ok() && j.optBoolean("ok", false)) return fetchCatalogFast();

    // Server 2.0.7 compatibility: old sync endpoint returns the whole catalog.
    if (r.code == 400 || r.code == 404) {
        r = api.getLong("api/native_app.php?action=library_sync");
        if (r.code == 401 && restoreLibrarySession()) r = api.getLong("api/native_app.php?action=library_sync");
        j = r.json();
        if (r.ok() && j.optJSONArray("tracks") != null) return decodeCatalog(j);
        return fetchLegacyCatalog();
    }
    throw new Exception("HTTP " + r.code + " · " + j.optString("error", "Falha ao sincronizar biblioteca"));
}

private boolean librarySyncDue() {'''
s = regex_once(
    s,
    r'// LIBRARY_NONBLOCKING_V207:.*?private boolean librarySyncDue\(\) \{',
    replacement,
    'MainActivity paged library block'
)
main.write_text(s, encoding='utf-8')


# LibraryStore: multi-megabyte catalog moves out of SharedPreferences -------------
store = Path('estradaplay-app-v2/app/src/main/java/com/estradaplay/app/LibraryStore.java')
s = store.read_text(encoding='utf-8')
s = replace_once(s, 'import java.io.File;\n', 'import java.io.ByteArrayOutputStream;\nimport java.io.File;\nimport java.io.FileInputStream;\nimport java.io.FileOutputStream;\nimport java.nio.charset.StandardCharsets;\n', 'LibraryStore imports')
s = replace_once(
    s,
    '    private final Context app;\n    private final SharedPreferences prefs;\n',
    '    private static final int MAX_CATALOG_BYTES = 32 * 1024 * 1024;\n    private final Context app;\n    private final SharedPreferences prefs;\n    private final File catalogFile;\n',
    'LibraryStore fields'
)
s = replace_once(
    s,
    '''    LibraryStore(Context context) {\n        app = context.getApplicationContext();\n        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);\n    }\n''',
    '''    LibraryStore(Context context) {\n        app = context.getApplicationContext();\n        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);\n        File dir = new File(app.getFilesDir(), "estradaplay_library");\n        catalogFile = new File(dir, "catalog.json");\n    }\n''',
    'LibraryStore constructor'
)
store_methods = r'''    // CATALOG_FILE_V208: large catalogs are stored atomically as a file, not in SharedPreferences.
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
        prefs.edit().remove(KEY_CATALOG).apply();
    }

    synchronized List<Track> catalog() {
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

    void saveDownloaded'''
s = regex_once(
    s,
    r'    void saveCatalog\(List<Track> tracks\) \{.*?\n    void saveDownloaded',
    store_methods,
    'LibraryStore catalog storage'
)
store.write_text(s, encoding='utf-8')


# Server API: new paged endpoint and sync-only endpoint ---------------------------
native = Path('api/native_app.php')
s = native.read_text(encoding='utf-8')
server_block = r'''if ($action === 'library_page') {
    $user = native_require_json_user($data);
    $page = max(1, (int)($_GET['page'] ?? 1));
    $limit = max(100, min(750, (int)($_GET['limit'] ?? 500)));
    $total = (int)db()->query('SELECT COUNT(*) FROM music_library')->fetchColumn();
    $offset = ($page - 1) * $limit;
    $sql = 'SELECT id,title,artist,album,origin,origin_ref,source_url,mime_type,file_size,folder,duration FROM music_library ORDER BY id ASC LIMIT '.(int)$limit.' OFFSET '.(int)$offset;
    $rows = db()->query($sql)->fetchAll() ?: [];
    $hasMore = ($offset + count($rows)) < $total;
    json_response([
        'ok'=>true,
        'tracks'=>native_library_rows($rows),
        'paging'=>[
            'page'=>$page,
            'limit'=>$limit,
            'returned'=>count($rows),
            'total'=>$total,
            'has_more'=>$hasMore,
            'next_page'=>$hasMore ? $page + 1 : null,
        ],
        'meta'=>native_library_meta(),
        'account'=>native_account_payload($user),
        'csrf'=>csrf_token(),
    ]);
}

// Legacy full-catalog actions remain for APK 2.0.7 compatibility.
if ($action === 'library_fast' || $action === 'library') {
    $user = native_require_json_user($data);
    $rows = db()->query('SELECT * FROM music_library ORDER BY title ASC LIMIT 5000')->fetchAll() ?: [];
    json_response(['ok'=>true,'tracks'=>native_library_rows($rows),'meta'=>native_library_meta(),'account'=>native_account_payload($user),'csrf'=>csrf_token()]);
}

if ($action === 'library_sync_only') {
    $user = native_require_json_user($data);
    try { $sync = native_library_sync_active_drive_folders(); }
    catch (Throwable $e) { json_response(['ok'=>false,'error'=>'Falha ao sincronizar o Google Drive.','detail'=>$e->getMessage(),'meta'=>native_library_meta()],502); }
    json_response(['ok'=>true,'sync'=>$sync,'meta'=>native_library_meta(),'account'=>native_account_payload($user),'csrf'=>csrf_token()]);
}

if ($action === 'library_sync') {
    $user = native_require_json_user($data);
    try { $sync = native_library_sync_active_drive_folders(); }
    catch (Throwable $e) { json_response(['ok'=>false,'error'=>'Falha ao sincronizar o Google Drive.','detail'=>$e->getMessage(),'meta'=>native_library_meta()],502); }
    $rows = db()->query('SELECT * FROM music_library ORDER BY title ASC LIMIT 5000')->fetchAll() ?: [];
    json_response(['ok'=>true,'tracks'=>native_library_rows($rows),'sync'=>$sync,'meta'=>native_library_meta(),'account'=>native_account_payload($user),'csrf'=>csrf_token()]);
}

if ($action === 'library_status') {'''
s = regex_once(
    s,
    r"if \(\$action === 'library_fast' \|\| \$action === 'library'\) \{.*?if \(\$action === 'library_status'\) \{",
    server_block,
    'native_app paged endpoints'
)
native.write_text(s, encoding='utf-8')


# Drive sync: isolate folder failures, fix per-root status and reject Google noise -
sync = Path('api/native_library_sync.php')
s = sync.read_text(encoding='utf-8')
s = replace_once(s, '// DRIVE_SYNC_V207: this runs outside the UI request path.', '// DRIVE_SYNC_V208: resilient root/subfolder sync; one bad public subfolder cannot abort the library.', 'sync marker')
s = replace_once(s, "'User-Agent: Mozilla/5.0 EstradaPlay/2.0.7'", "'User-Agent: Mozilla/5.0 EstradaPlay/2.0.8'", 'sync user agent')
s = replace_once(s, "        if ($stats['tracks'] >= 5000) return;", "        if ($stats['tracks'] >= 20000) return;", 'sync track cap')
s = replace_once(
    s,
    '''        try {\n            native_library_scan_folder((string)$root['folder_id'], [$rootName], $apiKey, $visited, $stats, 0);\n            $status = 'OK: '.$stats['tracks'].' músicas verificadas';\n            $u = db()->prepare('UPDATE drive_folders SET last_import_at = ?, last_status = ? WHERE id = ?');\n            $u->execute([date('Y-m-d H:i:s'), $status, (int)$root['id']]);\n''',
    '''        try {\n            $beforeTracks = (int)$stats['tracks'];\n            $beforeErrors = count($stats['errors']);\n            native_library_scan_folder((string)$root['folder_id'], [$rootName], $apiKey, $visited, $stats, 0);\n            $rootTracks = max(0, (int)$stats['tracks'] - $beforeTracks);\n            $rootWarnings = max(0, count($stats['errors']) - $beforeErrors);\n            $status = 'OK: '.$rootTracks.' músicas verificadas'.($rootWarnings > 0 ? ' | '.$rootWarnings.' aviso(s)' : '');\n            $u = db()->prepare('UPDATE drive_folders SET last_import_at = ?, last_status = ? WHERE id = ?');\n            $u->execute([date('Y-m-d H:i:s'), $status, (int)$root['id']]);\n''',
    'sync per-root status'
)
s = replace_once(
    s,
    '''        if (($entry['kind'] ?? '') === 'folder') {\n            native_library_scan_folder((string)$entry['id'], array_merge($path, [(string)($entry['name'] ?: 'Subpasta')]), $apiKey, $visited, $stats, $depth + 1);\n            continue;\n        }\n''',
    '''        if (($entry['kind'] ?? '') === 'folder') {\n            $childPath = array_merge($path, [(string)($entry['name'] ?: 'Subpasta')]);\n            try {\n                native_library_scan_folder((string)$entry['id'], $childPath, $apiKey, $visited, $stats, $depth + 1);\n            } catch (Throwable $e) {\n                $stats['errors'][] = implode(' / ', $childPath).': '.$e->getMessage();\n            }\n            continue;\n        }\n''',
    'sync child isolation'
)
s = replace_once(
    s,
    '''        if ($id === '' || $kind === '') continue;\n        $key = $kind.':'.$id;\n''',
    '''        if ($id === '' || $kind === '') continue;\n        $name = trim((string)($e['name'] ?? ''));\n        if ($kind === 'folder' && ($name === '' || preg_match('~(?:^https?://|(?:^|\\.)google(?:usercontent)?\\.com$|clients[0-9]*\\.google\\.com|appsgrowthpromo|googleapis\\.com)~i', $name))) continue;\n        $key = $kind.':'.$id;\n''',
    'sync public noise filter'
)
sync.write_text(s, encoding='utf-8')

print('EstradaPlay 2.0.8 source migration prepared successfully')
