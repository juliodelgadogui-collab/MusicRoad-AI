<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';

header('Content-Type: application/json; charset=utf-8');
$data = input_json();
$user = native_require_json_user($data);
$userId = (int)($user['id'] ?? 0);

$m = db()->prepare("SELECT c.id,c.name,m.member_role
    FROM company_members m JOIN companies c ON c.id=m.company_id
    WHERE m.user_id=? AND m.status='active' AND c.status='active'
    ORDER BY m.id ASC LIMIT 1");
$m->execute([$userId]);
$company = $m->fetch();
if (!$company) json_response(['ok'=>false,'error'=>'Esta conta não está vinculada a uma empresa.'],403);
$role = strtolower((string)($company['member_role'] ?? ''));
if (!in_array($role,['owner','admin','manager'],true)) json_response(['ok'=>false,'error'=>'Sua conta não tem permissão para ver o mapa da frota.'],403);
$companyId = (int)$company['id'];
$day = date('Y-m-d');

$sql = "SELECT p.user_id,p.vehicle_id,p.lat,p.lon,p.speed_kmh,p.heading,p.last_seen_at,
               u.name AS driver_name,u.username,
               v.plate,v.nickname,v.model,
               COALESCE((SELECT SUM(k.distance_m) FROM company_daily_km k
                         WHERE k.company_id=p.company_id AND k.user_id=p.user_id AND k.day_key=?),0) AS distance_m
        FROM company_presence p
        JOIN company_members m ON m.company_id=p.company_id AND m.user_id=p.user_id AND m.status='active'
        JOIN users u ON u.id=p.user_id
        JOIN company_vehicles v ON v.id=p.vehicle_id AND v.company_id=p.company_id AND v.status='active'
        WHERE p.company_id=?
        ORDER BY p.last_seen_at DESC";
$s = db()->prepare($sql);
$s->execute([$day,$companyId]);
$rows = $s->fetchAll() ?: [];
$now = time();
$out = [];
foreach ($rows as $r) {
    $last = (string)($r['last_seen_at'] ?? '');
    $lastTs = $last !== '' ? (strtotime($last) ?: 0) : 0;
    $age = $lastTs > 0 ? max(0,$now-$lastTs) : PHP_INT_MAX;
    if ($age > 900) continue; // do not place stale locations from old journeys on the live map
    $out[] = [
        'user_id'=>(int)$r['user_id'],
        'vehicle_id'=>(int)$r['vehicle_id'],
        'driver_name'=>(string)($r['driver_name'] ?? 'Motorista'),
        'username'=>(string)($r['username'] ?? ''),
        'plate'=>(string)($r['plate'] ?? ''),
        'nickname'=>(string)($r['nickname'] ?? ''),
        'model'=>(string)($r['model'] ?? ''),
        'lat'=>(float)$r['lat'],
        'lon'=>(float)$r['lon'],
        'speed_kmh'=>(float)($r['speed_kmh'] ?? 0),
        'heading'=>(float)($r['heading'] ?? -1),
        'last_seen_at'=>$last,
        'age_s'=>$age,
        'online'=>$age <= 120,
        'km_today'=>round(((float)($r['distance_m'] ?? 0))/1000.0,1),
    ];
}
json_response([
    'ok'=>true,
    'company'=>['id'=>$companyId,'name'=>(string)$company['name'],'role'=>$role],
    'vehicles'=>$out,
    'live_count'=>count(array_filter($out, static fn(array $v): bool => !empty($v['online']))),
    'server_time'=>time(),
]);
