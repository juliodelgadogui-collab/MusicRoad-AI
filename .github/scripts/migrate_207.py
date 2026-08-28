from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label}: anchor not found")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    out, count = re.subn(pattern, lambda m: replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: replacements={count}")
    return out


# ---------- Android version + client ----------
gradle = Path("estradaplay-app-v2/app/build.gradle")
s = gradle.read_text()
s = re.sub(r"versionCode\s+206", "versionCode 207", s, count=1)
s = re.sub(r"versionName\s+'2\.0\.6'", "versionName '2.0.7'", s, count=1)
if "versionCode 207" not in s or "versionName '2.0.7'" not in s:
    raise SystemExit("version bump failed")
gradle.write_text(s)

api = Path("estradaplay-app-v2/app/src/main/java/com/estradaplay/app/ApiClient.java")
s = api.read_text()
old = '''    Response get(String path) throws Exception { return request("GET", path, null, 10000, 18000, 2_000_000); }
    Response getLong(String path) throws Exception { return request("GET", path, null, 15000, 120000, 50_000_000); }
    Response post(String path, JSONObject data) throws Exception { return request("POST", path, data == null ? new JSONObject() : data, 10000, 18000, 2_000_000); }
'''
new = '''    // LIBRARY_FAST_V207: catalog requests must never trap the interface.
    Response getFast(String path) throws Exception { return request("GET", path, null, 5000, 8000, 4_000_000); }
    Response get(String path) throws Exception { return request("GET", path, null, 8000, 18000, 4_000_000); }
    Response getLong(String path) throws Exception { return request("GET", path, null, 10000, 120000, 50_000_000); }
    Response post(String path, JSONObject data) throws Exception { return request("POST", path, data == null ? new JSONObject() : data, 8000, 18000, 4_000_000); }
'''
s = replace_once(s, old, new, "ApiClient methods")
s = replace_once(s, 'c.setInstanceFollowRedirects(false);', 'c.setInstanceFollowRedirects("GET".equals(method));', "ApiClient redirects")
api.write_text(s)

main = Path("estradaplay-app-v2/app/src/main/java/com/estradaplay/app/MainActivity.java")
s = main.read_text()
replacement = r'''
// LIBRARY_NONBLOCKING_V207: fast catalog + separate sync + device-session recovery.
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

private ArrayList<Track> fetchCatalogFast() throws Exception {
    ApiClient.Response r = api.getFast("api/native_app.php?action=library_fast");
    if (r.code == 401 && restoreLibrarySession()) r = api.getFast("api/native_app.php?action=library_fast");
    JSONObject j = r.json();
    if (!r.ok() || j.optJSONArray("tracks") == null) return new ArrayList<>();
    return decodeCatalog(j);
}

private ArrayList<Track> forceCatalogSync() throws Exception {
    ApiClient.Response r = api.getLong("api/native_app.php?action=library_sync");
    if (r.code == 401 && restoreLibrarySession()) r = api.getLong("api/native_app.php?action=library_sync");
    JSONObject j = r.json();
    if (r.ok() && j.optJSONArray("tracks") != null) return decodeCatalog(j);

    // Server 2.0.6 compatibility while the server patch has not yet been applied.
    r = api.getLong("api/native_app.php?action=library");
    if (r.code == 401 && restoreLibrarySession()) r = api.getLong("api/native_app.php?action=library");
    j = r.json();
    if (r.ok() && j.optJSONArray("tracks") != null) return decodeCatalog(j);
    throw new Exception(j.optString("error", "Biblioteca indisponível"));
}

private boolean librarySyncDue() {
    long last = prefs.getLong("library_sync_v207_ms", 0L);
    return System.currentTimeMillis() - last > 10L * 60L * 1000L;
}

private void rememberLibrarySync() {
    prefs.edit().putLong("library_sync_v207_ms", System.currentTimeMillis()).apply();
}

private void saveFreshCatalog(List<Track> tracks, boolean notifyNewFolders) {
    if (tracks == null || tracks.isEmpty()) return;
    int beforeFolders = library.folderNames(library.catalog()).size();
    library.saveCatalog(tracks);
    int afterFolders = library.folderNames(tracks).size();
    if (notifyNewFolders && afterFolders > beforeFolders && beforeFolders > 0) {
        int added = afterFolders - beforeFolders;
        ui.post(() -> toast(added + (added == 1 ? " nova pasta encontrada." : " novas pastas encontradas.")));
    }
}

private void syncCatalogInBackground() {
    if (!online()) return;
    io.execute(() -> {
        try {
            ArrayList<Track> fast = fetchCatalogFast();
            if (!fast.isEmpty()) saveFreshCatalog(fast, true);
            if (fast.isEmpty() || librarySyncDue()) {
                ArrayList<Track> fresh = forceCatalogSync();
                if (!fresh.isEmpty()) {
                    saveFreshCatalog(fresh, true);
                    rememberLibrarySync();
                }
            }
        } catch (Exception ignored) {
            // Library is optional for boot. Map, GPS and downloaded music stay available.
        }
    });
}

private void loadCatalogAndOpenChooser(boolean initial) {
    List<Track> local = library.catalog();
    if (!local.isEmpty()) {
        library.setSetupDone(true);
        showFolderChooser(initial);
        syncCatalogInBackground();
        return;
    }

    showLoading("Carregando sua biblioteca…");
    io.execute(() -> {
        try {
            ArrayList<Track> tracks = fetchCatalogFast();
            if (tracks.isEmpty()) {
                ui.post(() -> showLoading("Sincronizando suas músicas…"));
                tracks = forceCatalogSync();
                if (!tracks.isEmpty()) rememberLibrarySync();
            }
            ArrayList<Track> result = tracks;
            ui.post(() -> {
                library.setSetupDone(true);
                if (!result.isEmpty()) {
                    library.saveCatalog(result);
                    showFolderChooser(initial);
                } else {
                    showHome();
                    alert("Biblioteca", "O servidor respondeu, mas ainda não há músicas disponíveis. Verifique as pastas do Google Drive no painel e toque em Gerenciar Biblioteca novamente.");
                }
            });
        } catch (Exception e) {
            ui.post(() -> {
                library.setSetupDone(true);
                showHome();
                alert("Biblioteca", "Não consegui atualizar as músicas agora. O restante do EstradaPlay continua funcionando. Tente novamente em Gerenciar Biblioteca.");
            });
        }
    });
}

private void refreshLibraryAndOpenChooser() {
    if (!online()) {
        toast("Sem internet para atualizar. Mostrando a biblioteca salva.");
        showFolderChooser(false);
        return;
    }
    showLoading("Atualizando suas pastas…");
    io.execute(() -> {
        try {
            ArrayList<Track> tracks = forceCatalogSync();
            if (!tracks.isEmpty()) {
                library.saveCatalog(tracks);
                rememberLibrarySync();
            }
            ui.post(() -> {
                showFolderChooser(false);
                toast(tracks.isEmpty() ? "Nenhuma música nova encontrada." : tracks.size() + " músicas disponíveis.");
            });
        } catch (Exception e) {
            ui.post(() -> {
                showFolderChooser(false);
                toast("Não consegui atualizar agora. Mantive a biblioteca salva.");
            });
        }
    });
}

    private void showFolderChooser'''
