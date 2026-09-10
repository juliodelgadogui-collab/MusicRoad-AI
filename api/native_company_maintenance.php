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
    db()->exec("CREATE TABLE IF NOT EXISTS company_maintenance_tasks (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        company_id BIGINT NOT NULL,
        vehicle_id BIGINT NOT NULL,
        title VARCHAR(140) NOT NULL,
        interval_km DOUBLE NOT NULL DEFAULT 0,
        interval_days INT NOT NULL DEFAULT 0,
        next_due_km DOUBLE NULL,
        next_due_date DATE NULL,
        last_done_km DOUBLE NULL,
        last_done_at DATETIME NULL,
        notes VARCHAR(500) NOT NULL DEFAULT '',
        active TINYINT NOT NULL DEFAULT 1,
        created_at DATETIME NOT NULL,
        updated_at DATETIME NOT NULL,
        KEY idx_company_maintenance_company(company_id,active),
        KEY idx_company_maintenance_vehicle(company_id,vehicle_id,active)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
} else {
    db()->exec("CREATE TABLE IF NOT EXISTS company_maintenance_tasks (
        id INTEGER PRIMARY KEY AUTOINCREMENT,company_id INTEGER NOT NULL,vehicle_id INTEGER NOT NULL,
        title TEXT NOT NULL,interval_km REAL NOT NULL DEFAULT 0,interval_days INTEGER NOT NULL DEFAULT 0,
        next_due_km REAL,next_due_date TEXT,last_done_km REAL,last_done_at TEXT,notes TEXT NOT NULL DEFAULT '',
        active INTEGER NOT NULL DEFAULT 1,created_at TEXT NOT NULL,updated_at TEXT NOT NULL
    )");
}

