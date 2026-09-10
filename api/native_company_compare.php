<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';

header('Content-Type: application/json; charset=utf-8');
$data=input_json();
$user=native_require_json_user($data);
$userId=(int)($user['id']??0);
$days=(int)($data['days']??30);if(!in_array($days,[7,30],true))$days=30;

$m=db()->prepare("SELECT c.id,c.name,m.member_role FROM company_members m JOIN companies c ON c.id=m.company_id WHERE m.user_id=? AND m.status='active' AND c.status='active' ORDER BY m.id ASC LIMIT 1");
$m->execute([$userId]);$company=$m->fetch();
if(!$company)json_response(['ok'=>false,'error'=>'Esta conta não está vinculada a uma empresa.'],403);
$role=strtolower((string)($company['member_role']??''));
if(!in_array($role,['owner','admin','manager'],true))json_response(['ok'=>false,'error'=>'Sua conta não pode comparar a frota.'],403);
$companyId=(int)$company['id'];
$from=date('Y-m-d',strtotime('-'.($days-1).' days'));
$fromDateTime=$from.' 00:00:00';

$vehicles=[];
$q=db()->prepare("SELECT id,plate,nickname,model FROM company_vehicles WHERE company_id=? AND status='active' ORDER BY nickname,plate");$q->execute([$companyId]);
foreach($q->fetchAll()?:[] as $v){
    $vid=(int)$v['id'];
    $km=0.0;$fuel=0.0;$liters=0.0;$inc=0;$high=0;$maintenance=0;
    try{$s=db()->prepare('SELECT COALESCE(SUM(distance_m),0) FROM company_daily_km WHERE company_id=? AND vehicle_id=? AND day_key>=?');$s->execute([$companyId,$vid,$from]);$km=((float)($s->fetchColumn()?:0))/1000.0;}catch(Throwable $ignored){}
    try{$s=db()->prepare('SELECT COALESCE(SUM(total_value),0),COALESCE(SUM(liters),0) FROM company_fuel_entries WHERE company_id=? AND vehicle_id=? AND filled_at>=?');$s->execute([$companyId,$vid,$fromDateTime]);$r=$s->fetch(PDO::FETCH_NUM);$fuel=(float)($r[0]??0);$liters=(float)($r[1]??0);}catch(Throwable $ignored){}
    try{$s=db()->prepare("SELECT COUNT(*),COALESCE(SUM(CASE WHEN severity='high' THEN 1 ELSE 0 END),0) FROM company_incidents WHERE company_id=? AND vehicle_id=? AND status='open'");$s->execute([$companyId,$vid]);$r=$s->fetch(PDO::FETCH_NUM);$inc=(int)($r[0]??0);$high=(int)($r[1]??0);}catch(Throwable $ignored){}
    try{
        $s=db()->prepare('SELECT next_due_km,next_due_date FROM company_maintenance_tasks WHERE company_id=? AND vehicle_id=? AND active=1');$s->execute([$companyId,$vid]);
        $odo=0.0;try{$o=db()->prepare('SELECT odometer_km FROM company_vehicles WHERE company_id=? AND id=?');$o->execute([$companyId,$vid]);$odo=(float)($o->fetchColumn()?:0);$all=db()->prepare('SELECT COALESCE(SUM(distance_m),0) FROM company_daily_km WHERE company_id=? AND vehicle_id=?');$all->execute([$companyId,$vid]);$odo+=((float)($all->fetchColumn()?:0))/1000.0;}catch(Throwable $ignored2){}
        foreach($s->fetchAll()?:[] as $t){$over=false;if($t['next_due_km']!==null&&((float)$t['next_due_km']-$odo)<=0)$over=true;if(!$over&&$t['next_due_date']!==null&&$t['next_due_date']!==''&&strtotime((string)$t['next_due_date'])<strtotime(date('Y-m-d')))$over=true;if($over)$maintenance++;}
    }catch(Throwable $ignored){}
    $n=trim((string)($v['nickname']??''));$p=trim((string)($v['plate']??''));$model=trim((string)($v['model']??''));$label=$n!==''?$n.($p!==''?' · '.$p:''):($p!==''?$p:($model!==''?$model:'Veículo'));
    $vehicles[]=['vehicle_id'=>$vid,'label'=>$label,'km'=>round($km,1),'fuel_value'=>round($fuel,2),'liters'=>round($liters,2),'cost_per_km'=>$km>0?round($fuel/$km,3):0.0,'open_incidents'=>$inc,'high_incidents'=>$high,'overdue_maintenance'=>$maintenance];
}
usort($vehicles,fn($a,$b)=>$b['km']<=>$a['km']);

$drivers=[];
$q=db()->prepare("SELECT m.user_id,u.name,u.username FROM company_members m JOIN users u ON u.id=m.user_id WHERE m.company_id=? AND m.status='active' AND m.member_role='driver' ORDER BY u.name,u.username");$q->execute([$companyId]);
foreach($q->fetchAll()?:[] as $d){
    $uid=(int)$d['user_id'];$km=0.0;$fuel=0.0;$inc=0;
    try{$s=db()->prepare('SELECT COALESCE(SUM(distance_m),0) FROM company_daily_km WHERE company_id=? AND user_id=? AND day_key>=?');$s->execute([$companyId,$uid,$from]);$km=((float)($s->fetchColumn()?:0))/1000.0;}catch(Throwable $ignored){}
    try{$s=db()->prepare('SELECT COALESCE(SUM(total_value),0) FROM company_fuel_entries WHERE company_id=? AND user_id=? AND filled_at>=?');$s->execute([$companyId,$uid,$fromDateTime]);$fuel=(float)($s->fetchColumn()?:0);}catch(Throwable $ignored){}
    try{$s=db()->prepare("SELECT COUNT(*) FROM company_incidents WHERE company_id=? AND user_id=? AND created_at>=?");$s->execute([$companyId,$uid,$fromDateTime]);$inc=(int)($s->fetchColumn()?:0);}catch(Throwable $ignored){}
    $drivers[]=['user_id'=>$uid,'name'=>(string)($d['name']??'Motorista'),'username'=>(string)($d['username']??''),'km'=>round($km,1),'fuel_value'=>round($fuel,2),'incidents'=>$inc];
}
usort($drivers,fn($a,$b)=>$b['km']<=>$a['km']);

json_response(['ok'=>true,'company'=>['id'=>$companyId,'name'=>(string)$company['name']],'period'=>['days'=>$days,'from'=>$from,'to'=>date('Y-m-d')],'vehicles'=>$vehicles,'drivers'=>$drivers]);