s = regex_once(s, r'\nprivate void syncCatalogInBackground\(\) \{.*?\n    private void showFolderChooser', '\n' + replacement, "MainActivity library block")
old = '''        Button all = compactButton("SELECIONAR TODAS NÃO BAIXADAS"); page.addView(all, lp(-1, 46)); margins(all, 0, 10, 0, 10);

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
'''
new = '''        Button all = compactButton("SELECIONAR TODAS NÃO BAIXADAS"); page.addView(all, lp(-1, 46)); margins(all, 0, 10, 0, 6);
        Button refresh = compactButton("ATUALIZAR DO SERVIDOR"); page.addView(refresh, lp(-1, 46)); margins(refresh, 0, 0, 0, 10);
        refresh.setEnabled(online());
        refresh.setOnClickListener(v -> refreshLibraryAndOpenChooser());

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
'''
s = replace_once(s, old, new, "MainActivity refresh button")
main.write_text(s)

dl = Path("estradaplay-app-v2/app/src/main/java/com/estradaplay/app/DownloadService.java")
s = dl.read_text()
old = '            c.setRequestProperty("X-MusicRoad-Native", "1");\n'
new = '''            c.setRequestProperty("X-MusicRoad-Native", "1");
            c.setRequestProperty("X-EstradaPlay-Device", DeviceIdentity.token(this));
            c.setRequestProperty("X-EstradaPlay-Device-Label", DeviceIdentity.label());
'''
s = replace_once(s, old, new, "DownloadService native headers")
dl.write_text(s)


