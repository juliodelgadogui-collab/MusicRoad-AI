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
    db()->exec("CREATE TABLE IF NOT EXISTS company_convoy_roster (
        company_id BIGINT NOT NULL,
        convoy_code VARCHAR(8) NOT NULL,
        user_id BIGINT NOT NULL,
        is_leader TINYINT NOT NULL DEFAULT 0,
        active TINYINT NOT NULL DEFAULT 1,
        created_at DATETIME NOT NULL,
        updated_at DATETIME NOT NULL,
        PRIMARY KEY(company_id,convoy_code,user_id),
        KEY idx_company_convoy_roster_active(company_id,convoy_code,active)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
} else {
    db()->exec("CREATE TABLE IF NOT EXISTS company_convoy_roster (
        company_id INTEGER NOT NULL,convoy_code TEXT NOT NULL,user_id INTEGER NOT NULL,
        is_leader INTEGER NOT NULL DEFAULT 0,active INTEGER NOT NULL DEFAULT 1,
        created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
        PRIMARY KEY(company_id,convoy_code,user_id)
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

$link = db()->prepare('SELECT convoy_code,title FROM company_convoy_links WHERE company_id=? AND active=1 LIMIT 1');
$link->execute([$companyId]);
$active = $link->fetch();
if (!$active) json_response(['ok'=>true,'active_convoy'=>null,'participants'=>[],'leader_user_id'=>0]);
$code = strtoupper((string)$active['convoy_code']);

if ($action === 'status') {
    $q = db()->prepare("SELECT r.user_id,r.is_leader,u.name,u.username,
                              v.id AS vehicle_id,v.plate,v.nickname,v.model
        FROM company_convoy_roster r
        JOIN users u ON u.id=r.user_id
        LEFT JOIN company_driver_vehicle a ON a.company_id=r.company_id AND a.user_id=r.user_id AND a.active=1
        LEFT JOIN company_vehicles v ON v.id=a.vehicle_id AND v.company_id=r.company_id
        WHERE r.company_id=? AND r.convoy_code=? AND r.active=1
        ORDER BY r.is_leader DESC,u.name,u.username");
    $q->execute([$companyId,$code]);
    $rows = $q->fetchAll() ?: [];
    $out = [];
    $leader = 0;
    foreach ($rows as $r) {
        $isLeader = (int)($r['is_leader'] ?? 0) === 1;
        if ($isLeader) $leader = (int)$r['user_id'];
        $vehicle = null;
        if ((int)($r['vehicle_id'] ?? 0) > 0) {
            $vehicle = ['id'=>(int)$r['vehicle_id'],'plate'=>(string)($r['plate'] ?? ''),'nickname'=>(string)($r['nickname'] ?? ''),'model'=>(string)($r['model'] ?? '')];
        }
        $out[] = ['user_id'=>(int)$r['user_id'],'name'=>(string)($r['name'] ?? ''),'username'=>(string)($r['username'] ?? ''),'leader'=>$isLeader,'vehicle'=>$vehicle];
    }
    json_response(['ok'=>true,'active_convoy'=>['code'=>$code,'title'=>(string)($active['title'] ?? '')],'participants'=>$out,'leader_user_id'=>$leader]);
}

if (!$manager) json_response(['ok'=>false,'error'=>'Sua conta não pode configurar o comboio da empresa.'],403);

if ($action === 'save') {
    $idsRaw = $data['driver_ids'] ?? [];
    if (!is_array($idsRaw)) json_response(['ok'=>false,'error'=>'Lista de motoristas inválida.'],422);
    $ids = [];
    foreach ($idsRaw as $raw) {
        $id = (int)$raw;
        if ($id > 0) $ids[$id] = true;
        if (count($ids) >= 100) break;
    }
    $ids = array_keys($ids);
    $leader = (int)($data['leader_user_id'] ?? 0);
    if ($leader > 0 && !in_array($leader,$ids,true)) json_response(['ok'=>false,'error'=>'O líder precisa estar incluído no comboio.'],422);

    if ($ids) {
        $marks = implode(',',array_fill(0,count($ids),'?'));
        $args = array_merge([$companyId],$ids);
        $check = db()->prepare("SELECT user_id FROM company_members WHERE company_id=? AND member_role='driver' AND status='active' AND user_id IN ($marks)");
        $check->execute($args);
        $valid = array_map('intval',$check->fetchAll(PDO::FETCH_COLUMN) ?: []);
        sort($valid); $expected=$ids; sort($expected);
        if ($valid !== $expected) json_response(['ok'=>false,'error'=>'Um dos motoristas não pertence mais à empresa.'],422);
    }

    $now = date('Y-m-d H:i:s');
    db()->beginTransaction();
    try {
        $off = db()->prepare('UPDATE company_convoy_roster SET active=0,is_leader=0,updated_at=? WHERE company_id=? AND convoy_code=?');
        $off->execute([$now,$companyId,$code]);
        foreach ($ids as $driverId) {
            $isLeader = $leader > 0 && $driverId === $leader ? 1 : 0;
            if ($mysql) {
                $s = db()->prepare("INSERT INTO company_convoy_roster (company_id,convoy_code,user_id,is_leader,active,created_at,updated_at)
                    VALUES (?,?,?,?,1,?,?) ON DUPLICATE KEY UPDATE is_leader=VALUES(is_leader),active=1,updated_at=VALUES(updated_at)");
            } else {
                $s = db()->prepare("INSERT INTO company_convoy_roster (company_id,convoy_code,user_id,is_leader,active,created_at,updated_at)
                    VALUES (?,?,?,?,1,?,?) ON CONFLICT(company_id,convoy_code,user_id) DO UPDATE SET is_leader=excluded.is_leader,active=1,updated_at=excluded.updated_at");
            }
            $s->execute([$companyId,$code,$driverId,$isLeader,$now,$now]);
        }
        $u = db()->prepare('UPDATE company_convoy_links SET updated_at=? WHERE company_id=? AND convoy_code=?');
        $u->execute([$now,$companyId,$code]);
        db()->commit();
    } catch (Throwable $e) {
        if (db()->inTransaction()) db()->rollBack();
        throw $e;
    }
    audit_log('company.convoy.roster',['company_id'=>$companyId,'code'=>$code,'leader_user_id'=>$leader,'participant_count'=>count($ids),'user_id'=>$userId]);
    json_response(['ok'=>true,'participant_count'=>count($ids),'leader_user_id'=>$leader]);
}

json_response(['ok'=>false,'error'=>'Ação inválida.'],400);
