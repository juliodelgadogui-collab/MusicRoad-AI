<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';

header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
header('Pragma: no-cache');
header('X-Content-Type-Options: nosniff');

$action = strtolower(trim((string)($_GET['action'] ?? 'ping')));
$data = input_json();

mr_native_ensure_tables();

if ($action === 'ping') {
    json_response(['ok' => true, 'service' => 'MusicRoad Native', 'version' => '1.4.0', 'time' => gmdate('c')]);
}

if ($action === 'device_login') {
    mr_native_require_post();
    $token = mr_native_token((string)($data['device_token'] ?? ''));
    if ($token === '') json_response(['ok'=>false,'error'=>'Identificação do dispositivo inválida.'],422);
    $stmt = db()->prepare('SELECT user_id FROM app_devices WHERE device_token = ? AND active = 1 LIMIT 1');
    $stmt->execute([$token]);
    $userId = (int)($stmt->fetchColumn() ?: 0);
    if ($userId <= 0) json_response(['ok'=>false,'recognized'=>false]);
    $user = mr_native_user($userId);
    if (!$user) json_response(['ok'=>false,'recognized'=>false]);
    $_SESSION['user_id'] = $userId;
    mr_native_touch_device($token, $userId, (string)($data['device_label'] ?? ''), (string)($data['app_version'] ?? ''));
    json_response(['ok'=>true,'recognized'=>true,'account'=>mr_native_account($user)]);
}

if ($action === 'login') {
    mr_native_require_post();
    $login = trim((string)($data['login'] ?? ''));
    $password = (string)($data['password'] ?? '');
    if ($login === '' || $password === '') json_response(['ok'=>false,'error'=>'Informe usuário e senha.'],422);
    $stmt = db()->prepare('SELECT * FROM users WHERE username = ? OR email = ? LIMIT 1');
    $stmt->execute([$login,$login]);
    $user = $stmt->fetch();
    if (!$user || !password_verify($password,(string)($user['password_hash'] ?? ''))) json_response(['ok'=>false,'error'=>'Usuário ou senha inválidos.'],401);
    session_regenerate_id(true);
    $_SESSION['user_id'] = (int)$user['id'];
    $token = mr_native_token((string)($data['device_token'] ?? ''));
    if ($token !== '') mr_native_bind_device($token,(int)$user['id'],(string)($data['device_label'] ?? ''),(string)($data['app_version'] ?? ''));
    try { audit_log('native.login',['user_id'=>(int)$user['id'],'device'=>substr($token,0,12)]); } catch (Throwable $e) {}
    json_response(['ok'=>true,'account'=>mr_native_account(mr_native_user((int)$user['id']))]);
}

if ($action === 'register') {
    mr_native_require_post();
    $name = trim((string)($data['name'] ?? ''));
    $email = strtolower(trim((string)($data['email'] ?? '')));
    $username = strtolower(trim((string)($data['username'] ?? '')));
    $username = preg_replace('/[^a-z0-9._-]+/','',$username) ?: '';
    $password = (string)($data['password'] ?? '');
    if (strlen($name) < 2) json_response(['ok'=>false,'error'=>'Informe seu nome.'],422);
    if (strlen($username) < 3) json_response(['ok'=>false,'error'=>'O login precisa ter pelo menos 3 caracteres.'],422);
    if (strlen($password) < 6) json_response(['ok'=>false,'error'=>'A senha precisa ter pelo menos 6 caracteres.'],422);
    if ($email === '') $email = $username . '@cliente.musicroad.local';
    if (!filter_var($email,FILTER_VALIDATE_EMAIL)) json_response(['ok'=>false,'error'=>'E-mail inválido.'],422);
    $exists = db()->prepare('SELECT id FROM users WHERE username = ? OR email = ? LIMIT 1');
    $exists->execute([$username,$email]);
    if ($exists->fetchColumn()) json_response(['ok'=>false,'error'=>'Já existe uma conta com esse login ou e-mail.'],409);
    $stmt = db()->prepare('INSERT INTO users (name,email,username,password_hash,role,created_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)');
    $stmt->execute([$name,$email,$username,password_hash($password,PASSWORD_DEFAULT),'client']);
    $id = (int)db()->lastInsertId();
    mr_native_create_trial($id);
    $token = mr_native_token((string)($data['device_token'] ?? ''));
    if ($token !== '') mr_native_bind_device($token,$id,(string)($data['device_label'] ?? ''),(string)($data['app_version'] ?? ''));
    session_regenerate_id(true); $_SESSION['user_id']=$id;
    try { audit_log('native.register',['user_id'=>$id,'device'=>substr($token,0,12)]); } catch (Throwable $e) {}
    json_response(['ok'=>true,'account'=>mr_native_account(mr_native_user($id))],201);
}

if ($action === 'me') {
    $user = current_user();
    if (!$user) json_response(['ok'=>false,'error'=>'Sessão não autenticada.'],401);
    json_response(['ok'=>true,'account'=>mr_native_account($user)]);
}

