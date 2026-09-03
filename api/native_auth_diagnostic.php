<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';

header('Content-Type: application/json; charset=utf-8');

// SERVER_AUTH_DIAG_V235: diagnostic endpoint for persistent native-device auth.
// Never returns passwords, bearer/refresh tokens, raw device secrets or credential hashes.
ensure_default_users();
native_auth_ensure_schema();
$data = input_json();

$deviceId = native_device_token_from_request($data);
$secret = native_device_secret_from_request($data);
$driver = (string)db()->getAttribute(PDO::ATTR_DRIVER_NAME);
$storedSalt = trim((string)app_setting('native_auth_salt_v207', ''));
$effectiveSalt = native_auth_salt();
$instanceKey = trim((string)app_setting('native_auth_diag_instance_v235', ''));
if ($instanceKey === '') {
    try { $candidate = bin2hex(random_bytes(24)); }
    catch (Throwable $e) { $candidate = hash('sha256', APP_ROOT . '|' . microtime(true)); }
    set_app_setting('native_auth_diag_instance_v235', $candidate);
    $instanceKey = trim((string)app_setting('native_auth_diag_instance_v235', $candidate));
}

$row = $deviceId !== '' ? native_credential_row($deviceId) : null;
$credentialExists = is_array($row);
$revoked = $credentialExists && !empty($row['revoked_at']);
$secretMatches = false;
if ($credentialExists && !$revoked && $secret !== '') {
    $expected = (string)($row['secret_hash'] ?? '');
    $secretMatches = $expected !== '' && hash_equals($expected, native_auth_hash('secret', $secret));
}

$accessExp = $credentialExists ? strtotime((string)($row['access_expires_at'] ?? '')) : false;
$refreshExp = $credentialExists ? strtotime((string)($row['refresh_expires_at'] ?? '')) : false;
$now = time();
$reason = 'ok';
if ($deviceId === '') $reason = 'device_id_missing_or_invalid';
elseif (!$credentialExists) $reason = 'credential_missing';
elseif ($revoked) $reason = 'credential_revoked';
elseif ($secret === '') $reason = 'device_secret_missing_or_invalid';
elseif (!$secretMatches) $reason = 'device_secret_mismatch';

try { $credentialCount = (int)db()->query('SELECT COUNT(*) FROM native_device_credentials')->fetchColumn(); }
catch (Throwable $e) { $credentialCount = -1; }

try {
    global $config;
    if ($driver === 'mysql') {
        $target = (string)($config['db']['mysql_dsn'] ?? 'mysql');
    } else {
        $target = (string)($config['db']['sqlite_path'] ?? 'sqlite');
    }
    $dbFingerprint = substr(hash('sha256', $driver . '|' . $target), 0, 16);
} catch (Throwable $e) {
    $dbFingerprint = 'unavailable';
}

json_response([
    'ok' => true,
    'diagnostic_version' => '2.3.5-server-auth-diag-1',
    'server_time' => date(DATE_ATOM),
    'database' => [
        'driver' => $driver,
        'target_fingerprint' => $dbFingerprint,
        'credential_rows' => $credentialCount,
    ],
    'auth_salt' => [
        'stored' => $storedSalt !== '',
        'stored_fingerprint' => $storedSalt !== '' ? substr(hash('sha256', $storedSalt), 0, 16) : '',
        'effective_fingerprint' => substr(hash('sha256', $effectiveSalt), 0, 16),
    ],
    'server_instance_fingerprint' => substr(hash('sha256', $instanceKey), 0, 16),
    'device' => [
        'id_valid' => $deviceId !== '',
        'id_fingerprint' => $deviceId !== '' ? substr(hash('sha256', $deviceId), 0, 16) : '',
        'credential_exists' => $credentialExists,
        'revoked' => $revoked,
        'secret_supplied' => $secret !== '',
        'secret_matches' => $secretMatches,
        'access_valid' => $credentialExists && is_int($accessExp) && $accessExp > $now,
        'refresh_valid' => $credentialExists && is_int($refreshExp) && $refreshExp > $now,
        'updated_at' => $credentialExists ? (string)($row['updated_at'] ?? '') : '',
        'last_used_at' => $credentialExists ? (string)($row['last_used_at'] ?? '') : '',
    ],
    'result' => $reason,
]);