# ---------- Cross-database bootstrap ----------
boot = Path("api/bootstrap.php")
s = boot.read_text()
block = r'''// DB_COMPAT_V207: minimum runtime schema compatible with SQLite and MySQL/MariaDB.
function schema_columns(string $table): array
{
    try {
        $driver = (string)db()->getAttribute(PDO::ATTR_DRIVER_NAME);
        $safe = preg_replace('/[^a-zA-Z0-9_]/', '', $table);
        if ($driver === 'sqlite') {
            $rows = db()->query("PRAGMA table_info(" . $safe . ")")->fetchAll();
            return array_map(fn($r) => (string)$r['name'], $rows);
        }
        if ($driver === 'mysql') {
            $stmt = db()->prepare('SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION');
            $stmt->execute([$safe]);
            return array_map(fn($r) => (string)$r['COLUMN_NAME'], $stmt->fetchAll() ?: []);
        }
    } catch (Throwable $e) {}
    return [];
}

function ensure_schema(): void
{
    static $done = false;
    if ($done) return;
    $done = true;
    $driver = (string)db()->getAttribute(PDO::ATTR_DRIVER_NAME);

    if ($driver === 'sqlite') {
        $sql = @file_get_contents(__DIR__ . '/../database/schema.sql');
        if ($sql !== false) {
            try { db()->exec($sql); } catch (Throwable $e) { error_log('Schema sqlite: ' . $e->getMessage()); }
        }
    } elseif ($driver === 'mysql') {
        $ddl = [
            "CREATE TABLE IF NOT EXISTS users (id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,name VARCHAR(190) NOT NULL,email VARCHAR(190) NOT NULL UNIQUE,username VARCHAR(190) NULL UNIQUE,password_hash VARCHAR(255) NOT NULL,role VARCHAR(32) NOT NULL DEFAULT 'client',status VARCHAR(32) NOT NULL DEFAULT 'active',last_login_at DATETIME NULL,created_at DATETIME NOT NULL,updated_at DATETIME NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
            "CREATE TABLE IF NOT EXISTS music_library (id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,user_id INT NULL,title VARCHAR(255) NOT NULL,artist TEXT NULL,album VARCHAR(255) NULL,genre VARCHAR(120) NULL,year INT NULL,duration INT NULL,cover_url TEXT NULL,origin VARCHAR(64) NOT NULL,origin_ref VARCHAR(255) NULL,mime_type VARCHAR(120) NULL,file_size BIGINT NULL,created_at DATETIME NOT NULL,UNIQUE KEY idx_music_origin_ref (origin,origin_ref)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
            "CREATE TABLE IF NOT EXISTS drive_folders (id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,name VARCHAR(255) NULL,folder_id VARCHAR(255) NOT NULL UNIQUE,folder_link TEXT NOT NULL,active TINYINT(1) NOT NULL DEFAULT 1,last_import_at DATETIME NULL,last_status TEXT NULL,created_at DATETIME NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
            "CREATE TABLE IF NOT EXISTS client_device_state (id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,user_id INT NOT NULL,device_id VARCHAR(190) NOT NULL,device_name VARCHAR(190) NULL,music_permission VARCHAR(32) NOT NULL DEFAULT 'unknown',location_permission VARCHAR(32) NOT NULL DEFAULT 'unknown',music_folder_count INT NOT NULL DEFAULT 0,music_track_count INT NOT NULL DEFAULT 0,last_latitude DOUBLE NULL,last_longitude DOUBLE NULL,last_seen_at DATETIME NOT NULL,UNIQUE KEY idx_device_user (user_id,device_id),KEY idx_device_id (device_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
            "CREATE TABLE IF NOT EXISTS app_settings (`key` VARCHAR(190) NOT NULL PRIMARY KEY,value TEXT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
            "CREATE TABLE IF NOT EXISTS audit_logs (id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,action VARCHAR(190) NOT NULL,payload LONGTEXT NULL,ip VARCHAR(64) NULL,created_at DATETIME NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
            "CREATE TABLE IF NOT EXISTS user_music_state (user_id INT NOT NULL,music_id INT NOT NULL,is_favorite TINYINT(1) NOT NULL DEFAULT 0,play_count INT NOT NULL DEFAULT 0,skip_count INT NOT NULL DEFAULT 0,last_played_at DATETIME NULL,PRIMARY KEY (user_id,music_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
        ];
        foreach ($ddl as $sql) {
            try { db()->exec($sql); } catch (Throwable $e) { error_log('Schema mysql: ' . $e->getMessage()); }
        }
    }

    $cols = schema_columns('users');
    if ($cols) {
        $defs = $driver === 'mysql'
            ? ['status'=>"VARCHAR(32) NOT NULL DEFAULT 'active'",'last_login_at'=>'DATETIME NULL','updated_at'=>'DATETIME NULL']
            : ['status'=>"TEXT NOT NULL DEFAULT 'active'",'last_login_at'=>'TEXT','updated_at'=>'TEXT'];
        foreach ($defs as $name => $definition) {
            if (!in_array($name, $cols, true)) {
                try { db()->exec("ALTER TABLE users ADD COLUMN $name $definition"); } catch (Throwable $e) {}
            }
        }
    }
    try {
        db()->exec("UPDATE users SET role = 'client' WHERE role IN ('user','cliente')");
        db()->exec("UPDATE users SET status = 'active' WHERE status IS NULL OR status = ''");
    } catch (Throwable $e) {}
}

function ensure_default_users(): void
{
    ensure_schema();
    $now = date('Y-m-d H:i:s');
    $adminCount = (int)db()->query("SELECT COUNT(*) FROM users WHERE role = 'admin'")->fetchColumn();
    if ($adminCount === 0) {
        $stmt = db()->prepare("INSERT INTO users (name,email,username,password_hash,role,status,created_at,updated_at) VALUES (?,?,?,?,?,'active',?,?)");
        $stmt->execute(['Administrador', 'admin@musicroad.local', 'adm', password_hash('1', PASSWORD_DEFAULT), 'admin', $now, $now]);
    }
    $clientCount = (int)db()->query("SELECT COUNT(*) FROM users WHERE role = 'client'")->fetchColumn();
    if ($clientCount === 0) {
        $stmt = db()->prepare("INSERT INTO users (name,email,username,password_hash,role,status,created_at,updated_at) VALUES (?,?,?,?,?,'active',?,?)");
        $stmt->execute(['Cliente Teste', 'cliente@musicroad.local', 'cliente', password_hash('1', PASSWORD_DEFAULT), 'client', $now, $now]);
    }
}
'''
s = regex_once(s, r'function schema_columns\(string \$table\): array.*?function ensure_default_users\(\): void\s*\{.*?\n\}\n\n// Compatibilidade', block + '\n// Compatibilidade', "bootstrap schema")
audit = r'''function audit_log(string $action, array $payload = []): void
{
    ensure_schema();
    try {
        $stmt = db()->prepare("INSERT INTO audit_logs (action,payload,ip,created_at) VALUES (?,?,?,?)");
        $stmt->execute([$action, json_encode($payload, JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES), $_SERVER['REMOTE_ADDR'] ?? 'cli', date('Y-m-d H:i:s')]);
    } catch (Throwable $e) {}
}'''
s = regex_once(s, r'function audit_log\(string \$action, array \$payload = \[\]\): void\s*\{.*?\n\}', audit, "bootstrap audit")
old = '''function set_app_setting(string $key, ?string $value): void
{
    ensure_schema();
    $stmt = db()->prepare("INSERT INTO app_settings (key,value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value");
    try {
        $stmt->execute([$key, $value]);
    } catch (Throwable $e) {
        $exists = db()->prepare('SELECT key FROM app_settings WHERE key = ?');
        $exists->execute([$key]);
        if ($exists->fetchColumn()) {
            $u = db()->prepare('UPDATE app_settings SET value = ? WHERE key = ?');
            $u->execute([$value, $key]);
        } else {
            $i = db()->prepare('INSERT INTO app_settings (key,value) VALUES (?,?)');
            $i->execute([$key, $value]);
        }
    }
}
'''
new = '''function set_app_setting(string $key, ?string $value): void
{
    ensure_schema();
    try {
        $driver = (string)db()->getAttribute(PDO::ATTR_DRIVER_NAME);
        $sql = $driver === 'mysql'
            ? "INSERT INTO app_settings (`key`,value) VALUES (?,?) ON DUPLICATE KEY UPDATE value=VALUES(value)"
            : "INSERT INTO app_settings (key,value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value";
        $stmt = db()->prepare($sql);
        $stmt->execute([$key, $value]);
    } catch (Throwable $e) {
        $exists = db()->prepare('SELECT `key` FROM app_settings WHERE `key` = ?');
        $exists->execute([$key]);
        if ($exists->fetchColumn()) {
            $u = db()->prepare('UPDATE app_settings SET value = ? WHERE `key` = ?');
            $u->execute([$value, $key]);
        } else {
            $i = db()->prepare('INSERT INTO app_settings (`key`,value) VALUES (?,?)');
            $i->execute([$key, $value]);
        }
    }
}
'''
s = replace_once(s, old, new, "bootstrap setting")
boot.write_text(s)


