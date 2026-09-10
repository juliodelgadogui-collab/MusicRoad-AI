<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';

header('Content-Type: application/json; charset=utf-8');
$data = input_json();
$user = native_require_json_user($data);
$userId = (int)($user['id'] ?? 0);
$action = strtolower(trim((string)($_GET['action'] ?? 'list')));
$mysql = (string)db()->getAttribute(PDO::ATTR_DRIVER_NAME) === 'mysql';

if ($mysql) {
    db()->exec("CREATE TABLE IF NOT EXISTS company_fuel_entries (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        company_id BIGINT NOT NULL,
        vehicle_id BIGINT NOT NULL,
        user_id BIGINT NOT NULL,
        liters DOUBLE NOT NULL,
        total_value DOUBLE NOT NULL,
        odometer_km DOUBLE NULL,
        station VARCHAR(160) NOT NULL DEFAULT '',
        notes VARCHAR(400) NOT NULL DEFAULT '',
        filled_at DATETIME NOT NULL,
        created_at DATETIME NOT NULL,
        KEY idx_company_fuel_company(company_id,filled_at),
        KEY idx_company_fuel_vehicle(company_id,vehicle_id,filled_at),
        KEY idx_company_fuel_user(company_id,user_id,filled_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
} else {
    db()->exec("CREATE TABLE IF NOT EXISTS company_fuel_entries (
        id INTEGER PRIMARY KEY AUTOINCREMENT,company_id INTEGER NOT NULL,vehicle_id INTEGER NOT NULL,user_id INTEGER NOT NULL,
        liters REAL NOT NULL,total_value REAL NOT NULL,odometer_km REAL,station TEXT NOT NULL DEFAULT '',notes TEXT NOT NULL DEFAULT '',filled_at TEXT NOT NULL,created_at TEXT NOT NULL
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
$role = strtolower((string)($company['member_role'] ?? 'driver'));
$manager = in_array($role,['owner','admin','manager'],true);

function company_fuel_active_vehicle(int $companyId,int $userId): int {
    $q=db()->prepare("SELECT v.id FROM company_driver_vehicle a JOIN company_vehicles v ON v.id=a.vehicle_id
        WHERE a.company_id=? AND a.user_id=? AND a.active=1 AND v.status='active' ORDER BY a.id DESC LIMIT 1");
    $q->execute([$companyId,$userId]);return (int)($q->fetchColumn()?:0);
}
function company_fuel_vehicle(int $companyId,int $vehicleId): ?array {
    $q=db()->prepare("SELECT id,plate,nickname,model FROM company_vehicles WHERE company_id=? AND id=? AND status='active' LIMIT 1");$q->execute([$companyId,$vehicleId]);$r=$q->fetch();return $r?:null;
}
function company_fuel_label(array $v): string {
    $nick=trim((string)($v['nickname']??''));$plate=trim((string)($v['plate']??''));$model=trim((string)($v['model']??''));
    return $nick!==''?$nick.($plate!==''?' · '.$plate:''):($plate!==''?$plate:($model!==''?$model:'Veículo'));
}

if ($action === 'add') {
    $vehicleId = $manager ? (int)($data['vehicle_id'] ?? 0) : company_fuel_active_vehicle($companyId,$userId);
    $vehicle = company_fuel_vehicle($companyId,$vehicleId);
    if (!$vehicle) json_response(['ok'=>false,'error'=>$manager?'Selecione um veículo da empresa.':'A empresa precisa atribuir um veículo antes do abastecimento.'],422);
    $liters=max(0.0,min(2000.0,(float)($data['liters']??0)));$total=max(0.0,min(100000.0,(float)($data['total_value']??0)));
    $odoRaw=$data['odometer_km']??null;$odo=$odoRaw===null||$odoRaw===''?null:max(0.0,(float)$odoRaw);
    $station=trim((string)($data['station']??''));$notes=trim((string)($data['notes']??''));
    $station=function_exists('mb_substr')?mb_substr($station,0,160):substr($station,0,160);$notes=function_exists('mb_substr')?mb_substr($notes,0,400):substr($notes,0,400);
    if($liters<=0||$total<=0)json_response(['ok'=>false,'error'=>'Informe litros e valor total do abastecimento.'],422);
    $now=date('Y-m-d H:i:s');
    $i=db()->prepare('INSERT INTO company_fuel_entries (company_id,vehicle_id,user_id,liters,total_value,odometer_km,station,notes,filled_at,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)');
    $i->execute([$companyId,$vehicleId,$userId,$liters,$total,$odo,$station,$notes,$now,$now]);$id=(int)db()->lastInsertId();
    audit_log('company.fuel.add',['company_id'=>$companyId,'vehicle_id'=>$vehicleId,'entry_id'=>$id,'liters'=>round($liters,2),'total_value'=>round($total,2),'user_id'=>$userId]);
    json_response(['ok'=>true,'entry_id'=>$id,'price_per_liter'=>round($total/$liters,3)]);
}

if ($action === 'list') {
    $days=(int)($data['days']??30);$days=in_array($days,[1,7,30],true)?$days:30;$from=date('Y-m-d 00:00:00',strtotime('-'.($days-1).' days'));
    $args=[$companyId,$from];$where='f.company_id=? AND f.filled_at>=?';
    if(!$manager){$where.=' AND f.user_id=?';$args[]=$userId;}
    $sql="SELECT f.*,u.name AS driver_name,u.username,v.plate,v.nickname,v.model
        FROM company_fuel_entries f JOIN users u ON u.id=f.user_id JOIN company_vehicles v ON v.id=f.vehicle_id AND v.company_id=f.company_id
        WHERE $where ORDER BY f.filled_at DESC,f.id DESC LIMIT 300";
    $q=db()->prepare($sql);$q->execute($args);$rows=$q->fetchAll()?:[];$out=[];$liters=0.0;$value=0.0;
    foreach($rows as $r){$l=(float)$r['liters'];$v=(float)$r['total_value'];$liters+=$l;$value+=$v;$vehicle=['plate'=>(string)($r['plate']??''),'nickname'=>(string)($r['nickname']??''),'model'=>(string)($r['model']??'')];$out[]=[
        'id'=>(int)$r['id'],'vehicle_id'=>(int)$r['vehicle_id'],'vehicle_label'=>company_fuel_label($vehicle),'driver_name'=>(string)($r['driver_name']??'Motorista'),
        'liters'=>round($l,2),'total_value'=>round($v,2),'price_per_liter'=>$l>0?round($v/$l,3):0,
        'odometer_km'=>$r['odometer_km']===null?null:(float)$r['odometer_km'],'station'=>(string)($r['station']??''),'notes'=>(string)($r['notes']??''),'filled_at'=>(string)$r['filled_at']];}
    $kmSql='SELECT COALESCE(SUM(distance_m),0) FROM company_daily_km WHERE company_id=? AND day_key>=?';$kmArgs=[$companyId,substr($from,0,10)];
    if(!$manager){$kmSql.=' AND user_id=?';$kmArgs[]=$userId;}
    $k=db()->prepare($kmSql);$k->execute($kmArgs);$trackedKm=((float)($k->fetchColumn()?:0))/1000.0;
    json_response(['ok'=>true,'company'=>['id'=>$companyId,'name'=>(string)$company['name'],'role'=>$role],'period'=>['days'=>$days,'from'=>substr($from,0,10),'to'=>date('Y-m-d')],'summary'=>[
        'entries'=>count($out),'liters'=>round($liters,2),'total_value'=>round($value,2),'avg_price_per_liter'=>$liters>0?round($value/$liters,3):0,
        'tracked_km'=>round($trackedKm,1),'cost_per_km'=>$trackedKm>0?round($value/$trackedKm,3):0,
    ],'entries'=>$out]);
}

json_response(['ok'=>false,'error'=>'Ação inválida.'],400);
