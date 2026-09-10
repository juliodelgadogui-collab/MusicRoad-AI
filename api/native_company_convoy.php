<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';

header('Content-Type: application/json; charset=utf-8');
$data = input_json();
$user = native_require_json_user($data);
$userId = (int)($user['id'] ?? 0);
$action = strtolower(trim((string)($_GET['action'] ?? 'status')));

$mysql = (string)db()->getAttribute(PDO::ATTR_DRIVER_NAME) === 'mysql';
if ($mysql) {
    db()->exec("CREATE TABLE IF NOT EXISTS company_convoy_links (
        company_id BIGINT NOT NULL PRIMARY KEY,
        convoy_code VARCHAR(8) NOT NULL,
        title VARCHAR(120) NOT NULL DEFAULT '',
        created_by BIGINT NOT NULL,
        active TINYINT NOT NULL DEFAULT 1,
        created_at DATETIME NOT NULL,
        updated_at DATETIME NOT NULL,
        KEY idx_company_convoy_active (active,updated_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
} else {
    db()->exec("CREATE TABLE IF NOT EXISTS company_convoy_links (
        company_id INTEGER PRIMARY KEY,convoy_code TEXT NOT NULL,title TEXT NOT NULL DEFAULT '',
        created_by INTEGER NOT NULL,active INTEGER NOT NULL DEFAULT 1,created_at TEXT NOT NULL,updated_at TEXT NOT NULL
    )");
}

$m = db()->prepare("SELECT c.id,c.name,m.member_role
    FROM company_members m JOIN companies c ON c.id=m.company_id
    WHERE m.user_id=? AND m.status='active' AND c.status='active'
    ORDER BY m.id ASC LIMIT 1");
$m->execute([$userId]);
$company = $m->fetch();
if (!$company) json_response(['ok'=>false,'error'=>'Esta conta não está vinculada a uma empresa.'],403);
$companyId = (int)$company['id'];
$role = strtolower((string)($company['member_role'] ?? ''));
$manager = in_array($role,['owner','admin','manager'],true);

if ($action === 'status') {
    $q = db()->prepare('SELECT convoy_code,title,created_by,created_at,updated_at FROM company_convoy_links WHERE company_id=? AND active=1 LIMIT 1');
    $q->execute([$companyId]);
    $row = $q->fetch();
    $active = null;
    if ($row) {
        $active = [
            'code'=>(string)$row['convoy_code'],
            'title'=>(string)($row['title'] ?? ''),
            'created_by'=>(int)$row['created_by'],
            'created_at'=>(string)$row['created_at'],
            'updated_at'=>(string)$row['updated_at'],
        ];
    }
    json_response(['ok'=>true,'company_id'=>$companyId,'active_convoy'=>$active]);
}

if (!$manager) json_response(['ok'=>false,'error'=>'Sua conta não pode administrar o comboio da empresa.'],403);

if ($action === 'publish') {
    $code = strtoupper(preg_replace('/[^A-Z0-9]/i','',(string)($data['code'] ?? '')) ?? '');
    $code = substr($code,0,8);
    $title = trim((string)($data['title'] ?? 'Comboio da empresa'));
    $title = function_exists('mb_substr') ? mb_substr($title,0,120) : substr($title,0,120);
    if (strlen($code) < 4) json_response(['ok'=>false,'error'=>'Código de comboio inválido.'],422);

    // Only publish a real, non-expired Estrada Play convoy.
    try {
        $check = db()->prepare('SELECT id FROM estrada_convoys WHERE code=? AND expires_at>NOW() LIMIT 1');
        $check->execute([$code]);
        if (!$check->fetchColumn()) json_response(['ok'=>false,'error'=>'Crie um comboio válido antes de publicá-lo para a empresa.'],404);
    } catch (Throwable $e) {
        json_response(['ok'=>false,'error'=>'O Comboio ainda não foi inicializado no servidor.'],503);
    }

    $now = date('Y-m-d H:i:s');
    if ($mysql) {
        $s = db()->prepare("INSERT INTO company_convoy_links (company_id,convoy_code,title,created_by,active,created_at,updated_at)
            VALUES (?,?,?,?,1,?,?) ON DUPLICATE KEY UPDATE convoy_code=VALUES(convoy_code),title=VALUES(title),created_by=VALUES(created_by),active=1,updated_at=VALUES(updated_at)");
    } else {
        $s = db()->prepare("INSERT INTO company_convoy_links (company_id,convoy_code,title,created_by,active,created_at,updated_at)
            VALUES (?,?,?,?,1,?,?) ON CONFLICT(company_id) DO UPDATE SET convoy_code=excluded.convoy_code,title=excluded.title,created_by=excluded.created_by,active=1,updated_at=excluded.updated_at");
    }
    $s->execute([$companyId,$code,$title,$userId,$now,$now]);
    audit_log('company.convoy.publish',['company_id'=>$companyId,'code'=>$code,'user_id'=>$userId]);
    json_response(['ok'=>true,'active_convoy'=>['code'=>$code,'title'=>$title,'created_by'=>$userId,'created_at'=>$now,'updated_at'=>$now]]);
}

if ($action === 'close') {
    $u = db()->prepare('UPDATE company_convoy_links SET active=0,updated_at=? WHERE company_id=?');
    $u->execute([date('Y-m-d H:i:s'),$companyId]);
    audit_log('company.convoy.close',['company_id'=>$companyId,'user_id'=>$userId]);
    json_response(['ok'=>true]);
}

json_response(['ok'=>false,'error'=>'Ação inválida.'],400);