# ---------- Fast library API ----------
library_php = r'''<?php
declare(strict_types=1);
require_once __DIR__.'/bootstrap.php';
require_once __DIR__.'/native_auth.php';
require_once __DIR__.'/native_library_sync.php';

$native = trim((string)($_SERVER['HTTP_X_MUSICROAD_NATIVE'] ?? '')) === '1';
$user = $native ? native_restore_user_from_request() : current_user();
if (!$user) {
    if ($native) json_response(['ok'=>false,'error'=>'Sessão expirada. Entre novamente neste aparelho.'],401);
    $user = require_login();
}

$action = strtolower(trim((string)($_GET['action'] ?? 'list')));
if ($action === 'list') {
    // LIBRARY_FAST_V207: listing never launches a Drive scan.
    $rows = db()->query('SELECT * FROM music_library ORDER BY title ASC LIMIT 5000')->fetchAll() ?: [];
    json_response(['ok'=>true,'tracks'=>native_library_rows_v207($rows),'meta'=>native_library_meta_v207(),'csrf'=>csrf_token()]);
}
if ($action === 'sync') {
    if (!$native) json_response(['ok'=>false,'error'=>'A sincronização pelo aplicativo exige cliente nativo.'],403);
    try {
        $sync = native_library_sync_active_drive_folders();
        $rows = db()->query('SELECT * FROM music_library ORDER BY title ASC LIMIT 5000')->fetchAll() ?: [];
        json_response(['ok'=>true,'tracks'=>native_library_rows_v207($rows),'sync'=>$sync,'meta'=>native_library_meta_v207(),'csrf'=>csrf_token()]);
    } catch (Throwable $e) {
        json_response(['ok'=>false,'error'=>'Falha ao sincronizar o Google Drive.','detail'=>$e->getMessage(),'meta'=>native_library_meta_v207()],502);
    }
}
json_response(['ok'=>false,'error'=>'Ação inválida.'],400);

function native_library_rows_v207(array $rows): array
{
    foreach ($rows as &$r) {
        $r['id'] = (string)($r['id'] ?? '');
        $origin = strtolower((string)($r['origin'] ?? ''));
        $ref = trim((string)($r['origin_ref'] ?? ''));
        $source = trim((string)($r['source_url'] ?? ''));
        if ($source === '') {
            if ($ref !== '' && (str_contains($origin,'drive') || preg_match('/^[A-Za-z0-9_-]{20,}$/',$ref))) $source = 'api/drive_stream.php?id='.rawurlencode($ref);
            elseif ($ref !== '' && preg_match('~^https?://~i',$ref)) $source = $ref;
        }
        $r['source'] = $source;
        $r['content_uri'] = $source;
        $r['duration_ms'] = ((int)($r['duration'] ?? 0))*1000;
        $r['file_size'] = (int)($r['file_size'] ?? 0);
        $folder = trim((string)($r['folder'] ?? ''));
        if ($folder === '' && str_contains($origin,'drive')) $folder = trim((string)($r['artist'] ?? ''));
        $r['folder'] = $folder;
        $r['folder_path'] = $folder;
    }
    unset($r);
    return $rows;
}

function native_library_meta_v207(): array
{
    try { $tracks = (int)db()->query('SELECT COUNT(*) FROM music_library')->fetchColumn(); } catch (Throwable $e) { $tracks = 0; }
    try { $roots = (int)db()->query('SELECT COUNT(*) FROM drive_folders WHERE active = 1')->fetchColumn(); } catch (Throwable $e) { $roots = 0; }
    return ['tracks'=>$tracks,'active_roots'=>$roots,'last_sync'=>app_setting('native_library_last_sync',''),'db_driver'=>(string)db()->getAttribute(PDO::ATTR_DRIVER_NAME)];
}
'''
Path("api/library.php").write_text(library_php)

