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
$companyId = (int)$company['id'];

$v = db()->prepare("SELECT v.id,v.plate,v.nickname,v.model
    FROM company_driver_vehicle a JOIN company_vehicles v ON v.id=a.vehicle_id
    WHERE a.company_id=? AND a.user_id=? AND a.active=1 AND v.status='active'
    ORDER BY a.id DESC LIMIT 1");
$v->execute([$companyId,$userId]);
$vehicle = $v->fetch();
$vehicleOut = $vehicle ? [
    'id'=>(int)$vehicle['id'],
    'plate'=>(string)$vehicle['plate'],
    'nickname'=>(string)$vehicle['nickname'],
    'model'=>(string)$vehicle['model'],
] : null;

$km = db()->prepare('SELECT COALESCE(SUM(distance_m),0) FROM company_daily_km WHERE company_id=? AND user_id=? AND day_key=?');
$km->execute([$companyId,$userId,date('Y-m-d')]);
$kmToday = ((float)($km->fetchColumn() ?: 0))/1000.0;

$convoy = null;
try {
    $q = db()->prepare('SELECT convoy_code,title,updated_at FROM company_convoy_links WHERE company_id=? AND active=1 LIMIT 1');
    $q->execute([$companyId]);
    $c = $q->fetch();
    if ($c) {
        $code = (string)$c['convoy_code'];
        $allowed = true;
        $leader = false;
        try {
            // If the operation has an explicit roster, only selected drivers receive it.
            $count = db()->prepare('SELECT COUNT(*) FROM company_convoy_roster WHERE company_id=? AND convoy_code=? AND active=1');
            $count->execute([$companyId,$code]);
            $configured = (int)($count->fetchColumn() ?: 0);
            if ($configured > 0) {
                $selected = db()->prepare('SELECT is_leader FROM company_convoy_roster WHERE company_id=? AND convoy_code=? AND user_id=? AND active=1 LIMIT 1');
                $selected->execute([$companyId,$code,$userId]);
                $value = $selected->fetchColumn();
                $allowed = $value !== false;
                $leader = $allowed && (int)$value === 1;
            }
        } catch (Throwable $ignored) {
            // Backward compatibility before the roster table exists: the company convoy remains visible.
        }
        if ($allowed) {
            $convoy = [
                'code'=>$code,
                'title'=>(string)($c['title'] ?? ''),
                'updated_at'=>(string)$c['updated_at'],
                'leader'=>$leader,
            ];
        }
    }
} catch (Throwable $ignored) {}

json_response(['ok'=>true,'company'=>[
    'id'=>$companyId,
    'name'=>(string)$company['name'],
    'role'=>(string)$company['member_role'],
    'active_vehicle'=>$vehicleOut,
    'active_convoy'=>$convoy,
], 'km_today'=>round($kmToday,1)]);
