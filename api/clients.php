<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
$user = require_admin();
$action = (string)($_GET['action'] ?? ($_POST['action'] ?? 'list'));
refresh_expired_licenses();

if ($action === 'list') {
    require_session_rate_limit('clients-read',30,60);$csrf=csrf_token();session_write_close();
    $stmt = db()->query("SELECT u.id,u.name,u.email,u.username,u.role,u.status,u.created_at,
      (SELECT ends_at FROM licenses l WHERE l.user_id=u.id ORDER BY ends_at DESC,l.id DESC LIMIT 1) AS license_ends
      FROM users u WHERE u.role='client' ORDER BY u.id DESC LIMIT 300");
    json_response(['ok'=>true,'clients'=>$stmt->fetchAll(),'csrf'=>$csrf]);
}

if ($action === 'create') {
    require_csrf();require_session_rate_limit('clients-create',20,3600);session_write_close();
    $data = input_json();
    $name = trim((string)($data['name'] ?? ''));
    $username = strtolower(trim((string)($data['username'] ?? '')));
    $username = preg_replace('/[^a-z0-9._-]+/', '', $username) ?: '';
    $email = strtolower(trim((string)($data['email'] ?? '')));
    $password = (string)($data['password'] ?? '');
    $days = max(1,min(3650,(int)($data['days'] ?? 30)));
    if (strlen($name)<2) json_response(['ok'=>false,'error'=>'Informe o nome do cliente.'],422);
    if (strlen($username)<3) json_response(['ok'=>false,'error'=>'O login precisa ter pelo menos 3 caracteres.'],422);
    if (strlen($password)<8) json_response(['ok'=>false,'error'=>'A senha precisa ter pelo menos 8 caracteres.'],422);
    if ($email==='') $email=$username.'@cliente.musicroad.local';
    if (!filter_var($email,FILTER_VALIDATE_EMAIL)) json_response(['ok'=>false,'error'=>'E-mail inválido.'],422);
    try {
        $exists=db()->prepare('SELECT id FROM users WHERE username=? OR email=? LIMIT 1');
        $exists->execute([$username,$email]);
        if($exists->fetchColumn())json_response(['ok'=>false,'error'=>'Já existe um cliente com esse login ou e-mail.'],409);
        db()->beginTransaction();
        $stmt=db()->prepare("INSERT INTO users(name,email,username,password_hash,role,status,created_at,updated_at) VALUES(?,?,?,?,?,'active',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        $stmt->execute([$name,$email,$username,secure_password_hash($password),'client']);
        $id=(int)db()->lastInsertId();
        grant_license($id,$days,'admin',null,'Criado pelo aplicativo');
        db()->commit();
        audit_log('client_created',['id'=>$id,'username'=>$username,'days'=>$days]);
        $license=latest_license($id);
        json_response(['ok'=>true,'id'=>$id,'name'=>$name,'username'=>$username,'email'=>$email,'license_ends'=>$license['ends_at']??null]);
    } catch(Throwable $e) {
        if(db()->inTransaction())db()->rollBack();
        $incident=report_runtime_exception('clients_create',$e);json_response(['ok'=>false,'error'=>'Não foi possível criar o cliente.','incident'=>$incident],500);
    }
}
json_response(['ok'=>false,'error'=>'Ação inválida.'],400);