native_app = r'''<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';
require_once __DIR__ . '/native_library_sync.php';

header('Content-Type: application/json; charset=utf-8');
ensure_default_users();
$action = strtolower(trim((string)($_GET['action'] ?? '')));
$data = input_json();

if ($action === 'login') {
    $login = trim((string)($data['login'] ?? ''));
    $password = (string)($data['password'] ?? '');
    if ($login === '' || $password === '') json_response(['ok'=>false,'error'=>'Informe usuário e senha.'], 422);
    $s = db()->prepare('SELECT * FROM users WHERE (username = ? OR email = ?) LIMIT 1');
    $s->execute([$login,$login]);
    $user = $s->fetch();
    if (!$user || ($user['status'] ?? 'active') !== 'active' || !password_verify($password,(string)$user['password_hash'])) json_response(['ok'=>false,'error'=>'Usuário ou senha inválidos.'], 401);
    native_start_user_session($user);
    $token = native_device_token_from_request($data);
    native_bind_device((int)$user['id'],$token,native_device_label_from_request($data));
    try { $u = db()->prepare('UPDATE users SET last_login_at = ? WHERE id = ?'); $u->execute([date('Y-m-d H:i:s'),(int)$user['id']]); } catch (Throwable $ignored) {}
    audit_log('native.login',['user_id'=>(int)$user['id']]);
    json_response(['ok'=>true,'account'=>native_account_payload($user),'csrf'=>csrf_token()]);
}

if ($action === 'register') {
    $name = trim((string)($data['name'] ?? ''));
    $email = trim((string)($data['email'] ?? ''));
    $username = trim((string)($data['username'] ?? ''));
    $password = (string)($data['password'] ?? '');
    if ($name === '' || $email === '' || $username === '' || strlen($password) < 4) json_response(['ok'=>false,'error'=>'Preencha nome, e-mail, usuário e uma senha com pelo menos 4 caracteres.'], 422);
    $check = db()->prepare('SELECT id FROM users WHERE email = ? OR username = ? LIMIT 1');
    $check->execute([$email,$username]);
    if ($check->fetchColumn()) json_response(['ok'=>false,'error'=>'E-mail ou usuário já cadastrado.'], 409);
    $now = date('Y-m-d H:i:s');
    $i = db()->prepare('INSERT INTO users (name,email,username,password_hash,role,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)');
    $i->execute([$name,$email,$username,password_hash($password,PASSWORD_DEFAULT),'client','active',$now,$now]);
    $id = (int)db()->lastInsertId();
    $s = db()->prepare('SELECT id,name,email,username,role,status,last_login_at,created_at FROM users WHERE id = ? LIMIT 1');
    $s->execute([$id]);
    $user = $s->fetch();
    native_start_user_session($user);
    $token = native_device_token_from_request($data);
    native_bind_device($id,$token,native_device_label_from_request($data));
    audit_log('native.register',['user_id'=>$id]);
    json_response(['ok'=>true,'account'=>native_account_payload($user),'csrf'=>csrf_token()]);
}

if ($action === 'device_login') {
    $token = native_device_token_from_request($data);
    if ($token === '') json_response(['ok'=>false,'error'=>'Identificação do aparelho inválida.'], 422);
    $user = native_user_for_device($token);
    if (!$user) json_response(['ok'=>false,'error'=>'Este aparelho ainda não está registrado.'], 401);
    native_start_user_session($user);
    native_bind_device((int)$user['id'],$token,native_device_label_from_request($data));
    audit_log('native.device_login',['user_id'=>(int)$user['id']]);
    json_response(['ok'=>true,'account'=>native_account_payload($user),'csrf'=>csrf_token()]);
}

if ($action === 'library_fast' || $action === 'library') {
    $user = native_require_json_user($data);
    $rows = db()->query('SELECT * FROM music_library ORDER BY title ASC LIMIT 5000')->fetchAll() ?: [];
    json_response(['ok'=>true,'tracks'=>native_library_rows($rows),'meta'=>native_library_meta(),'account'=>native_account_payload($user),'csrf'=>csrf_token()]);
}

if ($action === 'library_sync') {
    $user = native_require_json_user($data);
    try { $sync = native_library_sync_active_drive_folders(); }
    catch (Throwable $e) { json_response(['ok'=>false,'error'=>'Falha ao sincronizar o Google Drive.','detail'=>$e->getMessage(),'meta'=>native_library_meta()],502); }
    $rows = db()->query('SELECT * FROM music_library ORDER BY title ASC LIMIT 5000')->fetchAll() ?: [];
    json_response(['ok'=>true,'tracks'=>native_library_rows($rows),'sync'=>$sync,'meta'=>native_library_meta(),'account'=>native_account_payload($user),'csrf'=>csrf_token()]);
}

if ($action === 'library_status') {
    $user = native_require_json_user($data);
    json_response(['ok'=>true,'meta'=>native_library_meta(),'account'=>native_account_payload($user),'csrf'=>csrf_token()]);
}

if ($action === 'session') {
    $user = native_restore_user_from_request($data);
    if (!$user) json_response(['ok'=>false,'authenticated'=>false],401);
    json_response(['ok'=>true,'account'=>native_account_payload($user),'csrf'=>csrf_token()]);
}

json_response(['ok'=>false,'error'=>'Ação inválida.'],400);

function native_library_rows(array $rows): array
{
    foreach ($rows as &$r) {
        $r['id'] = (string)($r['id'] ?? '');
        $origin = strtolower((string)($r['origin'] ?? ''));
        $ref = trim((string)($r['origin_ref'] ?? ''));
        $source = trim((string)($r['source_url'] ?? ''));
        if ($source === '') {
            if ($ref !== '' && (str_contains($origin,'drive') || preg_match('/^[A-Za-z0-9_-]{20,}$/',$ref))) $source = 'api/drive_stream.php?id='.rawurlencode($ref);
            elseif ($ref !== '' && preg_match('~^https?://~i',$ref)) $source = $ref;
        }
        $r['source'] = $source;
        $r['content_uri'] = $source;
        $r['duration_ms'] = ((int)($r['duration'] ?? 0))*1000;
        $r['file_size'] = (int)($r['file_size'] ?? 0);
        $folder = trim((string)($r['folder'] ?? ''));
        if ($folder === '' && str_contains($origin,'drive')) $folder = trim((string)($r['artist'] ?? ''));
        $r['folder'] = $folder;
        $r['folder_path'] = $folder;
    }
    unset($r);
    return $rows;
}

function native_library_meta(): array
{
    try { $tracks = (int)db()->query('SELECT COUNT(*) FROM music_library')->fetchColumn(); } catch (Throwable $e) { $tracks = 0; }
    try { $roots = (int)db()->query('SELECT COUNT(*) FROM drive_folders WHERE active = 1')->fetchColumn(); } catch (Throwable $e) { $roots = 0; }
    return ['tracks'=>$tracks,'active_roots'=>$roots,'last_sync'=>app_setting('native_library_last_sync',''),'db_driver'=>(string)db()->getAttribute(PDO::ATTR_DRIVER_NAME)];
}
'''
Path("api/native_app.php").write_text(native_app)


