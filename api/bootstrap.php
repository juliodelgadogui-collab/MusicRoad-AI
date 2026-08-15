<?php
declare(strict_types=1);

$config = require __DIR__ . '/../config/config.php';

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

function schema_columns(string $table): array
{
    try {
        $driver = db()->getAttribute(PDO::ATTR_DRIVER_NAME);
        if ($driver === 'sqlite') {
            $rows = db()->query("PRAGMA table_info(" . preg_replace('/[^a-zA-Z0-9_]/', '', $table) . ")")->fetchAll();
            return array_map(fn($r) => (string)$r['name'], $rows);
        }
    } catch (Throwable $e) {}
    return [];
}

function ensure_schema(): void
{
    static $done = false;
    if ($done) return;
    $done = true;

    $sql = @file_get_contents(__DIR__ . '/../database/schema.sql');
    if ($sql !== false) {
        try { db()->exec($sql); } catch (Throwable $e) { error_log('Schema: ' . $e->getMessage()); }
    }

    // Migração leve para instalações v7.x.
    $cols = schema_columns('users');
    foreach ([
        'status' => "TEXT NOT NULL DEFAULT 'active'",
        'last_login_at' => 'TEXT',
        'updated_at' => 'TEXT',
    ] as $name => $definition) {
        if ($cols && !in_array($name, $cols, true)) {
            try { db()->exec("ALTER TABLE users ADD COLUMN $name $definition"); } catch (Throwable $e) {}
        }
    }

    try {
        db()->exec("UPDATE users SET role = 'client' WHERE role IN ('user','cliente')");
        db()->exec("UPDATE users SET status = 'active' WHERE status IS NULL OR status = ''");
    } catch (Throwable $e) {}

    // Tabelas/índices que precisam existir mesmo em bancos antigos.
    db()->exec("CREATE TABLE IF NOT EXISTS user_music_state (
        user_id INTEGER NOT NULL,
        music_id INTEGER NOT NULL,
        is_favorite INTEGER NOT NULL DEFAULT 0,
        play_count INTEGER NOT NULL DEFAULT 0,
        skip_count INTEGER NOT NULL DEFAULT 0,
        last_played_at TEXT,
        PRIMARY KEY (user_id, music_id)
    )");
    db()->exec("CREATE TABLE IF NOT EXISTS client_device_state (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id INTEGER NOT NULL,
        device_id TEXT NOT NULL,
        device_name TEXT,
        music_permission TEXT NOT NULL DEFAULT 'unknown',
        location_permission TEXT NOT NULL DEFAULT 'unknown',
        music_folder_count INTEGER NOT NULL DEFAULT 0,
        music_track_count INTEGER NOT NULL DEFAULT 0,
        last_latitude REAL,
        last_longitude REAL,
        last_seen_at TEXT NOT NULL,
        UNIQUE(user_id, device_id)
    )");
    db()->exec('CREATE TABLE IF NOT EXISTS app_settings (key TEXT PRIMARY KEY, value TEXT)');
    db()->exec('CREATE TABLE IF NOT EXISTS audit_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, action TEXT NOT NULL, payload TEXT, ip TEXT, created_at TEXT NOT NULL)');
}

function ensure_default_users(): void
{
    ensure_schema();
    $adminCount = (int)db()->query("SELECT COUNT(*) FROM users WHERE role = 'admin'")->fetchColumn();
    if ($adminCount === 0) {
        $stmt = db()->prepare("INSERT INTO users (name,email,username,password_hash,role,status,created_at,updated_at) VALUES (?,?,?,?,?,'active',datetime('now'),datetime('now'))");
        $stmt->execute(['Administrador', 'admin@musicroad.local', 'adm', password_hash('1', PASSWORD_DEFAULT), 'admin']);
    }
    $clientCount = (int)db()->query("SELECT COUNT(*) FROM users WHERE role = 'client'")->fetchColumn();
    if ($clientCount === 0) {
        $stmt = db()->prepare("INSERT INTO users (name,email,username,password_hash,role,status,created_at,updated_at) VALUES (?,?,?,?,?,'active',datetime('now'),datetime('now'))");
        $stmt->execute(['Cliente Teste', 'cliente@musicroad.local', 'cliente', password_hash('1', PASSWORD_DEFAULT), 'client']);
    }
}

// Compatibilidade com os arquivos anteriores.
function ensure_default_admin(): void { ensure_default_users(); }
function ensure_runtime_tables(): void { ensure_schema(); }

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

function app_setting(string $key, ?string $default = null): ?string
{
    ensure_schema();
    try {
        $stmt = db()->prepare('SELECT value FROM app_settings WHERE key = ? LIMIT 1');
        $stmt->execute([$key]);
        $value = $stmt->fetchColumn();
        return $value === false ? $default : (string)$value;
    } catch (Throwable $e) { return $default; }
}

function set_app_setting(string $key, ?string $value): void
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
        $stmt = db()->prepare("INSERT INTO audit_logs (action,payload,ip,created_at) VALUES (?,?,?,datetime('now'))");
        $stmt->execute([$action, json_encode($payload, JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES), $_SERVER['REMOTE_ADDR'] ?? 'cli']);
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
