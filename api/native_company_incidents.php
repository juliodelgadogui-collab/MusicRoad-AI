<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';

header('Content-Type: application/json; charset=utf-8');
$data=input_json();
$user=native_require_json_user($data);
$userId=(int)($user['id']??0);
$action=strtolower(trim((string)($_GET['action']??'list')));
$pdo=db();
$mysql=(string)$pdo->getAttribute(PDO::ATTR_DRIVER_NAME)==='mysql';

if($mysql){
    $pdo->exec("CREATE TABLE IF NOT EXISTS company_incidents (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        company_id BIGINT NOT NULL,
        user_id BIGINT NOT NULL,
        vehicle_id BIGINT NULL,
        journey_id BIGINT NULL,
        incident_type VARCHAR(32) NOT NULL,
        severity VARCHAR(16) NOT NULL DEFAULT 'medium',
        title VARCHAR(120) NOT NULL,
        notes VARCHAR(1000) NOT NULL DEFAULT '',
        lat DOUBLE NULL,
        lon DOUBLE NULL,
        status VARCHAR(20) NOT NULL DEFAULT 'open',
        created_at DATETIME NOT NULL,
        resolved_at DATETIME NULL,
        resolved_by BIGINT NULL,
        KEY idx_company_incidents_open(company_id,status,created_at),
        KEY idx_company_incidents_user(company_id,user_id,created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
}else{
    $pdo->exec("CREATE TABLE IF NOT EXISTS company_incidents (
        id INTEGER PRIMARY KEY AUTOINCREMENT,company_id INTEGER NOT NULL,user_id INTEGER NOT NULL,
        vehicle_id INTEGER,journey_id INTEGER,incident_type TEXT NOT NULL,severity TEXT NOT NULL DEFAULT 'medium',
        title TEXT NOT NULL,notes TEXT NOT NULL DEFAULT '',lat REAL,lon REAL,status TEXT NOT NULL DEFAULT 'open',
        created_at TEXT NOT NULL,resolved_at TEXT,resolved_by INTEGER
    )");
}

$m=$pdo->prepare("SELECT c.id,c.name,m.member_role FROM company_members m JOIN companies c ON c.id=m.company_id WHERE m.user_id=? AND m.status='active' AND c.status='active' ORDER BY m.id ASC LIMIT 1");
$m->execute([$userId]);$company=$m->fetch();
if(!$company)json_response(['ok'=>false,'error'=>'Esta conta não está vinculada a uma empresa.'],403);
$companyId=(int)$company['id'];
$role=strtolower((string)($company['member_role']??''));
$manager=in_array($role,['owner','admin','manager'],true);

function company_incident_title(string $type): string {
    $map=['breakdown'=>'Pane mecânica','tire'=>'Pneu / roda','accident'=>'Acidente','delay'=>'Atraso','load'=>'Carga','road'=>'Problema na estrada','other'=>'Outra ocorrência'];
    return $map[$type]??'Ocorrência';
}
function company_incident_short(string $value,int $max): string {
    $value=trim($value);return function_exists('mb_substr')?mb_substr($value,0,$max):substr($value,0,$max);
}

if($action==='add'){
    $type=strtolower(trim((string)($data['type']??'other')));
    if(!in_array($type,['breakdown','tire','accident','delay','load','road','other'],true))$type='other';
    $severity=strtolower(trim((string)($data['severity']??'medium')));
    if(!in_array($severity,['low','medium','high'],true))$severity='medium';
    $notes=company_incident_short((string)($data['notes']??''),1000);

    $vehicleId=0;$journeyId=0;$lat=null;$lon=null;
    $p=$pdo->prepare('SELECT vehicle_id,journey_id,lat,lon,last_seen_at FROM company_presence WHERE company_id=? AND user_id=? LIMIT 1');
    $p->execute([$companyId,$userId]);$presence=$p->fetch();
    if($presence){
        $lastTs=strtotime((string)($presence['last_seen_at']??''))?:0;
        if($lastTs>0 && time()-$lastTs<=600){
            $vehicleId=(int)($presence['vehicle_id']??0);$journeyId=(int)($presence['journey_id']??0);
            $lat=is_numeric($presence['lat']??null)?(float)$presence['lat']:null;$lon=is_numeric($presence['lon']??null)?(float)$presence['lon']:null;
        }
    }
    if($vehicleId<=0){
        $a=$pdo->prepare('SELECT vehicle_id FROM company_driver_vehicle WHERE company_id=? AND user_id=? AND active=1 ORDER BY id DESC LIMIT 1');
        $a->execute([$companyId,$userId]);$vehicleId=(int)($a->fetchColumn()?:0);
    }
    if(!$manager && $vehicleId<=0)json_response(['ok'=>false,'error'=>'A empresa precisa atribuir um veículo antes de registrar uma ocorrência.'],422);

    $now=date('Y-m-d H:i:s');$title=company_incident_title($type);
    $i=$pdo->prepare("INSERT INTO company_incidents (company_id,user_id,vehicle_id,journey_id,incident_type,severity,title,notes,lat,lon,status,created_at,resolved_at,resolved_by) VALUES (?,?,?,?,?,?,?,?,?,?,'open',?,NULL,NULL)");
    $i->execute([$companyId,$userId,$vehicleId?:null,$journeyId?:null,$type,$severity,$title,$notes,$lat,$lon,$now]);
    $id=(int)$pdo->lastInsertId();
    audit_log('company.incident.add',['company_id'=>$companyId,'incident_id'=>$id,'type'=>$type,'severity'=>$severity,'user_id'=>$userId]);
    json_response(['ok'=>true,'incident_id'=>$id]);
}

if($action==='resolve'){
    if(!$manager)json_response(['ok'=>false,'error'=>'Somente a gestão pode encerrar uma ocorrência.'],403);
    $id=(int)($data['id']??0);if($id<=0)json_response(['ok'=>false,'error'=>'Ocorrência inválida.'],422);
    $u=$pdo->prepare("UPDATE company_incidents SET status='resolved',resolved_at=?,resolved_by=? WHERE id=? AND company_id=? AND status='open'");
    $u->execute([date('Y-m-d H:i:s'),$userId,$id,$companyId]);
    if($u->rowCount()===0)json_response(['ok'=>false,'error'=>'Ocorrência não encontrada ou já encerrada.'],404);
    audit_log('company.incident.resolve',['company_id'=>$companyId,'incident_id'=>$id,'user_id'=>$userId]);
    json_response(['ok'=>true]);
}

if($action==='list'){
    $where=$manager?'i.company_id=?':'i.company_id=? AND i.user_id=?';
    $args=$manager?[$companyId]:[$companyId,$userId];
    $sql="SELECT i.id,i.user_id,i.vehicle_id,i.journey_id,i.incident_type,i.severity,i.title,i.notes,i.lat,i.lon,i.status,i.created_at,i.resolved_at,
                 u.name AS driver_name,u.username,v.plate,v.nickname,v.model
          FROM company_incidents i
          JOIN users u ON u.id=i.user_id
          LEFT JOIN company_vehicles v ON v.id=i.vehicle_id AND v.company_id=i.company_id
          WHERE $where ORDER BY i.status='open' DESC,i.created_at DESC LIMIT 120";
    $s=$pdo->prepare($sql);$s->execute($args);$rows=$s->fetchAll()?:[];$out=[];$open=0;$high=0;
    foreach($rows as $r){
        $isOpen=(string)$r['status']==='open';if($isOpen){$open++;if((string)$r['severity']==='high')$high++;}
        $vehicle='';if((int)($r['vehicle_id']??0)>0){$vehicle=trim((string)($r['nickname']??''));if($vehicle==='')$vehicle=trim((string)($r['plate']??''));elseif(trim((string)($r['plate']??''))!=='')$vehicle.=' · '.trim((string)$r['plate']);}
        $out[]=['id'=>(int)$r['id'],'user_id'=>(int)$r['user_id'],'vehicle_id'=>(int)($r['vehicle_id']??0),'journey_id'=>(int)($r['journey_id']??0),
            'type'=>(string)$r['incident_type'],'severity'=>(string)$r['severity'],'title'=>(string)$r['title'],'notes'=>(string)$r['notes'],
            'lat'=>$r['lat']===null?null:(float)$r['lat'],'lon'=>$r['lon']===null?null:(float)$r['lon'],'status'=>(string)$r['status'],
            'created_at'=>(string)$r['created_at'],'resolved_at'=>(string)($r['resolved_at']??''),'driver_name'=>(string)($r['driver_name']??'Motorista'),
            'username'=>(string)($r['username']??''),'vehicle_label'=>$vehicle];
    }
    json_response(['ok'=>true,'company'=>['id'=>$companyId,'name'=>(string)$company['name'],'role'=>$role],'summary'=>['open'=>$open,'high'=>$high],'incidents'=>$out]);
}

json_response(['ok'=>false,'error'=>'Ação inválida.'],400);
