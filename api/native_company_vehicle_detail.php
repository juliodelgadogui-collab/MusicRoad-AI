<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';

header('Content-Type: application/json; charset=utf-8');
$data=input_json();
$user=native_require_json_user($data);
$userId=(int)($user['id']??0);
$vehicleId=(int)($data['vehicle_id']??0);
if($vehicleId<=0)json_response(['ok'=>false,'error'=>'Veículo inválido.'],422);

$m=db()->prepare("SELECT c.id,c.name,m.member_role FROM company_members m JOIN companies c ON c.id=m.company_id WHERE m.user_id=? AND m.status='active' AND c.status='active' ORDER BY m.id ASC LIMIT 1");
$m->execute([$userId]);$company=$m->fetch();
if(!$company)json_response(['ok'=>false,'error'=>'Esta conta não está vinculada a uma empresa.'],403);
$role=strtolower((string)($company['member_role']??''));
if(!in_array($role,['owner','admin','manager'],true))json_response(['ok'=>false,'error'=>'Sua conta não pode ver detalhes da frota.'],403);
$companyId=(int)$company['id'];

$v=db()->prepare("SELECT id,plate,nickname,model,year,odometer_km,status FROM company_vehicles WHERE company_id=? AND id=? AND status='active' LIMIT 1");
$v->execute([$companyId,$vehicleId]);$vehicle=$v->fetch();
if(!$vehicle)json_response(['ok'=>false,'error'=>'Veículo não encontrado.'],404);

function scalar_float(string $sql,array $args): float { try{$q=db()->prepare($sql);$q->execute($args);return (float)($q->fetchColumn()?:0);}catch(Throwable $e){return 0.0;} }
function scalar_int(string $sql,array $args): int { return (int)round(scalar_float($sql,$args)); }
function vehicle_label(array $v): string {
    $n=trim((string)($v['nickname']??''));$p=trim((string)($v['plate']??''));$m=trim((string)($v['model']??''));
    if($n!=='')return $n.($p!==''?' · '.$p:'');if($p!=='')return $p;return $m!==''?$m:'Veículo';
}

$driver=null;
try{
    $q=db()->prepare("SELECT u.id,u.name,u.username FROM company_driver_vehicle a JOIN users u ON u.id=a.user_id WHERE a.company_id=? AND a.vehicle_id=? AND a.active=1 ORDER BY a.id DESC LIMIT 1");
    $q->execute([$companyId,$vehicleId]);$r=$q->fetch();
    if($r)$driver=['user_id'=>(int)$r['id'],'name'=>(string)($r['name']??''),'username'=>(string)($r['username']??'')];
}catch(Throwable $ignored){}

$today=date('Y-m-d');$from30=date('Y-m-d',strtotime('-29 days'));
$kmToday=scalar_float('SELECT COALESCE(SUM(distance_m),0) FROM company_daily_km WHERE company_id=? AND vehicle_id=? AND day_key=?',[$companyId,$vehicleId,$today])/1000.0;
$km30=scalar_float('SELECT COALESCE(SUM(distance_m),0) FROM company_daily_km WHERE company_id=? AND vehicle_id=? AND day_key>=?',[$companyId,$vehicleId,$from30])/1000.0;
$kmTotal=scalar_float('SELECT COALESCE(SUM(distance_m),0) FROM company_daily_km WHERE company_id=? AND vehicle_id=?',[$companyId,$vehicleId])/1000.0;
$currentKm=max(0.0,(float)($vehicle['odometer_km']??0))+$kmTotal;

$presence=null;
try{
    $q=db()->prepare('SELECT lat,lon,speed_kmh,heading,last_seen_at FROM company_presence WHERE company_id=? AND vehicle_id=? ORDER BY last_seen_at DESC LIMIT 1');
    $q->execute([$companyId,$vehicleId]);$r=$q->fetch();
    if($r){$last=(string)($r['last_seen_at']??'');$age=$last!==''?max(0,time()-(strtotime($last)?:0)):PHP_INT_MAX;$presence=['online'=>$age<=120,'age_s'=>$age===PHP_INT_MAX?null:$age,'lat'=>(float)$r['lat'],'lon'=>(float)$r['lon'],'speed_kmh'=>(float)$r['speed_kmh'],'heading'=>(float)$r['heading'],'last_seen_at'=>$last];}
}catch(Throwable $ignored){}

$fuel=['entries'=>0,'liters'=>0.0,'total_value'=>0.0,'cost_per_km'=>0.0];$fuelRows=[];
try{
    $q=db()->prepare("SELECT f.id,f.liters,f.total_value,f.station,f.filled_at,u.name AS driver_name FROM company_fuel_entries f JOIN users u ON u.id=f.user_id WHERE f.company_id=? AND f.vehicle_id=? AND f.filled_at>=? ORDER BY f.filled_at DESC LIMIT 8");
    $q->execute([$companyId,$vehicleId,date('Y-m-d 00:00:00',strtotime('-29 days'))]);$rows=$q->fetchAll()?:[];$liters=0.0;$value=0.0;
    foreach($rows as $r){$l=(float)$r['liters'];$val=(float)$r['total_value'];$liters+=$l;$value+=$val;$fuelRows[]=['id'=>(int)$r['id'],'liters'=>round($l,2),'total_value'=>round($val,2),'station'=>(string)($r['station']??''),'filled_at'=>(string)$r['filled_at'],'driver_name'=>(string)($r['driver_name']??'')];}
    $fuel=['entries'=>count($rows),'liters'=>round($liters,2),'total_value'=>round($value,2),'cost_per_km'=>$km30>0?round($value/$km30,3):0.0];
}catch(Throwable $ignored){}

