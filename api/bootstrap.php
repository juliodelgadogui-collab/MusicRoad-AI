<?php
declare(strict_types=1);

// ESTRADAPLAY_SETUP_REQUIRED_V6: clean-host packages contain no credentials.
$configFile = __DIR__ . '/../config/config.php';
if (!is_file($configFile)) {
    if (PHP_SAPI !== 'cli') {
        $accept = strtolower((string)($_SERVER['HTTP_ACCEPT'] ?? ''));
        $uri = (string)($_SERVER['REQUEST_URI'] ?? '');
        if (str_contains($accept, 'application/json') || str_contains($uri, '/api/')) {
            http_response_code(503); header('Content-Type: application/json; charset=utf-8');
            echo json_encode(['ok'=>false,'setup_required'=>true,'error'=>'Servidor ainda não instalado.'], JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES); exit;
        }
        header('Location: install.php'); exit;
    }
    throw new RuntimeException('Servidor ainda não instalado: execute install.php');
}
$config = require $configFile;

$logsDir = __DIR__ . '/../logs';
if (!is_dir($logsDir)) {
    @mkdir($logsDir, 0775, true);
}
ini_set('display_errors', ($config['env'] ?? 'production') === 'development' ? '1' : '0');
ini_set('log_errors', '1');
ini_set('error_log', $logsDir . '/php-error.log');

session_name((string)($config['security']['session_name'] ?? 'MUSICROADAISESSID'));
session_set_cookie_params([
    'httponly' => true,
    'secure' => (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off'),
    'samesite' => 'Lax',
    'path' => '/',
]);
if (session_status() !== PHP_SESSION_ACTIVE) {
    session_start();
}

header('X-Content-Type-Options: nosniff');
header('X-Frame-Options: SAMEORIGIN');
header('Referrer-Policy: strict-origin-when-cross-origin');
header('Permissions-Policy: geolocation=(self)');

function db(): PDO
{
    static $pdo = null;
    global $config;
    if ($pdo instanceof PDO) return $pdo;

    if (($config['db']['driver'] ?? 'sqlite') === 'mysql') {
        $pdo = new PDO((string)$config['db']['mysql_dsn'], (string)$config['db']['mysql_user'], (string)$config['db']['mysql_pass']);
    } else {
        $path = (string)$config['db']['sqlite_path'];
        $dir = dirname($path);
        if (!is_dir($dir)) @mkdir($dir, 0775, true);
        $pdo = new PDO('sqlite:' . $path);
        $pdo->exec('PRAGMA foreign_keys = ON');
        $pdo->exec('PRAGMA journal_mode = WAL');
        $pdo->exec('PRAGMA busy_timeout = 5000');
    }
    $pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    $pdo->setAttribute(PDO::ATTR_DEFAULT_FETCH_MODE, PDO::FETCH_ASSOC);
    return $pdo;
}

// SERVER_DB_COMPAT_V209: runtime PHP/SQL is normalized for SQLite and MySQL/MariaDB.
// DB_COMPAT_V207: minimum runtime schema compatible with SQLite and MySQL/MariaDB.
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
    if (function_exists('server_ensure_500mb_schema')) server_ensure_500mb_schema();
}

