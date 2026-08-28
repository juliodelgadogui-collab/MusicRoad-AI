<?php
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

if ($action === 'library_page') {
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