$maintenance=['overdue'=>0,'soon'=>0,'ok'=>0];$maintenanceRows=[];
try{
    $q=db()->prepare("SELECT id,title,next_due_km,next_due_date FROM company_maintenance_tasks WHERE company_id=? AND vehicle_id=? AND active=1 ORDER BY COALESCE(next_due_date,'9999-12-31'),COALESCE(next_due_km,999999999) LIMIT 12");
    $q->execute([$companyId,$vehicleId]);$rows=$q->fetchAll()?:[];
    foreach($rows as $r){$nextKm=$r['next_due_km']===null?null:(float)$r['next_due_km'];$nextDate=$r['next_due_date']===null?null:(string)$r['next_due_date'];$kmRemain=$nextKm===null?null:$nextKm-$currentKm;$daysRemain=null;if($nextDate){$t=strtotime($nextDate);if($t!==false)$daysRemain=(int)floor(($t-strtotime($today))/86400);} $over=($kmRemain!==null&&$kmRemain<=0)||($daysRemain!==null&&$daysRemain<0);$soon=!$over&&(($kmRemain!==null&&$kmRemain<=1000)||($daysRemain!==null&&$daysRemain<=30));$status=$over?'VENCIDA':($soon?'PRÓXIMA':'EM DIA');if($over)$maintenance['overdue']++;elseif($soon)$maintenance['soon']++;else$maintenance['ok']++;$maintenanceRows[]=['id'=>(int)$r['id'],'title'=>(string)$r['title'],'status'=>$status,'next_due_km'=>$nextKm,'next_due_date'=>$nextDate,'km_remaining'=>$kmRemain===null?null:round($kmRemain,1),'days_remaining'=>$daysRemain];}
}catch(Throwable $ignored){}

$incidents=['open'=>0,'high'=>0];$incidentRows=[];
try{
    $q=db()->prepare("SELECT id,title,severity,status,created_at,resolved_at FROM company_incidents WHERE company_id=? AND vehicle_id=? ORDER BY status='open' DESC,created_at DESC LIMIT 10");
    $q->execute([$companyId,$vehicleId]);$rows=$q->fetchAll()?:[];
    foreach($rows as $r){$open=(string)$r['status']==='open';if($open){$incidents['open']++;if((string)$r['severity']==='high')$incidents['high']++;}$incidentRows[]=['id'=>(int)$r['id'],'title'=>(string)$r['title'],'severity'=>(string)$r['severity'],'status'=>(string)$r['status'],'created_at'=>(string)$r['created_at'],'resolved_at'=>(string)($r['resolved_at']??'')];}
}catch(Throwable $ignored){}

$journeys=[];
try{
    $q=db()->prepare("SELECT j.id,j.started_at,j.ended_at,j.distance_m,j.status,u.name AS driver_name FROM company_journeys j JOIN users u ON u.id=j.user_id WHERE j.company_id=? AND j.vehicle_id=? ORDER BY j.started_at DESC,j.id DESC LIMIT 8");
    $q->execute([$companyId,$vehicleId]);foreach($q->fetchAll()?:[] as $r){$start=strtotime((string)$r['started_at']);$end=(string)($r['ended_at']??'');$endTs=$end!==''?strtotime($end):time();$duration=($start&&$endTs)?max(0,$endTs-$start):0;$journeys[]=['id'=>(int)$r['id'],'started_at'=>(string)$r['started_at'],'ended_at'=>$end,'distance_km'=>round(((float)$r['distance_m'])/1000.0,1),'duration_s'=>$duration,'status'=>(string)$r['status'],'driver_name'=>(string)($r['driver_name']??'')];}
}catch(Throwable $ignored){}

json_response(['ok'=>true,'company'=>['id'=>$companyId,'name'=>(string)$company['name']],'vehicle'=>[
    'id'=>(int)$vehicle['id'],'plate'=>(string)$vehicle['plate'],'nickname'=>(string)$vehicle['nickname'],'model'=>(string)$vehicle['model'],'year'=>(int)($vehicle['year']??0),'odometer_reference_km'=>(float)($vehicle['odometer_km']??0),'estimated_km'=>round($currentKm,1),'label'=>vehicle_label($vehicle)
],'driver'=>$driver,'presence'=>$presence,'summary'=>['km_today'=>round($kmToday,1),'km_30d'=>round($km30,1),'fuel_30d'=>$fuel,'maintenance'=>$maintenance,'incidents'=>$incidents],'journeys'=>$journeys,'fuel_entries'=>$fuelRows,'maintenance_tasks'=>$maintenanceRows,'incidents'=>$incidentRows]);
