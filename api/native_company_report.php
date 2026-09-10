<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';

header('Content-Type: application/json; charset=utf-8');
$data = input_json();
$user = native_require_json_user($data);
$userId = (int)($user['id'] ?? 0);
$days = (int)($data['days'] ?? 7);
$days = in_array($days,[1,7,30],true) ? $days : 7;

$m = db()->prepare("SELECT c.id,c.name,m.member_role
    FROM company_members m JOIN companies c ON c.id=m.company_id
    WHERE m.user_id=? AND m.status='active' AND c.status='active'
    ORDER BY m.id ASC LIMIT 1");
$m->execute([$userId]);
$company = $m->fetch();
if (!$company) json_response(['ok'=>false,'error'=>'Esta conta não está vinculada a uma empresa.'],403);
$role = strtolower((string)($company['member_role'] ?? ''));
if (!in_array($role,['owner','admin','manager'],true)) json_response(['ok'=>false,'error'=>'Sua conta não tem permissão para ver relatórios da empresa.'],403);
$companyId = (int)$company['id'];

$to = date('Y-m-d');
$from = date('Y-m-d', strtotime('-'.($days-1).' days'));
$sql = "SELECT k.day_key,k.user_id,k.vehicle_id,SUM(k.distance_m) AS distance_m,
               u.name AS driver_name,u.username,
               v.plate,v.nickname,v.model
        FROM company_daily_km k
        JOIN users u ON u.id=k.user_id
        JOIN company_vehicles v ON v.id=k.vehicle_id AND v.company_id=k.company_id
        WHERE k.company_id=? AND k.day_key>=? AND k.day_key<=?
        GROUP BY k.day_key,k.user_id,k.vehicle_id,u.name,u.username,v.plate,v.nickname,v.model
        ORDER BY k.day_key DESC,distance_m DESC,u.name";
$q = db()->prepare($sql);
$q->execute([$companyId,$from,$to]);
$rows = $q->fetchAll() ?: [];

$out = [];
$totalM = 0.0;
$drivers = [];
$vehicles = [];
$daysWithKm = [];
foreach ($rows as $r) {
    $m = max(0.0,(float)($r['distance_m'] ?? 0));
    $totalM += $m;
    $uid = (int)$r['user_id'];
    $vid = (int)$r['vehicle_id'];
    $drivers[$uid] = ($drivers[$uid] ?? 0.0) + $m;
    $vehicles[$vid] = ($vehicles[$vid] ?? 0.0) + $m;
    $daysWithKm[(string)$r['day_key']] = true;
    $nickname = trim((string)($r['nickname'] ?? ''));
    $plate = trim((string)($r['plate'] ?? ''));
    $model = trim((string)($r['model'] ?? ''));
    $vehicleLabel = $nickname !== '' ? $nickname . ($plate !== '' ? ' · '.$plate : '') : ($plate !== '' ? $plate : ($model !== '' ? $model : 'Veículo'));
    $out[] = [
        'day'=>(string)$r['day_key'],
        'user_id'=>$uid,
        'driver_name'=>(string)($r['driver_name'] ?? 'Motorista'),
        'username'=>(string)($r['username'] ?? ''),
        'vehicle_id'=>$vid,
        'vehicle_label'=>$vehicleLabel,
        'distance_km'=>round($m/1000.0,1),
    ];
}

$driverTotals = [];
foreach ($drivers as $uid=>$meters) {
    $name = '';
    foreach ($out as $row) if ((int)$row['user_id'] === (int)$uid) { $name=(string)$row['driver_name']; break; }
    $driverTotals[]=['user_id'=>(int)$uid,'name'=>$name,'distance_km'=>round($meters/1000.0,1)];
}
usort($driverTotals,static fn(array $a,array $b): int => $b['distance_km'] <=> $a['distance_km']);

json_response([
    'ok'=>true,
    'company'=>['id'=>$companyId,'name'=>(string)$company['name'],'role'=>$role],
    'period'=>['days'=>$days,'from'=>$from,'to'=>$to],
    'summary'=>[
        'distance_km'=>round($totalM/1000.0,1),
        'drivers'=>count($drivers),
        'vehicles'=>count($vehicles),
        'days_with_km'=>count($daysWithKm),
    ],
    'driver_totals'=>$driverTotals,
    'rows'=>$out,
]);
