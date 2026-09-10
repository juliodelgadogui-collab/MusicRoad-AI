<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';

header('Content-Type: application/json; charset=utf-8');
$data = input_json();
$user = native_require_json_user($data);
$userId = (int)($user['id'] ?? 0);

try {
    $m = db()->prepare("SELECT c.id,c.name,m.member_role
        FROM company_members m JOIN companies c ON c.id=m.company_id
        WHERE m.user_id=? AND m.status='active' AND c.status='active'
        ORDER BY m.id ASC LIMIT 1");
    $m->execute([$userId]);
    $company = $m->fetch();
} catch (Throwable $e) {
    json_response(['ok'=>false,'error'=>'Módulo empresa ainda não inicializado no servidor.'],503);
}

if (!$company) json_response(['ok'=>false,'error'=>'Esta conta não está vinculada a uma empresa.'],403);
$role = strtolower((string)($company['member_role'] ?? ''));
if (!in_array($role,['owner','admin','manager'],true)) json_response(['ok'=>false,'error'=>'Sua conta não tem permissão para ver a frota.'],403);
$companyId = (int)$company['id'];

// If Android kills a process before journeyStop reaches the server, do not leave a trip
// permanently marked as active. Ten minutes without matching presence closes it at the
// last known sample (or at start time when no sample survived).
try {
    $stale = db()->prepare("SELECT j.id,j.started_at,p.last_seen_at
        FROM company_journeys j
        LEFT JOIN company_presence p ON p.company_id=j.company_id AND p.user_id=j.user_id AND p.journey_id=j.id
        WHERE j.company_id=? AND j.status='active'");
    $stale->execute([$companyId]);
    $now = time();
    $close = db()->prepare("UPDATE company_journeys SET ended_at=?,status='closed' WHERE id=? AND company_id=? AND status='active'");
    foreach ($stale->fetchAll() ?: [] as $candidate) {
        $lastRaw = (string)($candidate['last_seen_at'] ?? '');
        $lastTs = $lastRaw !== '' ? (strtotime($lastRaw) ?: 0) : 0;
        if ($lastTs > 0 && ($now - $lastTs) <= 600) continue;
        $endedAt = $lastTs > 0 ? date('Y-m-d H:i:s',$lastTs) : (string)($candidate['started_at'] ?? date('Y-m-d H:i:s'));
        $close->execute([$endedAt,(int)$candidate['id'],$companyId]);
    }
} catch (Throwable $ignored) {}

$s = db()->prepare("SELECT j.id,j.started_at,j.ended_at,j.distance_m,j.status,
        u.name AS driver_name,u.username,
        v.plate,v.nickname,v.model
    FROM company_journeys j
    JOIN users u ON u.id=j.user_id
    JOIN company_vehicles v ON v.id=j.vehicle_id
    WHERE j.company_id=?
    ORDER BY j.started_at DESC,j.id DESC
    LIMIT 100");
$s->execute([$companyId]);
$rows = $s->fetchAll() ?: [];
$out = [];
foreach ($rows as $r) {
    $nickname = trim((string)($r['nickname'] ?? ''));
    $plate = trim((string)($r['plate'] ?? ''));
    $model = trim((string)($r['model'] ?? ''));
    $vehicle = $nickname !== '' ? $nickname . ($plate !== '' ? ' · '.$plate : '') : ($plate !== '' ? $plate : ($model !== '' ? $model : 'Veículo'));
    $out[] = [
        'id'=>(int)$r['id'],
        'driver_name'=>(string)($r['driver_name'] ?? 'Motorista'),
        'username'=>(string)($r['username'] ?? ''),
        'vehicle_label'=>$vehicle,
        'started_at'=>(string)($r['started_at'] ?? ''),
        'ended_at'=>(string)($r['ended_at'] ?? ''),
        'distance_km'=>round(((float)($r['distance_m'] ?? 0))/1000.0,1),
        'status'=>(string)($r['status'] ?? ''),
    ];
}
json_response(['ok'=>true,'company'=>['id'=>$companyId,'name'=>(string)$company['name'],'role'=>$role],'journeys'=>$out]);