# ---------- Harden public Drive sync ----------
scan = Path("api/native_library_sync.php")
s = scan.read_text()
s = replace_once(s, "function native_library_sync_active_drive_folders(): array\n{", "function native_library_sync_active_drive_folders(): array\n{\n    // DRIVE_SYNC_V207: this runs outside the UI request path.\n    if (function_exists('set_time_limit')) @set_time_limit(180);", "sync marker")
public_block = r'''function native_library_list_public(string $folderId): array
{
    foreach ([
        'https://drive.google.com/embeddedfolderview?id='.rawurlencode($folderId).'#list',
        'https://drive.google.com/drive/folders/'.rawurlencode($folderId).'?usp=sharing',
    ] as $url) {
        $html = native_library_http_text($url);
        if (!$html) continue;
        $decoded = html_entity_decode($html, ENT_QUOTES | ENT_HTML5, 'UTF-8');
        $out = [];
        preg_match_all('~href="(?:https://drive\\.google\\.com)?/drive/folders/([A-Za-z0-9_-]+)[^"]*"[^>]*>(.*?)</a>~is', $decoded, $fm, PREG_SET_ORDER);
        foreach ($fm as $m) if ($m[1] !== $folderId) $out[] = ['kind'=>'folder','id'=>$m[1],'name'=>native_library_clean_name($m[2]) ?: 'Subpasta'];
        preg_match_all('~href="(?:https://drive\\.google\\.com)?/file/d/([A-Za-z0-9_-]+)[^"]*"[^>]*>(.*?)</a>~is', $decoded, $files, PREG_SET_ORDER);
        foreach ($files as $m) { $name=native_library_clean_name($m[2]); if ($name!=='') $out[]=['kind'=>'file','id'=>$m[1],'name'=>$name,'mime_type'=>native_library_mime($name),'file_size'=>0,'cover_url'=>'']; }
        preg_match_all('~href="(?:https://drive\\.google\\.com)?/(?:open|uc)\\?[^\"]*id=([A-Za-z0-9_-]+)[^\"]*"[^>]*>(.*?)</a>~is', $decoded, $om, PREG_SET_ORDER);
        foreach ($om as $m) { $name=native_library_clean_name($m[2]); if (native_library_is_audio($name,'')) $out[]=['kind'=>'file','id'=>$m[1],'name'=>$name,'mime_type'=>native_library_mime($name),'file_size'=>0,'cover_url'=>'']; }
        preg_match_all('~data-id="([A-Za-z0-9_-]{20,})"[^>]+aria-label="([^"]+\\.(?:mp3|aac|m4a|ogg|oga|opus|flac|wav|wave))"~is', $decoded, $dm, PREG_SET_ORDER);
        foreach ($dm as $m) $out[]=['kind'=>'file','id'=>$m[1],'name'=>html_entity_decode($m[2],ENT_QUOTES|ENT_HTML5,'UTF-8'),'mime_type'=>native_library_mime($m[2]),'file_size'=>0,'cover_url'=>''];
        preg_match_all('~aria-label="([^"]+\\.(?:mp3|aac|m4a|ogg|oga|opus|flac|wav|wave))"[^>]+data-id="([A-Za-z0-9_-]{20,})"~is', $decoded, $am, PREG_SET_ORDER);
        foreach ($am as $m) $out[]=['kind'=>'file','id'=>$m[2],'name'=>html_entity_decode($m[1],ENT_QUOTES|ENT_HTML5,'UTF-8'),'mime_type'=>native_library_mime($m[1]),'file_size'=>0,'cover_url'=>''];
        preg_match_all('~\\["([A-Za-z0-9_-]{20,})"\\s*,\\s*"([^"]+\\.(?:mp3|aac|m4a|ogg|oga|opus|flac|wav|wave))"~i', $decoded, $jm, PREG_SET_ORDER);
        foreach ($jm as $m) $out[]=['kind'=>'file','id'=>$m[1],'name'=>stripcslashes($m[2]),'mime_type'=>native_library_mime($m[2]),'file_size'=>0,'cover_url'=>''];
        preg_match_all('~"([A-Za-z0-9_-]{20,})"[^\"]{0,900}"([^"]+\\.(?:mp3|aac|m4a|ogg|oga|opus|flac|wav|wave))"~i', $decoded, $lm, PREG_SET_ORDER);
        foreach ($lm as $m) $out[]=['kind'=>'file','id'=>$m[1],'name'=>stripcslashes($m[2]),'mime_type'=>native_library_mime($m[2]),'file_size'=>0,'cover_url'=>''];
        preg_match_all('~\\["([A-Za-z0-9_-]{20,})"\\s*,\\s*"([^"]+)".{0,1200}?application/vnd\\.google-apps\\.folder~is', $decoded, $jf, PREG_SET_ORDER);
        foreach ($jf as $m) if ($m[1] !== $folderId) $out[]=['kind'=>'folder','id'=>$m[1],'name'=>native_library_clean_name(stripcslashes($m[2])) ?: 'Subpasta'];
        $out = native_library_unique_entries($out);
        if ($out) return $out;
    }
    return [];
}

function native_library_http_text(string $url): ?string
{
    $headers = ['User-Agent: Mozilla/5.0 EstradaPlay/2.0.7','Accept: text/html,application/xhtml+xml,*/*;q=0.8','Accept-Language: pt-BR,pt;q=0.9'];
    if (function_exists('curl_init')) {
        $ch = curl_init($url);
        curl_setopt_array($ch,[CURLOPT_RETURNTRANSFER=>true,CURLOPT_FOLLOWLOCATION=>true,CURLOPT_CONNECTTIMEOUT=>4,CURLOPT_TIMEOUT=>10,CURLOPT_HTTPHEADER=>$headers,CURLOPT_ENCODING=>'']);
        $body = curl_exec($ch); $status = (int)curl_getinfo($ch,CURLINFO_RESPONSE_CODE); curl_close($ch);
        return $body && $status < 400 ? (string)$body : null;
    }
    $ctx = stream_context_create(['http'=>['header'=>implode("\r\n",$headers),'timeout'=>10]]);
    $body = @file_get_contents($url,false,$ctx);
    return $body ? (string)$body : null;
}
'''
s = regex_once(s, r'function native_library_list_public\(string \$folderId\): array.*?function native_library_clean_name', public_block + '\nfunction native_library_clean_name', "Drive public scanner")
scan.write_text(s)