if ($action === 'library') {
    $user = current_user();
    if (!$user) json_response(['ok'=>false,'error'=>'Sessão não autenticada.'],401);
    $stmt = db()->prepare('SELECT m.id,m.title,m.artist,m.album,m.genre,m.duration,m.origin,m.origin_ref,m.mime_type,COALESCE(s.is_favorite,0) AS is_favorite,COALESCE(s.play_count,0) AS play_count FROM music_library m LEFT JOIN user_music_state s ON s.music_id=m.id AND s.user_id=? WHERE m.user_id IS NULL OR m.user_id=? ORDER BY m.title ASC LIMIT 750');
    $stmt->execute([(int)$user['id'],(int)$user['id']]);
    $tracks=[];
    foreach ($stmt->fetchAll() as $row) {
        $stream=''; $origin=(string)($row['origin'] ?? ''); $ref=trim((string)($row['origin_ref'] ?? ''));
        if ($ref !== '' && (str_contains($origin,'drive') || preg_match('/^[A-Za-z0-9_-]{20,}$/',$ref))) $stream='api/drive_stream.php?id='.rawurlencode($ref);
        elseif ($ref !== '' && preg_match('~^https?://~i',$ref)) $stream=$ref;
        $tracks[]=[
            'id'=>(int)$row['id'],'title'=>(string)$row['title'],'artist'=>(string)($row['artist'] ?? ''),'album'=>(string)($row['album'] ?? ''),
            'genre'=>(string)($row['genre'] ?? ''),'duration'=>(int)($row['duration'] ?? 0),'origin'=>$origin,'stream_url'=>$stream,
            'favorite'=>(int)($row['is_favorite'] ?? 0)===1,'play_count'=>(int)($row['play_count'] ?? 0)
        ];
    }
    json_response(['ok'=>true,'tracks'=>$tracks]);
}

if ($action === 'logout') {
    mr_native_require_post();
    $_SESSION=[];
    if (session_status() === PHP_SESSION_ACTIVE) session_destroy();
    json_response(['ok'=>true]);
}

json_response(['ok'=>false,'error'=>'Ação inválida.'],400);

function mr_native_require_post(): void { if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'POST') json_response(['ok'=>false,'error'=>'Método inválido.'],405); }
function mr_native_token(string $value): string { $v=strtolower(trim($value)); return preg_match('/^[a-f0-9]{32,128}$/',$v)?$v:''; }
function mr_native_user(int $id): ?array { $s=db()->prepare('SELECT id,name,email,username,role FROM users WHERE id = ? LIMIT 1');$s->execute([$id]);$u=$s->fetch();return $u?:null; }
function mr_native_account(array $user): array {
    $ent=null;try{$s=db()->prepare('SELECT status,trial_expires_at,subscription_expires_at FROM app_entitlements WHERE user_id = ? LIMIT 1');$s->execute([(int)$user['id']]);$ent=$s->fetch()?:null;}catch(Throwable $e){}
    return ['authenticated'=>true,'user'=>['id'=>(int)$user['id'],'name'=>(string)($user['name']??''),'email'=>(string)($user['email']??''),'username'=>(string)($user['username']??''),'role'=>(string)($user['role']??'user')],'entitlement'=>$ent];
}
function mr_native_bind_device(string $token,int $userId,string $label,string $version): void {
    $s=db()->prepare('SELECT id FROM app_devices WHERE device_token = ? LIMIT 1');$s->execute([$token]);$id=(int)($s->fetchColumn()?:0);
    if($id>0){$u=db()->prepare('UPDATE app_devices SET user_id=?,device_label=?,app_version=?,active=1,last_seen_at=CURRENT_TIMESTAMP WHERE id=?');$u->execute([$userId,substr($label,0,190),substr($version,0,40),$id]);}
    else{$i=db()->prepare('INSERT INTO app_devices (device_token,user_id,device_label,app_version,active,created_at,last_seen_at) VALUES (?,?,?,?,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)');$i->execute([$token,$userId,substr($label,0,190),substr($version,0,40)]);}
}
function mr_native_touch_device(string $token,int $userId,string $label,string $version): void { mr_native_bind_device($token,$userId,$label,$version); }
function mr_native_create_trial(int $userId): void {
    $expires=date('Y-m-d H:i:s',time()+86400);$s=db()->prepare('SELECT user_id FROM app_entitlements WHERE user_id = ? LIMIT 1');$s->execute([$userId]);
    if($s->fetchColumn())return;$i=db()->prepare('INSERT INTO app_entitlements (user_id,status,trial_expires_at,created_at,updated_at) VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)');$i->execute([$userId,'trial',$expires]);
}
function mr_native_ensure_tables(): void {
    $driver=(string)db()->getAttribute(PDO::ATTR_DRIVER_NAME);
    if($driver==='mysql'){
        db()->exec('CREATE TABLE IF NOT EXISTS app_devices (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, device_token VARCHAR(128) NOT NULL UNIQUE, user_id BIGINT NOT NULL, device_label VARCHAR(190) NULL, app_version VARCHAR(40) NULL, active TINYINT NOT NULL DEFAULT 1, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, last_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, INDEX idx_app_devices_user (user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4');
        db()->exec("CREATE TABLE IF NOT EXISTS app_entitlements (user_id BIGINT NOT NULL PRIMARY KEY, status VARCHAR(32) NOT NULL DEFAULT 'trial', trial_expires_at DATETIME NULL, subscription_expires_at DATETIME NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    } else {
        db()->exec('CREATE TABLE IF NOT EXISTS app_devices (id INTEGER PRIMARY KEY AUTOINCREMENT, device_token TEXT NOT NULL UNIQUE, user_id INTEGER NOT NULL, device_label TEXT, app_version TEXT, active INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, last_seen_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)');
        db()->exec('CREATE INDEX IF NOT EXISTS idx_app_devices_user ON app_devices(user_id)');
        db()->exec("CREATE TABLE IF NOT EXISTS app_entitlements (user_id INTEGER PRIMARY KEY, status TEXT NOT NULL DEFAULT 'trial', trial_expires_at TEXT, subscription_expires_at TEXT, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
    }
}
