<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';

header('Content-Type: application/json; charset=utf-8');
$data = input_json();
$user = native_require_json_user($data);
$userId = (int)($user['id'] ?? 0);
$name = trim((string)($data['name'] ?? ''));
if ($userId <= 0) json_response(['ok'=>false,'error'=>'Conta inválida.'],401);
if (function_exists('mb_strlen') ? mb_strlen($name) < 3 : strlen($name) < 3) json_response(['ok'=>false,'error'=>'Informe o nome da empresa.'],422);
$name = function_exists('mb_substr') ? mb_substr($name,0,180) : substr($name,0,180);

$mysql = (string)db()->getAttribute(PDO::ATTR_DRIVER_NAME) === 'mysql';
if ($mysql) {
    db()->exec("CREATE TABLE IF NOT EXISTS companies (id BIGINT AUTO_INCREMENT PRIMARY KEY,name VARCHAR(180) NOT NULL,owner_user_id BIGINT NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'active',created_at DATETIME NOT NULL,updated_at DATETIME NOT NULL,KEY idx_company_owner(owner_user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
    db()->exec("CREATE TABLE IF NOT EXISTS company_members (id BIGINT AUTO_INCREMENT PRIMARY KEY,company_id BIGINT NOT NULL,user_id BIGINT NOT NULL,member_role VARCHAR(24) NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'active',created_at DATETIME NOT NULL,updated_at DATETIME NOT NULL,UNIQUE KEY uq_company_member(company_id,user_id),KEY idx_company_member_user(user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
} else {
    db()->exec("CREATE TABLE IF NOT EXISTS companies (id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,owner_user_id INTEGER NOT NULL,status TEXT NOT NULL DEFAULT 'active',created_at TEXT NOT NULL,updated_at TEXT NOT NULL)");
    db()->exec("CREATE TABLE IF NOT EXISTS company_members (id INTEGER PRIMARY KEY AUTOINCREMENT,company_id INTEGER NOT NULL,user_id INTEGER NOT NULL,member_role TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'active',created_at TEXT NOT NULL,updated_at TEXT NOT NULL,UNIQUE(company_id,user_id))");
}

$existing = db()->prepare("SELECT c.id,c.name,m.member_role FROM company_members m JOIN companies c ON c.id=m.company_id WHERE m.user_id=? AND m.status='active' AND c.status='active' ORDER BY m.id ASC LIMIT 1");
$existing->execute([$userId]);
$row = $existing->fetch();
if ($row) {
    json_response(['ok'=>false,'error'=>'Esta conta já está vinculada à empresa '.(string)$row['name'].'.'],409);
}

$now = date('Y-m-d H:i:s');
db()->beginTransaction();
try {
    $i = db()->prepare("INSERT INTO companies (name,owner_user_id,status,created_at,updated_at) VALUES (?,?,'active',?,?)");
    $i->execute([$name,$userId,$now,$now]);
    $companyId = (int)db()->lastInsertId();
    $m = db()->prepare("INSERT INTO company_members (company_id,user_id,member_role,status,created_at,updated_at) VALUES (?,?,'owner','active',?,?)");
    $m->execute([$companyId,$userId,$now,$now]);
    db()->commit();
} catch (Throwable $e) {
    if (db()->inTransaction()) db()->rollBack();
    json_response(['ok'=>false,'error'=>'Não foi possível criar a empresa agora.'],500);
}

audit_log('company.create',['company_id'=>$companyId,'owner_user_id'=>$userId]);
json_response(['ok'=>true,'company'=>[
    'id'=>$companyId,
    'name'=>$name,
    'role'=>'owner',
    'can_manage'=>true,
    'active_vehicle'=>null,
]]);