# ---------- Build/release packaging ----------
workflow = Path(".github/workflows/build-estradaplay-v2.yml")
s = workflow.read_text()
s = replace_once(s,
    "          grep -q 'X-EstradaPlay-Device' estradaplay-app-v2/app/src/main/java/com/estradaplay/app/ApiClient.java\n",
    "          grep -q 'X-EstradaPlay-Device' estradaplay-app-v2/app/src/main/java/com/estradaplay/app/ApiClient.java\n          grep -q 'LIBRARY_NONBLOCKING_V207' estradaplay-app-v2/app/src/main/java/com/estradaplay/app/MainActivity.java\n          grep -q 'LIBRARY_FAST_V207' estradaplay-app-v2/app/src/main/java/com/estradaplay/app/ApiClient.java\n",
    "build verify markers")
s = replace_once(s, "          php -l api/library.php\n", "          php -l api/bootstrap.php\n          php -l api/library.php\n", "build php lint")
s = replace_once(s, "          cp api/library.php \"$PKG/api/\"\n", "          cp api/bootstrap.php \"$PKG/api/\"\n          cp api/library.php \"$PKG/api/\"\n", "build package bootstrap")
s = s.replace("          EstradaPlay 2.0.5 - correção de biblioteca\n", "          EstradaPlay 2.0.7 - biblioteca e servidor\n", 1)
s = s.replace("          Esta atualização restaura sessão por dispositivo e sincroniza novas pastas do Google Drive.\n", "          Esta atualização torna a listagem imediata, separa a sincronização do Drive e corrige SQLite/MySQL/MariaDB.\n", 1)
anchor = '''          if [ "$VERSION" = "2.0.5" ]; then
            NOTES="Corrige a biblioteca após reinstalação e a descoberta de novas pastas do Google Drive. O app envia uma identidade estável do aparelho em toda chamada; o servidor reconstrói a sessão, refaz a varredura das pastas ativas e mantém fallback da biblioteca já salva. Inclui pacote de servidor."
          fi
'''
extra = anchor + '''          if [ "$VERSION" = "2.0.7" ]; then
            NOTES="Versão de teste focada em biblioteca: abre catálogo salvo imediatamente, separa listagem rápida da sincronização do Google Drive, recupera sessão do aparelho, adiciona atualização manual, evita carregamento infinito, reforça downloads autenticados e inclui correção SQLite/MySQL/MariaDB no servidor."
          fi
'''
s = replace_once(s, anchor, extra, "build release notes")
workflow.write_text(s)

print("EstradaPlay 2.0.7 migration prepared")
