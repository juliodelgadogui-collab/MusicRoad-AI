<?php
declare(strict_types=1);

const MUSICROAD_VERSION = '1.2.0';
const MUSICROAD_VERSION_CODE = 26;
const MUSICROAD_SCHEMA_VERSION = '1.2.0';

$rootDir = dirname(__DIR__);
$configFile = $rootDir . '/config/config.php';
$installLock = $rootDir . '/storage/installed.lock';

if (!is_file($configFile) || !is_file($installLock)) {
    if (PHP_SAPI === 'cli') {
        throw new RuntimeException('MusicRoad ainda não foi instalado. Execute /install/.');
    }
    $uri = (string)($_SERVER['REQUEST_URI'] ?? '');
    if (!str_contains($uri, '/install/')) {
        $scriptName = str_replace('\\','/',(string)($_SERVER['SCRIPT_NAME'] ?? '/index.php'));
        $basePath = preg_replace('~/(?:api/[^/]+|[^/]+)$~','',$scriptName) ?: '';
        header('Location: ' . rtrim($basePath,'/') . '/install/');
        exit;
    }
}

$config = is_file($configFile) ? require $configFile : [];
if (PHP_SAPI !== 'cli' && !empty($config['force_https']) && (empty($_SERVER['HTTPS']) || $_SERVER['HTTPS'] === 'off')) {
    $appUri = parse_url((string)($config['app_url'] ?? ''));
    $canonicalHost = is_array($appUri) ? (string)($appUri['host'] ?? '') : '';
    if ($canonicalHost !== '') {
        $canonicalPort = isset($appUri['port']) ? ':' . (int)$appUri['port'] : '';
        $requestUri = (string)($_SERVER['REQUEST_URI'] ?? '/');
        if (!str_starts_with($requestUri,'/')) $requestUri = '/';
        header('Location: https://' . $canonicalHost . $canonicalPort . $requestUri, true, 308);
        exit;
    }
}
$logsDir = $rootDir . '/logs';
if (!is_dir($logsDir)) @mkdir($logsDir, 0775, true);
ini_set('display_errors', (($config['env'] ?? 'production') === 'development') ? '1' : '0');
ini_set('log_errors', '1');
ini_set('error_log', $logsDir . '/php-error.log');

