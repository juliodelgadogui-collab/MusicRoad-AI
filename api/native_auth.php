<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';

function native_device_token_from_request(?array $data = null): string
{
    $token = trim((string)($_SERVER['HTTP_X_ESTRADAPLAY_DEVICE'] ?? ''));
    if ($token === '' && is_array($data)) {
        $token = trim((string)($data['device_token'] ?? ''));
    }
    return preg_match('/^[a-f0-9]{32,128}$/i', $token) ? strtolower($token) : '';
}

function native_device_label_from_request(?array $data = null): string
{
    $label = is_array($data) ? trim((string)($data['device_label'] ?? '')) : '';
    if ($label === '') $label = trim((string)($_SERVER['HTTP_X_ESTRADAPLAY_DEVICE_LABEL'] ?? ''));
    if ($label === '') $label = 'EstradaPlay Android';
    return mb_substr($label, 0, 160);
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

function native_restore_user_from_request(?array $data = null): ?array
{
    $current = current_user();
    if ($current) return $current;
    $token = native_device_token_from_request($data);
    if ($token === '') return null;
    $user = native_user_for_device($token);
    if (!$user) return null;
    native_start_user_session($user);
    native_bind_device((int)$user['id'], $token, native_device_label_from_request($data));
    return $user;
}

function native_require_json_user(?array $data = null): array
{
    $user = native_restore_user_from_request($data);
    if (!$user) json_response(['ok'=>false,'error'=>'Sessão expirada. Entre novamente neste aparelho.'], 401);
    return $user;
}