function ensure_default_users(): void
{
    ensure_schema();
    global $config;
    if (($config['env'] ?? 'production') !== 'development') return;
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

// Compatibilidade com os arquivos anteriores.
function ensure_default_admin(): void { ensure_default_users(); }
function ensure_runtime_tables(): void { ensure_schema(); if (function_exists('server_housekeeping')) server_housekeeping(); }

function current_user(): ?array
{
    ensure_schema();
    $id = (int)($_SESSION['user_id'] ?? 0);
    if ($id <= 0) return null;
    $stmt = db()->prepare('SELECT id,name,email,username,role,status,last_login_at,created_at FROM users WHERE id = ? LIMIT 1');
    $stmt->execute([$id]);
    $user = $stmt->fetch();
    if (!$user || ($user['status'] ?? 'active') !== 'active') {
        unset($_SESSION['user_id']);
        return null;
    }
    return $user;
}

function require_login(): array
{
    $user = current_user();
    if (!$user) {
        header('Location: login.php');
        exit;
    }
    return $user;
}

function require_admin(): array
{
    $user = require_login();
    if (($user['role'] ?? '') !== 'admin') {
        http_response_code(403);
        echo 'Acesso negado.';
        exit;
    }
    return $user;
}

function require_client(): array
{
    $user = require_login();
    if (($user['role'] ?? '') !== 'client') {
        header('Location: admin.php');
        exit;
    }
    return $user;
}

// APP_SETTINGS_KEY_QUOTE_V236: `key` is a MySQL/MariaDB keyword. Always quote it when reading.
function app_setting(string $key, ?string $default = null): ?string
{
    ensure_schema();
    try {
        $stmt = db()->prepare('SELECT value FROM app_settings WHERE `key` = ? LIMIT 1');
        $stmt->execute([$key]);
        $value = $stmt->fetchColumn();
        return $value === false ? $default : (string)$value;
    } catch (Throwable $e) {
        error_log('app_setting read failed: ' . $e->getMessage());
        return $default;
    }
}

function set_app_setting(string $key, ?string $value): void
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

function google_api_key(): string
{
    global $config;
    $panel = trim((string)app_setting('google_api_key', ''));
    return $panel !== '' ? $panel : trim((string)($config['google']['api_key'] ?? ''));
}

function drive_folder_id_from_link(string $link): ?string
{
    if (preg_match('~/folders/([A-Za-z0-9_-]+)~', $link, $m)) return $m[1];
    if (preg_match('~embeddedfolderview\?[^#]*[?&]id=([A-Za-z0-9_-]+)~', $link, $m)) return $m[1];
    if (preg_match('~^[A-Za-z0-9_-]{20,}$~', trim($link))) return trim($link);
    return null;
}

function drive_file_id_from_link(string $link): ?string
{
    if (preg_match('~/file/d/([A-Za-z0-9_-]+)~', $link, $m)) return $m[1];
    if (preg_match('~[?&]id=([A-Za-z0-9_-]+)~', $link, $m)) return $m[1];
    return null;
}

function json_response(array $data, int $status = 200): void
{
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function input_json(): array
{
    $raw = file_get_contents('php://input') ?: '';
    $data = json_decode($raw, true);
    return is_array($data) ? $data : $_POST;
}

function csrf_token(): string
{
    if (empty($_SESSION['csrf'])) $_SESSION['csrf'] = bin2hex(random_bytes(32));
    return (string)$_SESSION['csrf'];
}

function require_csrf(): void
{
    $token = $_SERVER['HTTP_X_CSRF_TOKEN'] ?? ($_POST['csrf'] ?? '');
    if (!hash_equals((string)($_SESSION['csrf'] ?? ''), (string)$token)) {
        if (str_contains((string)($_SERVER['HTTP_ACCEPT'] ?? ''), 'application/json') || str_contains((string)($_SERVER['CONTENT_TYPE'] ?? ''), 'application/json')) {
            json_response(['ok'=>false,'error'=>'CSRF inválido. Atualize a página.'], 419);
        }
        http_response_code(419);
        exit('Sessão expirada. Atualize a página.');
    }
}

function audit_log(string $action, array $payload = []): void
{
    ensure_schema();
    try {
        $stmt = db()->prepare("INSERT INTO audit_logs (action,payload,ip,created_at) VALUES (?,?,?,?)");
        $stmt->execute([$action, json_encode($payload, JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES), $_SERVER['REMOTE_ADDR'] ?? 'cli', date('Y-m-d H:i:s')]);
    } catch (Throwable $e) {}
}

function haversine_m(float $lat1, float $lon1, float $lat2, float $lon2): float
{
    $r = 6371000;
    $dLat = deg2rad($lat2 - $lat1); $dLon = deg2rad($lon2 - $lon1);
    $a = sin($dLat/2)**2 + cos(deg2rad($lat1))*cos(deg2rad($lat2))*sin($dLon/2)**2;
    return $r * 2 * atan2(sqrt($a), sqrt(1-$a));
}

function http_json(string $url, ?string $postBody = null, array $headers = []): ?array
{
    global $config;
    $defaultHeaders = ['User-Agent: ' . ($config['routing']['user_agent'] ?? 'MusicRoadAI/1.0'), 'Accept: application/json'];
    if (!function_exists('curl_init')) {
        $context = stream_context_create(['http'=>['method'=>$postBody===null?'GET':'POST','header'=>implode("\r\n",array_merge($defaultHeaders,$headers)),'content'=>$postBody??'','timeout'=>25]]);
        $raw = @file_get_contents($url, false, $context);
        $data = $raw ? json_decode($raw,true) : null;
        return is_array($data) ? $data : null;
    }
    $ch = curl_init($url);
    curl_setopt_array($ch,[CURLOPT_RETURNTRANSFER=>true,CURLOPT_FOLLOWLOCATION=>true,CURLOPT_CONNECTTIMEOUT=>10,CURLOPT_TIMEOUT=>25,CURLOPT_HTTPHEADER=>array_merge($defaultHeaders,$headers),CURLOPT_ENCODING=>'']);
    if ($postBody !== null) { curl_setopt($ch,CURLOPT_POST,true); curl_setopt($ch,CURLOPT_POSTFIELDS,$postBody); }
    $raw = curl_exec($ch); $status = (int)curl_getinfo($ch,CURLINFO_RESPONSE_CODE); curl_close($ch);
    if (!$raw || $status >= 400) return null;
    $data = json_decode((string)$raw,true);
    return is_array($data) ? $data : null;
}

require_once __DIR__ . '/server_500mb.php';
server_rotate_logs();

// AUTH_SALT_BRIDGE_V236: v2.3.5 diagnostics exposed that older MySQL reads could not see
// app_settings.`key`. Existing secure credentials were therefore hashed with the private file
// fallback. Before native_auth.php is loaded, copy that already-effective salt into the database
// so fixing the SQL reader cannot silently switch hash identity and invalidate those credentials.
function ensure_native_auth_salt_storage_v236(): void
{
    $file = __DIR__ . '/../config/native-auth-salt-v207.php';
    if (!is_file($file)) return;
    try { $fileSalt = include $file; }
    catch (Throwable $e) { return; }
    $fileSalt = strtolower(trim(is_string($fileSalt) ? $fileSalt : ''));
    if (!preg_match('/^[a-f0-9]{64}$/', $fileSalt)) return;

    $dbSalt = strtolower(trim((string)app_setting('native_auth_salt_v207', '')));
    if ($dbSalt !== $fileSalt) {
        try { set_app_setting('native_auth_salt_v207', $fileSalt); }
        catch (Throwable $e) { error_log('AUTH_SALT_BRIDGE_V236 write failed: ' . $e->getMessage()); return; }
    }
    $verify = strtolower(trim((string)app_setting('native_auth_salt_v207', '')));
    if (!hash_equals($fileSalt, $verify)) error_log('AUTH_SALT_BRIDGE_V236 verify failed');
}
ensure_native_auth_salt_storage_v236();