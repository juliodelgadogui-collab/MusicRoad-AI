<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';

const NATIVE_ACCESS_TTL = 1800;
const NATIVE_REFRESH_TTL = 2592000;

function native_auth_is_mysql(): bool
{
    return (string)db()->getAttribute(PDO::ATTR_DRIVER_NAME) === 'mysql';
}

function native_auth_ensure_schema(): void
{
    static $done = false;
    if ($done) return;
    $done = true;
    ensure_runtime_tables();
    try {
        if (native_auth_is_mysql()) {
            db()->exec("CREATE TABLE IF NOT EXISTS native_device_credentials (
                device_id VARCHAR(160) NOT NULL PRIMARY KEY,
                user_id BIGINT NOT NULL,
                secret_hash CHAR(64) NOT NULL,
                access_hash CHAR(64) NULL,
                access_expires_at DATETIME NULL,
                refresh_hash CHAR(64) NULL,
                refresh_expires_at DATETIME NULL,
                revoked_at DATETIME NULL,
                created_at DATETIME NOT NULL,
                updated_at DATETIME NOT NULL,
                last_used_at DATETIME NULL,
                KEY idx_native_cred_user (user_id),
                KEY idx_native_cred_access (access_hash),
                KEY idx_native_cred_refresh (refresh_hash),
                KEY idx_native_cred_exp (access_expires_at,refresh_expires_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        } else {
            db()->exec("CREATE TABLE IF NOT EXISTS native_device_credentials (
                device_id TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL,
                secret_hash TEXT NOT NULL,
                access_hash TEXT,
                access_expires_at TEXT,
                refresh_hash TEXT,
                refresh_expires_at TEXT,
                revoked_at TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                last_used_at TEXT
            )");
            db()->exec('CREATE INDEX IF NOT EXISTS idx_native_cred_user ON native_device_credentials(user_id)');
            db()->exec('CREATE INDEX IF NOT EXISTS idx_native_cred_access ON native_device_credentials(access_hash)');
            db()->exec('CREATE INDEX IF NOT EXISTS idx_native_cred_refresh ON native_device_credentials(refresh_hash)');
        }
    } catch (Throwable $e) {
        error_log('Native auth schema: ' . $e->getMessage());
    }
}

function native_device_token_from_request(?array $data = null): string
{
    $token = trim((string)($_SERVER['HTTP_X_ESTRADAPLAY_DEVICE'] ?? ''));
    if ($token === '' && is_array($data)) $token = trim((string)($data['device_token'] ?? ''));
    return preg_match('/^[a-f0-9]{32,128}$/i', $token) ? strtolower($token) : '';
}

function native_device_secret_raw(?array $data = null): string
{
    $secret = trim((string)($_SERVER['HTTP_X_ESTRADAPLAY_DEVICE_SECRET'] ?? ''));
    if ($secret === '' && is_array($data)) $secret = trim((string)($data['device_secret'] ?? ''));
    return $secret;
}

function native_device_secret_from_request(?array $data = null): string
{
    $secret = native_device_secret_raw($data);
    return preg_match('/^[A-Za-z0-9_-]{40,192}$/', $secret) ? $secret : '';
}

function native_device_label_from_request(?array $data = null): string
{
    $label = is_array($data) ? trim((string)($data['device_label'] ?? '')) : '';
    if ($label === '') $label = trim((string)($_SERVER['HTTP_X_ESTRADAPLAY_DEVICE_LABEL'] ?? ''));
    if ($label === '') $label = 'EstradaPlay Android';
    return function_exists('mb_substr') ? mb_substr($label, 0, 160) : substr($label, 0, 160);
}

function native_bearer_token(): string
{
    $raw = trim((string)($_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? ''));
    if ($raw === '' && function_exists('getallheaders')) {
        $headers = getallheaders();
        if (is_array($headers)) {
            foreach ($headers as $k => $v) if (strcasecmp((string)$k, 'Authorization') === 0) { $raw = trim((string)$v); break; }
        }
    }
    if (!preg_match('/^Bearer\s+([A-Za-z0-9_-]{32,256})$/i', $raw, $m)) return '';
    return $m[1];
}

function native_auth_salt(): string
{
    $salt = trim((string)app_setting('native_auth_salt_v207', ''));
    if ($salt !== '') return $salt;
    try { $salt = bin2hex(random_bytes(32)); }
    catch (Throwable $e) { $salt = hash('sha256', APP_ROOT . '|' . microtime(true) . '|' . mt_rand()); }
    set_app_setting('native_auth_salt_v207', $salt);
    return $salt;
}

function native_auth_hash(string $kind, string $value): string
{
    return hash('sha256', native_auth_salt() . '|' . $kind . '|' . $value);
}

function native_random_token(int $bytes): string
{
    return rtrim(strtr(base64_encode(random_bytes($bytes)), '+/', '-_'), '=');
}

function native_account_payload(array $user): array
{
    return [
        'authenticated' => true,
        'user' => [
            'id' => (int)($user['id'] ?? 0),
            'name' => (string)($user['name'] ?? ''),
            'email' => (string)($user['email'] ?? ''),
            'username' => (string)($user['username'] ?? ''),
            'role' => (string)($user['role'] ?? 'client'),
        ],
    ];
}

function native_start_user_session(array $user): void
{
    $id = (int)($user['id'] ?? 0);
    if ($id <= 0) return;
    if ((int)($_SESSION['user_id'] ?? 0) !== $id) {
        @session_regenerate_id(true);
        $_SESSION['user_id'] = $id;
    }
}

function native_bind_device(int $userId, string $token, string $label = ''): void
{
    if ($userId <= 0 || $token === '') return;
    ensure_runtime_tables();
    $now = date('Y-m-d H:i:s');
    $check = db()->prepare('SELECT id FROM client_device_state WHERE user_id = ? AND device_id = ? LIMIT 1');
    $check->execute([$userId, $token]);
    $id = (int)($check->fetchColumn() ?: 0);
    if ($id > 0) {
        $u = db()->prepare('UPDATE client_device_state SET device_name = ?, last_seen_at = ? WHERE id = ?');
        $u->execute([$label, $now, $id]);
    } else {
        $i = db()->prepare('INSERT INTO client_device_state (user_id,device_id,device_name,last_seen_at) VALUES (?,?,?,?)');
        $i->execute([$userId, $token, $label, $now]);
    }
}

function native_user_for_device(string $token): ?array
{
    // Legacy helper remains for diagnostics only. Authentication must never call it without a secret.
    if ($token === '') return null;
    ensure_runtime_tables();
    $s = db()->prepare("SELECT u.id,u.name,u.email,u.username,u.role,u.status,u.last_login_at,u.created_at
        FROM client_device_state d
        JOIN users u ON u.id = d.user_id
        WHERE d.device_id = ? AND (u.status IS NULL OR u.status = '' OR u.status = 'active')
        ORDER BY d.last_seen_at DESC LIMIT 1");
    $s->execute([$token]);
    $user = $s->fetch();
    return is_array($user) ? $user : null;
}

function native_user_by_id(int $id): ?array
{
    if ($id <= 0) return null;
    $s = db()->prepare("SELECT id,name,email,username,role,status,last_login_at,created_at FROM users
        WHERE id = ? AND (status IS NULL OR status = '' OR status = 'active') LIMIT 1");
    $s->execute([$id]);
    $user = $s->fetch();
    return is_array($user) ? $user : null;
}

function native_issue_device_auth(int $userId, string $deviceId, string $secret): array
{
    native_auth_ensure_schema();
    if ($userId <= 0 || $deviceId === '' || $secret === '') return [];
    $access = native_random_token(32);
    $refresh = native_random_token(48);
    $nowTs = time();
    $now = date('Y-m-d H:i:s', $nowTs);
    $accessExpTs = $nowTs + NATIVE_ACCESS_TTL;
    $refreshExpTs = $nowTs + NATIVE_REFRESH_TTL;
    $accessExp = date('Y-m-d H:i:s', $accessExpTs);
    $refreshExp = date('Y-m-d H:i:s', $refreshExpTs);
    $secretHash = native_auth_hash('secret', $secret);
    $accessHash = native_auth_hash('access', $access);
    $refreshHash = native_auth_hash('refresh', $refresh);

    $q = db()->prepare('SELECT device_id FROM native_device_credentials WHERE device_id = ? LIMIT 1');
    $q->execute([$deviceId]);
    if ($q->fetchColumn()) {
        $u = db()->prepare('UPDATE native_device_credentials SET user_id=?,secret_hash=?,access_hash=?,access_expires_at=?,refresh_hash=?,refresh_expires_at=?,revoked_at=NULL,updated_at=?,last_used_at=? WHERE device_id=?');
        $u->execute([$userId,$secretHash,$accessHash,$accessExp,$refreshHash,$refreshExp,$now,$now,$deviceId]);
    } else {
        $i = db()->prepare('INSERT INTO native_device_credentials (device_id,user_id,secret_hash,access_hash,access_expires_at,refresh_hash,refresh_expires_at,revoked_at,created_at,updated_at,last_used_at) VALUES (?,?,?,?,?,?,?,NULL,?,?,?)');
        $i->execute([$deviceId,$userId,$secretHash,$accessHash,$accessExp,$refreshHash,$refreshExp,$now,$now,$now]);
    }
    return [
        'token_type' => 'Bearer',
        'access_token' => $access,
        'refresh_token' => $refresh,
        'access_expires_at' => $accessExpTs,
        'refresh_expires_at' => $refreshExpTs,
    ];
}

function native_credential_row(string $deviceId): ?array
{
    if ($deviceId === '') return null;
    native_auth_ensure_schema();
    try {
        $s = db()->prepare('SELECT * FROM native_device_credentials WHERE device_id = ? LIMIT 1');
        $s->execute([$deviceId]);
        $row = $s->fetch();
        return is_array($row) ? $row : null;
    } catch (Throwable $e) { return null; }
}

function native_user_for_secure_device(string $deviceId, string $secret): ?array
{
    if ($deviceId === '' || $secret === '') return null;
    $row = native_credential_row($deviceId);
    if (!$row || !empty($row['revoked_at'])) return null;
    $expected = (string)($row['secret_hash'] ?? '');
    if ($expected === '' || !hash_equals($expected, native_auth_hash('secret', $secret))) return null;
    $user = native_user_by_id((int)($row['user_id'] ?? 0));
    if (!$user) return null;
    try {
        $u = db()->prepare('UPDATE native_device_credentials SET last_used_at=?,updated_at=? WHERE device_id=?');
        $now = date('Y-m-d H:i:s'); $u->execute([$now,$now,$deviceId]);
    } catch (Throwable $ignored) {}
    return $user;
}

function native_user_for_access_token(string $token): ?array
{
    if ($token === '') return null;
    native_auth_ensure_schema();
    $hash = native_auth_hash('access', $token);
    try {
        $s = db()->prepare("SELECT c.device_id,c.user_id,c.revoked_at,c.access_expires_at,u.id,u.name,u.email,u.username,u.role,u.status,u.last_login_at,u.created_at
            FROM native_device_credentials c JOIN users u ON u.id=c.user_id
            WHERE c.access_hash=? AND c.revoked_at IS NULL AND c.access_expires_at>? AND (u.status IS NULL OR u.status='' OR u.status='active') LIMIT 1");
        $s->execute([$hash,date('Y-m-d H:i:s')]);
        $row = $s->fetch();
        if (!is_array($row)) return null;
        $u = db()->prepare('UPDATE native_device_credentials SET last_used_at=? WHERE device_id=?');
        $u->execute([date('Y-m-d H:i:s'),(string)$row['device_id']]);
        return $row;
    } catch (Throwable $e) { return null; }
}

function native_refresh_device_auth(string $deviceId, string $secret, string $refreshToken): ?array
{
    if ($deviceId === '' || $secret === '' || !preg_match('/^[A-Za-z0-9_-]{32,256}$/', $refreshToken)) return null;
    $row = native_credential_row($deviceId);
    if (!$row || !empty($row['revoked_at'])) return null;
    if (!hash_equals((string)($row['secret_hash'] ?? ''), native_auth_hash('secret', $secret))) return null;
    if (!hash_equals((string)($row['refresh_hash'] ?? ''), native_auth_hash('refresh', $refreshToken))) return null;
    $exp = strtotime((string)($row['refresh_expires_at'] ?? ''));
    if ($exp === false || $exp <= time()) return null;
    $user = native_user_by_id((int)($row['user_id'] ?? 0));
    if (!$user) return null;
    return ['user'=>$user,'auth'=>native_issue_device_auth((int)$user['id'],$deviceId,$secret)];
}

function native_revoke_device(string $deviceId): bool
{
    if ($deviceId === '') return false;
    native_auth_ensure_schema();
    try {
        $now = date('Y-m-d H:i:s');
        $s = db()->prepare('UPDATE native_device_credentials SET revoked_at=?,access_hash=NULL,access_expires_at=NULL,refresh_hash=NULL,refresh_expires_at=NULL,updated_at=? WHERE device_id=?');
        $s->execute([$now,$now,$deviceId]);
        return $s->rowCount() > 0;
    } catch (Throwable $e) { return false; }
}

function native_device_is_revoked(string $deviceId): bool
{
    $row = native_credential_row($deviceId);
    return is_array($row) && !empty($row['revoked_at']);
}

function native_can_bootstrap_secure_device(array $user, string $deviceId): bool
{
    $uid = (int)($user['id'] ?? 0);
    if ($uid <= 0 || $deviceId === '') return false;
    try {
        $s = db()->prepare('SELECT id FROM client_device_state WHERE user_id=? AND device_id=? LIMIT 1');
        $s->execute([$uid,$deviceId]);
        return (bool)$s->fetchColumn();
    } catch (Throwable $e) { return false; }
}

function native_restore_user_from_request(?array $data = null): ?array
{
    $deviceId = native_device_token_from_request($data);
    $bearer = native_bearer_token();
    if ($bearer !== '') {
        $user = native_user_for_access_token($bearer);
        if (!$user) return null;
        native_start_user_session($user);
        if ($deviceId !== '') native_bind_device((int)$user['id'],$deviceId,native_device_label_from_request($data));
        return $user;
    }

    $secretRaw = native_device_secret_raw($data);
    if ($secretRaw !== '') {
        $secret = native_device_secret_from_request($data);
        if ($deviceId === '' || $secret === '') return null;
        $row = native_credential_row($deviceId);
        if ($row) {
            $user = native_user_for_secure_device($deviceId,$secret);
            if (!$user) return null;
            native_start_user_session($user);
            native_bind_device((int)$user['id'],$deviceId,native_device_label_from_request($data));
            return $user;
        }
        // Migration bridge: a valid legacy PHP session may continue briefly until the app calls
        // device_login, where the random secret is permanently bound and bearer tokens are issued.
        $current = current_user();
        if ($current && native_can_bootstrap_secure_device($current,$deviceId)) return $current;
        return null;
    }

    // Legacy clients are accepted by an existing PHP session only. Device ID alone is never login.
    $current = current_user();
    if ($current) {
        if ($deviceId !== '' && native_device_is_revoked($deviceId)) return null;
        return $current;
    }
    return null;
}

function native_require_json_user(?array $data = null): array
{
    $user = native_restore_user_from_request($data);
    if (!$user) json_response(['ok'=>false,'error'=>'Sessão expirada. Entre novamente neste aparelho.'], 401);
    return $user;
}