ini_set('session.use_strict_mode','1');
ini_set('session.use_only_cookies','1');
ini_set('session.cookie_httponly','1');
session_name((string)($config['security']['session_name'] ?? 'MUSICROADAISESSID'));
$sessionPath=(string)(parse_url((string)($config['app_url']??''),PHP_URL_PATH)?:'/');
if(!str_starts_with($sessionPath,'/'))$sessionPath='/';if(!str_ends_with($sessionPath,'/'))$sessionPath.='/';
session_set_cookie_params([
    'httponly' => true,
    'secure' => (bool)($config['force_https'] ?? (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')),
    'samesite' => 'Lax',
    'path' => $sessionPath,
]);
if ((!defined('MUSICROAD_STATELESS_REQUEST') || MUSICROAD_STATELESS_REQUEST !== true) && session_status() !== PHP_SESSION_ACTIVE) session_start();

$cspNonce = base64_encode(random_bytes(18));
header('X-Content-Type-Options: nosniff');
header('X-Frame-Options: SAMEORIGIN');
header('Referrer-Policy: strict-origin-when-cross-origin');
header('Permissions-Policy: geolocation=(self)');
header("Content-Security-Policy: default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'self'; form-action 'self'; script-src 'self' 'nonce-{$cspNonce}'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https:; media-src 'self' data: blob: https:; connect-src 'self' https://*.tile.openstreetmap.org https://servicodados.ibge.gov.br https://raw.githubusercontent.com; worker-src 'self' blob:; manifest-src 'self'");
if (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') {
    header('Strict-Transport-Security: max-age=31536000; includeSubDomains');
}

function csp_nonce(): string
{
    global $cspNonce;
    return (string)$cspNonce;
}


function db_driver(): string
{
    global $config;
    return strtolower((string)($config['db']['driver'] ?? 'mysql'));
}

function db(): PDO
{
    static $pdo = null;
    global $config;
    if ($pdo instanceof PDO) return $pdo;

    $driver = db_driver();
    if (!in_array($driver, ['mysql','mariadb'], true)) {
        throw new RuntimeException('MusicRoad 1.2 SecureDB exige MariaDB/MySQL. Execute o instalador 1.2.0.');
    }
    if (!extension_loaded('pdo_mysql')) {
        throw new RuntimeException('Extensão PDO MySQL não está habilitada no servidor.');
    }
    $db = (array)($config['db'] ?? []);
    $host = trim((string)($db['host'] ?? '127.0.0.1'));
    $port = max(1, (int)($db['port'] ?? 3306));
    $name = trim((string)($db['name'] ?? ''));
    $user = (string)($db['user'] ?? '');
    $pass = (string)(getenv('MUSICROAD_DB_PASSWORD') !== false ? getenv('MUSICROAD_DB_PASSWORD') : ($db['password'] ?? ''));
    if ($name === '' || $user === '') throw new RuntimeException('Banco MariaDB/MySQL não configurado.');

    $dsn = "mysql:host={$host};port={$port};dbname={$name};charset=utf8mb4";
    $opts = [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES => false,
        PDO::ATTR_STRINGIFY_FETCHES => false,
        PDO::ATTR_TIMEOUT => 8,
    ];
    if (!empty($db['ssl_ca']) && defined('PDO::MYSQL_ATTR_SSL_CA')) {
        $opts[PDO::MYSQL_ATTR_SSL_CA] = (string)$db['ssl_ca'];
        if (defined('PDO::MYSQL_ATTR_SSL_VERIFY_SERVER_CERT')) $opts[PDO::MYSQL_ATTR_SSL_VERIFY_SERVER_CERT] = true;
    }
    $pdo = new PDO($dsn, $user, $pass, $opts);
    $pdo->exec("SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci");
    try { $pdo->exec("SET SESSION sql_mode='STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION'"); } catch (Throwable $e) {}
    return $pdo;
}

function db_exec_schema(PDO $pdo, string $sql): void
{
    // O schema MusicRoad não contém procedures; dividir por ; mantém compatibilidade
    // com hospedagens que desabilitam multi-statements no PDO MySQL.
    $sql = preg_replace('/^\s*--.*$/m', '', $sql) ?? $sql;
    foreach (preg_split('/;\s*(?:\r?\n|$)/', $sql) ?: [] as $statement) {
        $statement = trim($statement);
        if ($statement !== '') $pdo->exec($statement);
    }
}

function db_column_exists(PDO $pdo, string $table, string $column): bool
{
    $stmt = $pdo->prepare('SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=? LIMIT 1');
    $stmt->execute([$table, $column]);
    return (bool)$stmt->fetchColumn();
}

function db_index_exists(PDO $pdo, string $table, string $index): bool
{
    $stmt = $pdo->prepare('SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=? LIMIT 1');
    $stmt->execute([$table, $index]);
    return (bool)$stmt->fetchColumn();
}

function db_constraint_exists(PDO $pdo, string $table, string $constraint): bool
{
    $stmt = $pdo->prepare('SELECT 1 FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME=? AND CONSTRAINT_NAME=? LIMIT 1');
    $stmt->execute([$table, $constraint]);
    return (bool)$stmt->fetchColumn();
}

function migrate_schema_1_2(PDO $pdo): void
{
    $columns = [
        'external_id' => 'VARCHAR(190) NULL AFTER user_id',
        'speed' => 'INT NULL AFTER type',
        'heading' => 'DOUBLE NULL AFTER speed',
        'source' => "VARCHAR(80) NOT NULL DEFAULT 'USUARIO' AFTER heading",
        'payload_json' => 'LONGTEXT NULL AFTER source',
        'reviewed_by' => 'BIGINT UNSIGNED NULL AFTER confirmations',
        'reviewed_at' => 'DATETIME NULL AFTER reviewed_by',
        'review_note' => 'TEXT NULL AFTER reviewed_at',
    ];
    foreach ($columns as $name => $definition) {
        if (!db_column_exists($pdo, 'road_reports', $name)) {
            $pdo->exec("ALTER TABLE road_reports ADD COLUMN `{$name}` {$definition}");
        }
    }
    $pdo->exec("ALTER TABLE road_reports MODIFY COLUMN status VARCHAR(80) NOT NULL DEFAULT 'PENDENTE'");
    if (!db_constraint_exists($pdo, 'road_reports', 'fk_rr_reviewer')) {
        $pdo->exec('ALTER TABLE road_reports ADD CONSTRAINT fk_rr_reviewer FOREIGN KEY(reviewed_by) REFERENCES users(id) ON DELETE SET NULL');
    }
    if (!db_column_exists($pdo, 'radars', 'expires_at')) {
        $pdo->exec('ALTER TABLE radars ADD COLUMN expires_at DATETIME NULL AFTER ativo');
    }
    if (!db_index_exists($pdo, 'road_reports', 'idx_road_reports_status_created')) {
        $pdo->exec('ALTER TABLE road_reports ADD INDEX idx_road_reports_status_created(status,created_at)');
    }
    if (!db_index_exists($pdo, 'road_reports', 'idx_road_reports_user_created')) {
        $pdo->exec('ALTER TABLE road_reports ADD INDEX idx_road_reports_user_created(user_id,created_at)');
    }
    if (!db_index_exists($pdo, 'radars', 'idx_radars_active_lat_lon')) {
        $pdo->exec('ALTER TABLE radars ADD INDEX idx_radars_active_lat_lon(ativo,latitude,longitude)');
    }
    if (!db_index_exists($pdo, 'radars', 'idx_radars_active_expires')) {
        $pdo->exec('ALTER TABLE radars ADD INDEX idx_radars_active_expires(ativo,expires_at)');
    }
}

function ensure_schema(): void
{
    static $done = false;
    static $running = false;
    if ($done || $running) return;
    $running = true;
    $lockAcquired = false;
    $lockName = '';
    try {
        $pdo = db();
        $currentVersion = null;
        try {
            $value = $pdo->query("SELECT `value` FROM schema_meta WHERE `key`='schema_version' LIMIT 1")->fetchColumn();
            if ($value !== false) $currentVersion = (string)$value;
        } catch (Throwable $e) {}
        if ($currentVersion !== null && version_compare($currentVersion, MUSICROAD_SCHEMA_VERSION, '>=')) {
            $done = true;
            return;
        }
        global $config;
        $lockName = 'musicroad-schema-' . substr(hash('sha256',(string)($config['db']['name'] ?? 'default')),0,32);
        $lock = $pdo->prepare('SELECT GET_LOCK(?,20)');
        $lock->execute([$lockName]);
        if ((int)$lock->fetchColumn() !== 1) throw new RuntimeException('Outra atualização do MusicRoad está em andamento. Tente novamente em alguns segundos.');
        $lockAcquired = true;
        $version = null;
        try {
            $value = $pdo->query("SELECT `value` FROM schema_meta WHERE `key`='schema_version' LIMIT 1")->fetchColumn();
            if ($value !== false) $version = (string)$value;
        } catch (Throwable $e) {}

        if ($version === null || version_compare($version, MUSICROAD_SCHEMA_VERSION, '<')) {
            $schema = dirname(__DIR__) . '/database/schema.mysql.sql';
            if (!is_file($schema)) throw new RuntimeException('Schema MariaDB/MySQL não encontrado.');
            db_exec_schema($pdo, (string)file_get_contents($schema));
            migrate_schema_1_2($pdo);
            $stmt = $pdo->prepare("INSERT INTO schema_meta(`key`,`value`) VALUES ('schema_version',?) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)");
            $stmt->execute([MUSICROAD_SCHEMA_VERSION]);
            $stmt = $pdo->prepare("INSERT INTO app_settings(`key`,`value`,updated_at) VALUES ('installed_version',?,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`),updated_at=CURRENT_TIMESTAMP");
            $stmt->execute([MUSICROAD_VERSION]);
        }
        $done = true;
    } finally {
        if ($lockAcquired) {
            try { $release = db()->prepare('SELECT RELEASE_LOCK(?)');$release->execute([$lockName]); } catch (Throwable $e) {}
        }
        $running = false;
    }
}

function ensure_runtime_tables(): void { ensure_schema(); }

function ensure_default_admin(): void
{
    ensure_schema();
    $count = (int)db()->query("SELECT COUNT(*) FROM users WHERE role='admin'")->fetchColumn();
    if ($count > 0) return;
    throw new RuntimeException('Administrador não encontrado. Execute novamente o instalador 1.2 para criar uma conta segura.');
}

function current_user(): ?array
{
    ensure_schema();
    $id = (int)($_SESSION['user_id'] ?? 0);
    if ($id <= 0) return null;
    $stmt = db()->prepare('SELECT id,name,email,username,role,status,last_login_at,created_at,updated_at FROM users WHERE id=? LIMIT 1');
    $stmt->execute([$id]);
    $user = $stmt->fetch();
    if (!$user || ($user['status'] ?? '') !== 'active') {
        unset($_SESSION['user_id']);
        return null;
    }
    return $user;
}

function latest_license(int $userId): ?array
{
    ensure_schema();
    $stmt = db()->prepare("SELECT l.*,p.name AS plan_name,p.code AS plan_code,p.duration_days,p.price_cents
        FROM licenses l LEFT JOIN plans p ON p.id=l.plan_id
        WHERE l.user_id=? ORDER BY l.ends_at DESC,l.id DESC LIMIT 1");
    $stmt->execute([$userId]);
    $row = $stmt->fetch();
    return $row ?: null;
}

function user_has_access(array $user): bool
{
    if (($user['role'] ?? '') === 'admin') return true;
    if (($user['status'] ?? '') !== 'active') return false;
    $license = latest_license((int)$user['id']);
    if (!$license || ($license['status'] ?? '') !== 'active') return false;
    return strtotime((string)$license['starts_at']) <= time() && strtotime((string)$license['ends_at']) >= time();
}

function require_login(): array
{
    $user = current_user();
    if (!$user) {
        if (request_expects_json()) json_response(['ok'=>false,'error'=>'Autenticação necessária.'],401);
        global $config;
        $base = rtrim((string)($config['app_url'] ?? ''), '/');
        header('Location: ' . ($base !== '' ? $base . '/login.php' : '/login.php'));
        exit;
    }
    if (($user['role'] ?? '') !== 'admin' && !user_has_access($user)) {
        $scriptName = (string)($_SERVER['SCRIPT_NAME'] ?? '');
        $script = basename($scriptName);
        if (!in_array($script, ['license.php','payment_checkout.php','payment_pix.php','payment_status.php','mercadopago_webhook.php','logout.php'], true)) {
            if (str_contains($scriptName, '/api/')) json_response(['ok'=>false,'error'=>'Licença expirada.','license_required'=>true],402);
            header('Location: license.php');
            exit;
        }
    }
    return $user;
}

function require_admin(): array
{
    $user = require_login();
    if (($user['role'] ?? '') !== 'admin') {
        if (request_expects_json()) json_response(['ok'=>false,'error'=>'Acesso administrativo necessário.'],403);
        http_response_code(403);
        exit('Acesso negado.');
    }
    return $user;
}

function require_client(): array
{
    $user = require_login();
    if (($user['role'] ?? '') !== 'client') {
        if (request_expects_json()) json_response(['ok'=>false,'error'=>'Acesso exclusivo para clientes.'],403);
        header('Location: admin.php');
        exit;
    }
    return $user;
}

function secret_setting_key(string $key): bool
{
    return in_array($key, ['mercadopago_access_token','mercadopago_webhook_secret','trial_server_secret','google_api_key'], true);
}

function app_crypto_key(): ?string
{
    global $config;
    $hex = trim((string)($config['security']['app_key'] ?? ''));
    if (preg_match('/^[a-f0-9]{64}$/i', $hex)) return hex2bin($hex) ?: null;
    return null;
}

function secret_encrypt(string $plain): string
{
    if ($plain === '' || str_starts_with($plain,'enc:v1:')) return $plain;
    $key = app_crypto_key();
    if ($key === null || !function_exists('openssl_encrypt')) {
        throw new RuntimeException('Criptografia indisponível. Configure security.app_key e OpenSSL antes de salvar segredos.');
    }
    $iv=random_bytes(12);$tag='';
    $cipher=openssl_encrypt($plain,'aes-256-gcm',$key,OPENSSL_RAW_DATA,$iv,$tag,'MusicRoad:setting');
    if (!is_string($cipher)) throw new RuntimeException('Não foi possível criptografar o segredo.');
    return 'enc:v1:'.base64_encode($iv.$tag.$cipher);
}

function secret_decrypt(string $stored): string
{
    if (!str_starts_with($stored,'enc:v1:')) return $stored;
    $key=app_crypto_key();if($key===null)return '';
    $raw=base64_decode(substr($stored,7),true);if(!is_string($raw)||strlen($raw)<29)return '';
    $iv=substr($raw,0,12);$tag=substr($raw,12,16);$cipher=substr($raw,28);
    $plain=openssl_decrypt($cipher,'aes-256-gcm',$key,OPENSSL_RAW_DATA,$iv,$tag,'MusicRoad:setting');
    return is_string($plain)?$plain:'';
}

function security_hmac(string $value): string
{
    $key=app_crypto_key();
    if($key===null)throw new RuntimeException('security.app_key não configurada. Atualize config/config.php antes de continuar.');
    return hash_hmac('sha256',$value,$key);
}

function auth_ip_hash(): string { return security_hmac((string)($_SERVER['REMOTE_ADDR'] ?? 'unknown')); }
function auth_login_hash(string $login): string { return security_hmac(mb_strtolower(trim($login),'UTF-8')); }
function auth_rate_limited(string $login): bool
{
    ensure_schema();$cutoff=date('Y-m-d H:i:s',time()-900);$lh=auth_login_hash($login);$ih=auth_ip_hash();
    $st=db()->prepare('SELECT COUNT(*) FROM auth_attempts WHERE success=0 AND created_at>=? AND ((login_hash=? AND ip_hash=?) OR ip_hash=?)');
    $st->execute([$cutoff,$lh,$ih,$ih]);$count=(int)$st->fetchColumn();
    return $count>=12;
}
function auth_record_attempt(string $login,bool $success): void
{
    try{db()->prepare('INSERT INTO auth_attempts(login_hash,ip_hash,success,created_at) VALUES(?,?,?,CURRENT_TIMESTAMP)')->execute([auth_login_hash($login),auth_ip_hash(),$success?1:0]);
        if(random_int(1,40)===1){$old=date('Y-m-d H:i:s',time()-2592000);db()->prepare('DELETE FROM auth_attempts WHERE created_at<?')->execute([$old]);}
    }catch(Throwable $e){}
}

function secure_password_hash(string $password): string
{
    if (defined('PASSWORD_ARGON2ID')) {
        $hash=password_hash($password,PASSWORD_ARGON2ID,['memory_cost'=>65536,'time_cost'=>4,'threads'=>2]);
        if (is_string($hash)) return $hash;
    }
    $hash=password_hash($password,PASSWORD_DEFAULT);
    if (!is_string($hash)) throw new RuntimeException('Não foi possível proteger a senha.');
    return $hash;
}

function app_setting(string $key, ?string $default = null): ?string
{
    ensure_schema();
    $stmt = db()->prepare('SELECT `value` FROM app_settings WHERE `key`=? LIMIT 1');
    $stmt->execute([$key]);
    $value = $stmt->fetchColumn();
    if ($value === false) return $default;
    $value=(string)$value;
    if (secret_setting_key($key) && $value !== '' && !str_starts_with($value,'enc:v1:')) {
        $encrypted = secret_encrypt($value);
        db()->prepare('UPDATE app_settings SET `value`=?,updated_at=CURRENT_TIMESTAMP WHERE `key`=?')->execute([$encrypted,$key]);
        $value = $encrypted;
    }
    return secret_setting_key($key) ? secret_decrypt($value) : $value;
}

function set_app_setting(string $key, ?string $value): void
{
    ensure_schema();
    if ($value !== null && secret_setting_key($key)) $value=secret_encrypt($value);
    $stmt = db()->prepare("INSERT INTO app_settings(`key`,`value`,updated_at) VALUES(?,?,CURRENT_TIMESTAMP)
        ON DUPLICATE KEY UPDATE `value`=VALUES(`value`),updated_at=CURRENT_TIMESTAMP");
    $stmt->execute([$key,$value]);
}

function google_api_key(): string
{
    global $config;
    $panelKey = trim((string)app_setting('google_api_key',''));
    return $panelKey !== '' ? $panelKey : trim((string)($config['google']['api_key'] ?? ''));
}


function drive_folder_id_from_link(string $link): ?string
{
    if (preg_match('~/folders/([A-Za-z0-9_-]+)~', $link, $m)) return $m[1];
    if (preg_match('~embeddedfolderview\\?[^#]*[?&]id=([A-Za-z0-9_-]+)~', $link, $m)) return $m[1];
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

function report_runtime_exception(string $scope, Throwable $error): string
{
    $scope = preg_replace('/[^a-z0-9._-]/i', '', mb_substr($scope, 0, 64)) ?: 'application';
    $incident = substr(hash('sha256', implode('|', [
        $scope,
        get_class($error),
        $error->getMessage(),
        basename($error->getFile()),
        (string)$error->getLine(),
    ])), 0, 12);
    error_log('[MusicRoad ' . $scope . '] incidente ' . $incident . ' · ' . get_class($error) . ' em ' . basename($error->getFile()) . ':' . $error->getLine());
    return $incident;
}

function write_runtime_file(string $path, string $data): bool
{
    try {
        $temporary = $path . '.tmp-' . bin2hex(random_bytes(4));
        if (@file_put_contents($temporary, $data, LOCK_EX) === false) return false;
        @chmod($temporary, 0664);
        if (!@rename($temporary, $path)) { @unlink($temporary); return false; }
        return true;
    } catch (Throwable $error) {
        return false;
    }
}

function request_expects_json(): bool
{
    $script = str_replace('\\','/',(string)($_SERVER['SCRIPT_NAME'] ?? ''));
    $accept = strtolower((string)($_SERVER['HTTP_ACCEPT'] ?? ''));
    $requestedWith = strtolower((string)($_SERVER['HTTP_X_REQUESTED_WITH'] ?? ''));
    return str_contains($script, '/api/') || str_contains($accept, 'application/json') || $requestedWith === 'xmlhttprequest';
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
        if (request_expects_json()) json_response(['ok'=>false,'error'=>'Sessão expirada. Atualize a página.'],419);
        http_response_code(419);
        exit('Sessão expirada. Atualize a página.');
    }
}

function require_session_rate_limit(string $bucket, int $limit, int $windowSeconds): void
{
    $bucket=preg_replace('/[^a-z0-9._-]/i','',mb_substr($bucket,0,64))?:'default';
    $limit=max(1,$limit);$windowSeconds=max(1,$windowSeconds);$now=time();
    $all=is_array($_SESSION['request_rate_limits']??null)?$_SESSION['request_rate_limits']:[];
    $hits=array_values(array_filter(is_array($all[$bucket]??null)?$all[$bucket]:[],static fn($hit): bool=>is_numeric($hit)&&(int)$hit>$now-$windowSeconds));
    if(count($hits)>=$limit)json_response(['ok'=>false,'error'=>'Muitas solicitações. Aguarde um momento e tente novamente.'],429);
    $hits[]=$now;$all[$bucket]=$hits;
    if(count($all)>24){foreach($all as $key=>$values)if(!is_array($values)||!array_filter($values,static fn($hit): bool=>is_numeric($hit)&&(int)$hit>$now-3600))unset($all[$key]);}
    $_SESSION['request_rate_limits']=$all;
}

function audit_log(string $action, array $payload = []): void
{
    ensure_schema();
    try {
        $stmt = db()->prepare('INSERT INTO audit_logs(user_id,action,payload,ip,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)');
        $stmt->execute([(int)($_SESSION['user_id'] ?? 0) ?: null,$action,json_encode($payload,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES),PHP_SAPI==='cli'?'cli':auth_ip_hash()]);
    } catch (Throwable $e) {}
}

function grant_license(int $userId, int $days, string $source='admin', ?int $planId=null, string $notes=''): int
{
    ensure_schema();
    $days = max(1,min(3650,$days));
    $latest = latest_license($userId);
    $baseTs = time();
    if ($latest && ($latest['status'] ?? '') === 'active' && strtotime((string)$latest['ends_at']) > $baseTs) {
        $baseTs = strtotime((string)$latest['ends_at']);
    }
    $starts = date('Y-m-d H:i:s');
    $ends = date('Y-m-d H:i:s', $baseTs + ($days * 86400));
    $stmt = db()->prepare("INSERT INTO licenses(user_id,plan_id,source,status,starts_at,ends_at,notes,created_at,updated_at) VALUES(?,?,?,'active',?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    $stmt->execute([$userId,$planId,$source,$starts,$ends,$notes]);
    return (int)db()->lastInsertId();
}

function refresh_expired_licenses(): void
{
    ensure_schema();
    db()->exec("UPDATE licenses SET status='expired',updated_at=CURRENT_TIMESTAMP WHERE status='active' AND ends_at < CURRENT_TIMESTAMP");
}

function payment_enabled(): bool { return app_setting('payment_enabled','0') === '1'; }

function mercadopago_request(string $method, string $path, ?array $body=null, array $extraHeaders=[]): array
{
    $token = trim((string)app_setting('mercadopago_access_token',''));
    if ($token === '') return ['ok'=>false,'status'=>0,'data'=>null,'error'=>'Access Token do Mercado Pago não configurado.'];
    $url = 'https://api.mercadopago.com' . $path;
    $ch = curl_init($url);
    $headers = array_merge(['Authorization: Bearer ' . $token,'Accept: application/json','Content-Type: application/json'], $extraHeaders);
    curl_setopt_array($ch,[CURLOPT_RETURNTRANSFER=>true,CURLOPT_CUSTOMREQUEST=>$method,CURLOPT_CONNECTTIMEOUT=>10,CURLOPT_TIMEOUT=>30,CURLOPT_HTTPHEADER=>$headers]);
    if ($body !== null) curl_setopt($ch,CURLOPT_POSTFIELDS,json_encode($body,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES));
    $raw = curl_exec($ch);
    $error = curl_error($ch);
    $status = (int)curl_getinfo($ch,CURLINFO_RESPONSE_CODE);
    curl_close($ch);
    $data = is_string($raw) ? json_decode($raw,true) : null;
    return ['ok'=>$status>=200&&$status<300,'status'=>$status,'data'=>is_array($data)?$data:null,'error'=>$error ?: null];
}

function sanitize_payment_payload(array $payment): array
{
    foreach(['payer','card','additional_info','shipments','collector'] as $key)unset($payment[$key]);
    $blocked=['identification'=>true,'phone'=>true,'address'=>true,'email'=>true,'first_name'=>true,'last_name'=>true,'document'=>true];
    $clean=function(array $value)use(&$clean,$blocked):array{$out=[];foreach($value as $key=>$item){if(isset($blocked[strtolower((string)$key)]))continue;$out[$key]=is_array($item)?$clean($item):$item;}return $out;};
    $payment=$clean($payment);
    return $payment;
}

function apply_mercadopago_payment(array $payment): array
{
    $status=(string)($payment['status']??'unknown');
    $external=(string)($payment['external_reference']??'');
    $paymentId=(string)($payment['id']??'');
    if($external==='') return ['ok'=>false,'reason'=>'unmatched'];
    $pdo=db();
    try{
        $pdo->beginTransaction();
        $stmt=$pdo->prepare("SELECT o.*,p.duration_days FROM payment_orders o JOIN plans p ON p.id=o.plan_id WHERE o.provider='mercadopago' AND o.external_reference=? LIMIT 1 FOR UPDATE");
        $stmt->execute([$external]);$order=$stmt->fetch();
        if(!$order){$pdo->commit();return ['ok'=>false,'reason'=>'unmatched'];}
        $safe=json_encode(sanitize_payment_payload($payment),JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);
        $paidCents=(int)round(((float)($payment['transaction_amount']??0))*100);
        $currency=strtoupper((string)($payment['currency_id']??''));
        if(!empty($order['paid_at'])){
            $pdo->prepare("UPDATE payment_orders SET raw_payload=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")->execute([$safe,(int)$order['id']]);
            $pdo->commit();
            return ['ok'=>true,'status'=>'approved','order_id'=>(int)$order['id'],'user_id'=>(int)$order['user_id'],'already_applied'=>true];
        }
        if($status==='approved' && ($paidCents!==(int)$order['amount_cents'] || $currency!=='BRL')){
            $pdo->prepare("UPDATE payment_orders SET provider_payment_id=?,status='review_amount_mismatch',raw_payload=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")
              ->execute([$paymentId,$safe,(int)$order['id']]);
            $pdo->commit();
            audit_log('payment.amount_mismatch',['order_id'=>(int)$order['id'],'expected_cents'=>(int)$order['amount_cents'],'received_cents'=>$paidCents,'currency'=>$currency]);
            return ['ok'=>true,'status'=>'review_amount_mismatch','order_id'=>(int)$order['id']];
        }
        if($status==='approved'){
            if($paymentId==='')throw new RuntimeException('Pagamento aprovado sem identificador do provedor.');
            $pdo->prepare("UPDATE payment_orders SET provider_payment_id=?,status='approved',raw_payload=?,paid_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?")
                ->execute([$paymentId,$safe,(int)$order['id']]);
            grant_license((int)$order['user_id'],(int)$order['duration_days'],'mercadopago',(int)$order['plan_id'],'Pagamento PIX Mercado Pago '.$paymentId);
        }else{
            $pdo->prepare('UPDATE payment_orders SET provider_payment_id=?,status=?,raw_payload=?,updated_at=CURRENT_TIMESTAMP WHERE id=?')
              ->execute([$paymentId,$status,$safe,(int)$order['id']]);
        }
        $pdo->commit();
    }catch(Throwable $e){if($pdo->inTransaction())$pdo->rollBack();throw $e;}
    return ['ok'=>true,'status'=>$status,'order_id'=>(int)$order['id'],'user_id'=>(int)$order['user_id']];
}

function valid_cpf(string $cpf): bool
{
    $cpf=preg_replace('/\D+/','',$cpf)??'';
    if(strlen($cpf)!==11 || preg_match('/^(\d)\1{10}$/',$cpf))return false;
    for($t=9;$t<11;$t++){
        $sum=0;for($i=0;$i<$t;$i++)$sum+=(int)$cpf[$i]*(($t+1)-$i);
        $d=(10*$sum)%11;if($d===10)$d=0;if($d!==(int)$cpf[$t])return false;
    }
    return true;
}

function trial_registration_enabled(): bool { return app_setting('trial_registration_enabled','1')==='1'; }
function trial_hours(): int { return 24; }
function trial_network_limit(): int { return max(1,min(20,(int)app_setting('trial_network_limit','2'))); }
function trial_network_days(): int { return max(1,min(90,(int)app_setting('trial_network_days','7'))); }

function trial_secret(): string
{
    $secret=(string)app_setting('trial_server_secret','');
    if(strlen($secret)<32){$secret=bin2hex(random_bytes(32));set_app_setting('trial_server_secret',$secret);}
    return $secret;
}
function trial_device_hash(string $token): string { return hash_hmac('sha256',$token,trial_secret()); }
function trial_ua_hash(): string { return hash_hmac('sha256',(string)($_SERVER['HTTP_USER_AGENT']??''),trial_secret()); }
function trial_network_key(): string
{
    $ip=(string)($_SERVER['REMOTE_ADDR']??'0.0.0.0');
    if(filter_var($ip,FILTER_VALIDATE_IP,FILTER_FLAG_IPV4)){
        $parts=explode('.',$ip);$network=implode('.',array_slice($parts,0,3)).'.0/24';
    }elseif(filter_var($ip,FILTER_VALIDATE_IP,FILTER_FLAG_IPV6)){
        $bin=@inet_pton($ip);$network=$bin!==false?bin2hex(substr($bin,0,8)).'/64':'ipv6/unknown';
    }else{$network='unknown';}
    return hash_hmac('sha256',$network,trial_secret());
}
function trial_check_eligibility(string $deviceToken): array
{
    ensure_schema();
    if(!trial_registration_enabled())return ['ok'=>false,'reason'=>'Cadastro para teste está temporariamente desativado.'];
    $deviceToken=trim($deviceToken);
    if(strlen($deviceToken)<20 || strlen($deviceToken)>220)return ['ok'=>false,'reason'=>'Não foi possível validar este dispositivo. Feche e abra o app novamente.'];
    if(!str_starts_with($deviceToken,'android:') && !str_starts_with($deviceToken,'web:'))return ['ok'=>false,'reason'=>'Identificação de instalação inválida.'];
    $deviceHash=trial_device_hash($deviceToken);$networkHash=trial_network_key();
    $st=db()->prepare('SELECT user_id,claimed_at FROM trial_claims WHERE device_hash=? LIMIT 1');$st->execute([$deviceHash]);
    if($st->fetch())return ['ok'=>false,'reason'=>'Este dispositivo/instalação já utilizou o teste gratuito. Entre com a conta existente ou assine um plano.'];
    $days=trial_network_days();$limit=trial_network_limit();$cutoff=date('Y-m-d H:i:s',time()-($days*86400));
    $st=db()->prepare("SELECT COUNT(*) FROM trial_claims WHERE network_hash=? AND claimed_at>=?");
    $st->execute([$networkHash,$cutoff]);$count=(int)$st->fetchColumn();
    if($count>=$limit)return ['ok'=>false,'reason'=>'O limite de testes gratuitos desta rede foi atingido. Entre com uma conta existente ou assine um plano.'];
    return ['ok'=>true,'device_hash'=>$deviceHash,'network_hash'=>$networkHash,'ua_hash'=>trial_ua_hash(),'source'=>str_starts_with($deviceToken,'android:')?'android':'web'];
}
function record_trial_claim(int $userId,array $eligibility): int
{
    if(empty($eligibility['device_hash'])||empty($eligibility['network_hash']))throw new RuntimeException('Validação do teste expirou. Tente novamente.');
    $pdo=db();$ownsTransaction=!$pdo->inTransaction();if($ownsTransaction)$pdo->beginTransaction();
    try{
        $device=$pdo->prepare('SELECT id FROM trial_claims WHERE device_hash=? LIMIT 1 FOR UPDATE');$device->execute([(string)$eligibility['device_hash']]);
        if($device->fetchColumn())throw new RuntimeException('Este dispositivo/instalação já utilizou o teste gratuito.');
        $cutoff=date('Y-m-d H:i:s',time()-trial_network_days()*86400);
        $network=$pdo->prepare('SELECT id FROM trial_claims WHERE network_hash=? AND claimed_at>=? FOR UPDATE');$network->execute([(string)$eligibility['network_hash'],$cutoff]);
        if(count($network->fetchAll(PDO::FETCH_COLUMN))>=trial_network_limit())throw new RuntimeException('O limite de testes gratuitos desta rede foi atingido.');
        $hours=trial_hours();$expires=date('Y-m-d H:i:s',time()+$hours*3600);
        $st=$pdo->prepare('INSERT INTO trial_claims(user_id,device_hash,network_hash,user_agent_hash,source,claimed_at,expires_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP,?)');
        $st->execute([$userId,$eligibility['device_hash'],$eligibility['network_hash'],$eligibility['ua_hash']??null,$eligibility['source']??'web',$expires]);
        if($ownsTransaction)$pdo->commit();return $hours;
    }catch(Throwable $e){if($ownsTransaction&&$pdo->inTransaction())$pdo->rollBack();throw $e;}
}

function registered_device_hash(string $token): string
{
    return security_hmac('registered-device|'.trim($token));
}
function registered_device_valid_token(string $token): bool
{
    $token=trim($token);
    return str_starts_with($token,'android:') && strlen($token)>=40 && strlen($token)<=220;
}
function registered_device_bind(int $userId,string $token,string $label='',string $appVersion=''): array
{
    ensure_schema();
    if(!registered_device_valid_token($token))return ['ok'=>false,'bound'=>false,'reason'=>'not_android'];
    $hash=registered_device_hash($token);$pdo=db();
    $st=$pdo->prepare('SELECT id,user_id,revoked_at FROM registered_devices WHERE device_hash=? LIMIT 1');$st->execute([$hash]);$row=$st->fetch();
    if($row){
        $owner=(int)$row['user_id'];
        if($owner!==$userId && empty($row['revoked_at']))throw new RuntimeException('Este aparelho já está vinculado a outra conta. Remova o dispositivo nessa conta antes de trocar.');
        $pdo->prepare("UPDATE registered_devices SET user_id=?,device_label=?,platform='android',app_version=?,auto_login_enabled=1,last_ip_hash=?,last_seen_at=CURRENT_TIMESTAMP,revoked_at=NULL WHERE id=?")
            ->execute([$userId,mb_substr(trim($label),0,190),mb_substr(trim($appVersion),0,40),auth_ip_hash(),(int)$row['id']]);
        audit_log('device.bound',['user_id'=>$userId,'device_id'=>(int)$row['id'],'reactivated'=>true]);
        return ['ok'=>true,'bound'=>true,'device_id'=>(int)$row['id']];
    }
    $pdo->prepare("INSERT INTO registered_devices(user_id,device_hash,device_label,platform,app_version,auto_login_enabled,last_ip_hash,created_at,last_seen_at) VALUES(?,?,?,'android',?,1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
        ->execute([$userId,$hash,mb_substr(trim($label),0,190),mb_substr(trim($appVersion),0,40),auth_ip_hash()]);
    $id=(int)$pdo->lastInsertId();audit_log('device.bound',['user_id'=>$userId,'device_id'=>$id,'reactivated'=>false]);
    return ['ok'=>true,'bound'=>true,'device_id'=>$id];
}
function registered_device_lookup(string $token): ?array
{
    ensure_schema();if(!registered_device_valid_token($token))return null;
    $st=db()->prepare('SELECT d.*,u.name,u.email,u.username,u.role,u.status FROM registered_devices d JOIN users u ON u.id=d.user_id WHERE d.device_hash=? AND d.auto_login_enabled=1 AND d.revoked_at IS NULL LIMIT 1');
    $st->execute([registered_device_hash($token)]);$row=$st->fetch();return $row?:null;
}
function registered_device_login(string $token,string $appVersion=''): array
{
    $row=registered_device_lookup($token);
    if(!$row)return ['ok'=>false,'registered'=>false,'reason'=>'not_registered'];
    if(($row['status']??'')!=='active')return ['ok'=>false,'registered'=>true,'reason'=>'account_inactive'];
    db()->prepare('UPDATE registered_devices SET app_version=?,last_ip_hash=?,last_seen_at=CURRENT_TIMESTAMP WHERE id=?')
        ->execute([mb_substr(trim($appVersion),0,40),auth_ip_hash(),(int)$row['id']]);
    session_regenerate_id(true);$_SESSION['user_id']=(int)$row['user_id'];
    db()->prepare('UPDATE users SET last_login_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?')->execute([(int)$row['user_id']]);
    $u=current_user();$access=$u?user_has_access($u):false;
    audit_log('device.auto_login',['user_id'=>(int)$row['user_id'],'device_id'=>(int)$row['id']]);
    return ['ok'=>true,'registered'=>true,'user'=>['id'=>(int)$row['user_id'],'name'=>$row['name'],'username'=>$row['username'],'role'=>$row['role']],'access'=>$access,'redirect'=>($row['role']==='admin'?'admin.php':($access?'index.php':'license.php'))];
}
function registered_device_remove(int $userId,string $token): bool
{
    if(!registered_device_valid_token($token))return false;
    $st=db()->prepare('UPDATE registered_devices SET auto_login_enabled=0,revoked_at=CURRENT_TIMESTAMP,last_seen_at=CURRENT_TIMESTAMP WHERE user_id=? AND device_hash=? AND revoked_at IS NULL');
    $st->execute([$userId,registered_device_hash($token)]);$ok=$st->rowCount()>0;
    if($ok)audit_log('device.removed',['user_id'=>$userId]);return $ok;
}
function registered_device_status(int $userId,string $token): array
{
    if(!registered_device_valid_token($token))return ['registered'=>false];
    $st=db()->prepare('SELECT id,device_label,app_version,auto_login_enabled,created_at,last_seen_at,revoked_at FROM registered_devices WHERE user_id=? AND device_hash=? LIMIT 1');
    $st->execute([$userId,registered_device_hash($token)]);$row=$st->fetch();
    return ['registered'=>(bool)$row,'device'=>$row?:null];
}

function haversine_m(float $lat1, float $lon1, float $lat2, float $lon2): float
{
    $r=6371000; $dLat=deg2rad($lat2-$lat1); $dLon=deg2rad($lon2-$lon1);
    $a=sin($dLat/2)**2 + cos(deg2rad($lat1))*cos(deg2rad($lat2))*sin($dLon/2)**2;
    return $r*2*atan2(sqrt($a),sqrt(1-$a));
}

function http_json(string $url, ?string $postBody = null, array $headers = [], int $timeoutSeconds = 25, int $maxBytes = 33554432): ?array
{
    global $config;
    $maxBytes=max(65536,min(67108864,$maxBytes));
    $defaultHeaders=['User-Agent: '.($config['routing']['user_agent'] ?? 'MusicRoadAI/1.2.0'),'Accept: application/json'];
    if (!function_exists('curl_init')) {
        $context=stream_context_create(['http'=>['method'=>$postBody===null?'GET':'POST','header'=>implode("\r\n",array_merge($defaultHeaders,$headers)),'content'=>$postBody??'','timeout'=>max(3,$timeoutSeconds)]]);
        $raw=@file_get_contents($url,false,$context,0,$maxBytes+1);if(!is_string($raw)||strlen($raw)>$maxBytes)return null;$data=json_decode($raw,true);return is_array($data)?$data:null;
    }
    $ch=curl_init($url);$raw='';$tooLarge=false;
    curl_setopt_array($ch,[CURLOPT_RETURNTRANSFER=>false,CURLOPT_FOLLOWLOCATION=>true,CURLOPT_CONNECTTIMEOUT=>min(10,max(3,$timeoutSeconds)),CURLOPT_TIMEOUT=>max(3,$timeoutSeconds),CURLOPT_HTTPHEADER=>array_merge($defaultHeaders,$headers),CURLOPT_ENCODING=>'',CURLOPT_WRITEFUNCTION=>static function($handle,string $chunk)use(&$raw,&$tooLarge,$maxBytes):int{$length=strlen($chunk);if(strlen($raw)+$length>$maxBytes){$tooLarge=true;return 0;}$raw.=$chunk;return $length;}]);
    if($postBody!==null){curl_setopt($ch,CURLOPT_POST,true);curl_setopt($ch,CURLOPT_POSTFIELDS,$postBody);}$ok=curl_exec($ch);$status=(int)curl_getinfo($ch,CURLINFO_RESPONSE_CODE);curl_close($ch);
    if($ok===false||$tooLarge||$raw===''||$status>=400)return null;$data=json_decode($raw,true);return is_array($data)?$data:null;
}

function geocode_place(string $place): ?array
{
    global $config;
    if (str_contains($place, ',')) {
        $parts = array_map('trim', explode(',', $place));
        if (count($parts) === 2 && is_numeric($parts[0]) && is_numeric($parts[1])) {
            return ['lat' => (float)$parts[0], 'lon' => (float)$parts[1], 'label' => $place];
        }
    }
    $query = http_build_query(['q' => $place . ', Brasil', 'format' => 'jsonv2', 'limit' => 1, 'addressdetails' => 1]);
    $data = http_json(rtrim($config['routing']['nominatim_url'], '?') . '?' . $query);
    if (!$data || empty($data[0]['lat']) || empty($data[0]['lon'])) {
        return null;
    }
    return ['lat' => (float)$data[0]['lat'], 'lon' => (float)$data[0]['lon'], 'label' => $data[0]['display_name'] ?? $place];
}

// Garante que qualquer endpoint funcione em uma instalação limpa.
ensure_schema();
