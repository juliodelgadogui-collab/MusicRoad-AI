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
if (!in_array($role,['owner','admin','manager'],true)) json_response(['ok'=>false,'error'=>'Sua conta não tem permissão para administrar a frota.'],403);
$companyId = (int)$company['id'];
$day = date('Y-m-d');

$sql = "SELECT u.id AS user_id,u.name,u.username,u.email,m.member_role,
               v.id AS vehicle_id,v.plate,v.nickname,v.model,
               p.last_seen_at,p.speed_kmh,
               COALESCE((SELECT SUM(k.distance_m) FROM company_daily_km k
                         WHERE k.company_id=m.company_id AND k.user_id=m.user_id AND k.day_key=?),0) AS distance_m
        FROM company_members m
        JOIN users u ON u.id=m.user_id
        LEFT JOIN company_driver_vehicle a ON a.company_id=m.company_id AND a.user_id=m.user_id AND a.active=1
        LEFT JOIN company_vehicles v ON v.id=a.vehicle_id AND v.company_id=m.company_id
        LEFT JOIN company_presence p ON p.company_id=m.company_id AND p.user_id=m.user_id
        WHERE m.company_id=? AND m.status='active' AND m.member_role='driver'
        ORDER BY u.name,u.username";
$s = db()->prepare($sql);
$s->execute([$day,$companyId]);
$rows = $s->fetchAll() ?: [];
$now = time();
$out = [];
foreach ($rows as $r) {
    $last = (string)($r['last_seen_at'] ?? '');
    $lastTs = $last !== '' ? (strtotime($last) ?: 0) : 0;
    $age = $lastTs > 0 ? max(0,$now-$lastTs) : PHP_INT_MAX;
    $vehicle = null;
    if ((int)($r['vehicle_id'] ?? 0) > 0) {
        $vehicle = [
            'id'=>(int)$r['vehicle_id'],
            'plate'=>(string)($r['plate'] ?? ''),
            'nickname'=>(string)($r['nickname'] ?? ''),
            'model'=>(string)($r['model'] ?? ''),
        ];
    }
    $out[] = [
        'user_id'=>(int)$r['user_id'],
        'name'=>(string)($r['name'] ?? 'Motorista'),
        'username'=>(string)($r['username'] ?? ''),
        'email'=>(string)($r['email'] ?? ''),
        'role'=>(string)($r['member_role'] ?? 'driver'),
        'vehicle'=>$vehicle,
        'km_today'=>round(((float)($r['distance_m'] ?? 0))/1000.0,1),
        'last_seen_at'=>$last,
        'speed_kmh'=>(float)($r['speed_kmh'] ?? 0),
        'presence'=>$age<=120 ? 'NA ESTRADA' : ($last!=='' ? 'OFFLINE' : 'SEM JORNADA'),
    ];
}
json_response(['ok'=>true,'company'=>['id'=>$companyId,'name'=>(string)$company['name'],'role'=>$role],'drivers'=>$out]);