$m = db()->prepare("SELECT c.id,c.name,m.member_role
    FROM company_members m JOIN companies c ON c.id=m.company_id
    WHERE m.user_id=? AND m.status='active' AND c.status='active'
    ORDER BY m.id ASC LIMIT 1");
$m->execute([$userId]);
$company = $m->fetch();
if (!$company) json_response(['ok'=>false,'error'=>'Esta conta não está vinculada a uma empresa.'],403);
$role = strtolower((string)($company['member_role'] ?? ''));
if (!in_array($role,['owner','admin','manager'],true)) json_response(['ok'=>false,'error'=>'Sua conta não tem permissão para administrar manutenção.'],403);
$companyId = (int)$company['id'];

function company_maintenance_current_km(int $companyId,int $vehicleId): float {
    $q = db()->prepare("SELECT v.odometer_km,
        COALESCE((SELECT SUM(k.distance_m) FROM company_daily_km k WHERE k.company_id=v.company_id AND k.vehicle_id=v.id),0) AS tracked_m
        FROM company_vehicles v WHERE v.company_id=? AND v.id=? AND v.status='active' LIMIT 1");
    $q->execute([$companyId,$vehicleId]);
    $v = $q->fetch();
    if (!$v) return NAN;
    return max(0.0,(float)($v['odometer_km'] ?? 0)) + max(0.0,(float)($v['tracked_m'] ?? 0))/1000.0;
}
function company_maintenance_status(float $currentKm,$nextKm,?string $nextDate): array {
    $today = strtotime(date('Y-m-d')) ?: time();
    $kmRemaining = $nextKm === null ? null : ((float)$nextKm - $currentKm);
    $daysRemaining = null;
    if ($nextDate !== null && $nextDate !== '') {
        $due = strtotime($nextDate);
        if ($due !== false) $daysRemaining = (int)floor(($due-$today)/86400);
    }
    $overdue = ($kmRemaining !== null && $kmRemaining <= 0) || ($daysRemaining !== null && $daysRemaining < 0);
    $soon = !$overdue && (($kmRemaining !== null && $kmRemaining <= 1000) || ($daysRemaining !== null && $daysRemaining <= 30));
    return [
        'status'=>$overdue?'VENCIDA':($soon?'PRÓXIMA':'EM DIA'),
        'km_remaining'=>$kmRemaining === null ? null : round($kmRemaining,1),
        'days_remaining'=>$daysRemaining,
    ];
}
function company_maintenance_vehicle(int $companyId,int $vehicleId): array {
    $q=db()->prepare("SELECT id,plate,nickname,model,odometer_km FROM company_vehicles WHERE company_id=? AND id=? AND status='active' LIMIT 1");
    $q->execute([$companyId,$vehicleId]);$v=$q->fetch();
    if(!$v)json_response(['ok'=>false,'error'=>'Veículo não encontrado.'],404);
    return $v;
}

if ($action === 'list') {
    $sql = "SELECT t.*,v.plate,v.nickname,v.model,v.odometer_km,
                   COALESCE((SELECT SUM(k.distance_m) FROM company_daily_km k WHERE k.company_id=t.company_id AND k.vehicle_id=t.vehicle_id),0) AS tracked_m
            FROM company_maintenance_tasks t
            JOIN company_vehicles v ON v.id=t.vehicle_id AND v.company_id=t.company_id
            WHERE t.company_id=? AND t.active=1 AND v.status='active'
            ORDER BY COALESCE(t.next_due_date,'9999-12-31'),COALESCE(t.next_due_km,999999999),t.title";
    $q=db()->prepare($sql);$q->execute([$companyId]);$rows=$q->fetchAll()?:[];$out=[];$counts=['VENCIDA'=>0,'PRÓXIMA'=>0,'EM DIA'=>0];
    foreach($rows as $r){
        $current=max(0.0,(float)($r['odometer_km']??0))+max(0.0,(float)($r['tracked_m']??0))/1000.0;
        $nextKm=$r['next_due_km']===null?null:(float)$r['next_due_km'];$nextDate=$r['next_due_date']===null?null:(string)$r['next_due_date'];$state=company_maintenance_status($current,$nextKm,$nextDate);$counts[$state['status']]++;
        $nick=trim((string)($r['nickname']??''));$plate=trim((string)($r['plate']??''));$model=trim((string)($r['model']??''));$vehicleLabel=$nick!==''?$nick.($plate!==''?' · '.$plate:''):($plate!==''?$plate:($model!==''?$model:'Veículo'));
        $out[]=[
            'id'=>(int)$r['id'],'vehicle_id'=>(int)$r['vehicle_id'],'vehicle_label'=>$vehicleLabel,'title'=>(string)$r['title'],
            'interval_km'=>(float)$r['interval_km'],'interval_days'=>(int)$r['interval_days'],
            'current_km'=>round($current,1),'next_due_km'=>$nextKm,'next_due_date'=>$nextDate,
            'last_done_km'=>$r['last_done_km']===null?null:(float)$r['last_done_km'],'last_done_at'=>$r['last_done_at']===null?null:(string)$r['last_done_at'],
            'notes'=>(string)($r['notes']??''),'status'=>$state['status'],'km_remaining'=>$state['km_remaining'],'days_remaining'=>$state['days_remaining'],
        ];
    }
    json_response(['ok'=>true,'company'=>['id'=>$companyId,'name'=>(string)$company['name']],'summary'=>['total'=>count($out),'overdue'=>$counts['VENCIDA'],'soon'=>$counts['PRÓXIMA'],'ok'=>$counts['EM DIA']],'tasks'=>$out]);
}

if ($action === 'save') {
    $id=max(0,(int)($data['id']??0));$vehicleId=(int)($data['vehicle_id']??0);$title=trim((string)($data['title']??''));
    $intervalKm=max(0.0,min(500000.0,(float)($data['interval_km']??0)));$intervalDays=max(0,min(3650,(int)($data['interval_days']??0)));$notes=trim((string)($data['notes']??''));
    $title=function_exists('mb_substr')?mb_substr($title,0,140):substr($title,0,140);$notes=function_exists('mb_substr')?mb_substr($notes,0,500):substr($notes,0,500);
    if($vehicleId<=0||$title==='')json_response(['ok'=>false,'error'=>'Informe o veículo e o serviço.'],422);
    if($intervalKm<=0&&$intervalDays<=0)json_response(['ok'=>false,'error'=>'Informe um intervalo em km ou em dias.'],422);
    company_maintenance_vehicle($companyId,$vehicleId);$current=company_maintenance_current_km($companyId,$vehicleId);if(!is_finite($current))json_response(['ok'=>false,'error'=>'Não consegui calcular a quilometragem do veículo.'],422);
    $nextKm=$intervalKm>0?$current+$intervalKm:null;$nextDate=$intervalDays>0?date('Y-m-d',strtotime('+'.$intervalDays.' days')):null;$now=date('Y-m-d H:i:s');
    if($id>0){
        $u=db()->prepare('UPDATE company_maintenance_tasks SET vehicle_id=?,title=?,interval_km=?,interval_days=?,next_due_km=?,next_due_date=?,notes=?,updated_at=? WHERE id=? AND company_id=? AND active=1');
        $u->execute([$vehicleId,$title,$intervalKm,$intervalDays,$nextKm,$nextDate,$notes,$now,$id,$companyId]);if($u->rowCount()===0)json_response(['ok'=>false,'error'=>'Manutenção não encontrada.'],404);
    }else{
        $i=db()->prepare('INSERT INTO company_maintenance_tasks (company_id,vehicle_id,title,interval_km,interval_days,next_due_km,next_due_date,last_done_km,last_done_at,notes,active,created_at,updated_at) VALUES (?,?,?,?,?,?,?,NULL,NULL,?,1,?,?)');
        $i->execute([$companyId,$vehicleId,$title,$intervalKm,$intervalDays,$nextKm,$nextDate,$notes,$now,$now]);$id=(int)db()->lastInsertId();
    }
    audit_log('company.maintenance.save',['company_id'=>$companyId,'task_id'=>$id,'vehicle_id'=>$vehicleId,'user_id'=>$userId]);
    json_response(['ok'=>true,'task_id'=>$id]);
}

if ($action === 'complete') {
    $id=(int)($data['id']??0);if($id<=0)json_response(['ok'=>false,'error'=>'Manutenção inválida.'],422);
    $q=db()->prepare('SELECT * FROM company_maintenance_tasks WHERE id=? AND company_id=? AND active=1 LIMIT 1');$q->execute([$id,$companyId]);$t=$q->fetch();if(!$t)json_response(['ok'=>false,'error'=>'Manutenção não encontrada.'],404);
    $vehicleId=(int)$t['vehicle_id'];$current=company_maintenance_current_km($companyId,$vehicleId);if(!is_finite($current))json_response(['ok'=>false,'error'=>'Não consegui calcular a quilometragem do veículo.'],422);
    $intervalKm=max(0.0,(float)$t['interval_km']);$intervalDays=max(0,(int)$t['interval_days']);$nextKm=$intervalKm>0?$current+$intervalKm:null;$nextDate=$intervalDays>0?date('Y-m-d',strtotime('+'.$intervalDays.' days')):null;$now=date('Y-m-d H:i:s');
    $u=db()->prepare('UPDATE company_maintenance_tasks SET last_done_km=?,last_done_at=?,next_due_km=?,next_due_date=?,updated_at=? WHERE id=? AND company_id=?');$u->execute([$current,$now,$nextKm,$nextDate,$now,$id,$companyId]);
    audit_log('company.maintenance.complete',['company_id'=>$companyId,'task_id'=>$id,'vehicle_id'=>$vehicleId,'km'=>round($current,1),'user_id'=>$userId]);
    json_response(['ok'=>true,'task_id'=>$id,'completed_km'=>round($current,1),'next_due_km'=>$nextKm,'next_due_date'=>$nextDate]);
}

json_response(['ok'=>false,'error'=>'Ação inválida.'],400);
