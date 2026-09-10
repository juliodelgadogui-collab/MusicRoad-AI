<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';

header('Content-Type: application/json; charset=utf-8');
company_ensure_schema();
$action = strtolower(trim((string)($_GET['action'] ?? '')));
$data = input_json();
$user = native_require_json_user($data);
$context = company_context_for_user($user, true);

if ($action === 'context') {
    json_response(['ok'=>true,'company'=>$context]);
}

if (!$context) json_response(['ok'=>false,'error'=>'Esta conta não está vinculada a uma empresa.'],403);
$companyId = (int)$context['id'];
$userId = (int)($user['id'] ?? 0);

if ($action === 'dashboard') {
    company_require_manager($context);
    $day = date('Y-m-d');
    $activeSince = date('Y-m-d H:i:s', time() - 120);
    $vehicles = company_scalar('SELECT COUNT(*) FROM company_vehicles WHERE company_id=? AND status=?',[$companyId,'active']);
    $drivers = company_scalar("SELECT COUNT(*) FROM company_members WHERE company_id=? AND status='active' AND member_role='driver'",[$companyId]);
    $active = company_scalar('SELECT COUNT(*) FROM company_presence WHERE company_id=? AND last_seen_at>=?',[$companyId,$activeSince]);
    $km = company_scalar('SELECT COALESCE(SUM(distance_m),0) FROM company_daily_km WHERE company_id=? AND day_key=?',[$companyId,$day]) / 1000.0;
    json_response(['ok'=>true,'company'=>$context,'summary'=>[
        'vehicles'=>$vehicles,'drivers'=>$drivers,'active_now'=>$active,'km_today'=>round($km,1),'day'=>$day,
    ]]);
}

if ($action === 'vehicles') {
    company_require_manager($context);
    $s = db()->prepare("SELECT id,plate,nickname,model,year,odometer_km,status,created_at,updated_at FROM company_vehicles WHERE company_id=? ORDER BY status='active' DESC,nickname,plate");
    $s->execute([$companyId]);
    $rows = $s->fetchAll() ?: [];
    foreach ($rows as &$r) {
        $r['id']=(int)$r['id']; $r['year']=(int)($r['year'] ?? 0); $r['odometer_km']=(float)($r['odometer_km'] ?? 0);
    }
    unset($r);
    json_response(['ok'=>true,'vehicles'=>$rows]);
}

if ($action === 'save_vehicle') {
    company_require_manager($context);
    $id = max(0,(int)($data['id'] ?? 0));
    $plate = strtoupper(trim((string)($data['plate'] ?? '')));
    $plate = preg_replace('/[^A-Z0-9-]/','',$plate) ?: '';
    $nickname = company_short((string)($data['nickname'] ?? ''),80);
    $model = company_short((string)($data['model'] ?? ''),120);
    $year = max(0,min(2100,(int)($data['year'] ?? 0)));
    $odometer = max(0.0,(float)($data['odometer_km'] ?? 0));
    if (strlen($plate) < 5) json_response(['ok'=>false,'error'=>'Informe uma placa válida.'],422);
    $dup = db()->prepare('SELECT id FROM company_vehicles WHERE company_id=? AND plate=? AND id<>? LIMIT 1');
    $dup->execute([$companyId,$plate,$id]);
    if ($dup->fetchColumn()) json_response(['ok'=>false,'error'=>'Esta placa já está cadastrada na empresa.'],409);
    $now = date('Y-m-d H:i:s');
    if ($id > 0) {
        $u = db()->prepare('UPDATE company_vehicles SET plate=?,nickname=?,model=?,year=?,odometer_km=?,updated_at=? WHERE id=? AND company_id=?');
        $u->execute([$plate,$nickname,$model,$year?:null,$odometer,$now,$id,$companyId]);
        if ($u->rowCount() === 0) json_response(['ok'=>false,'error'=>'Veículo não encontrado.'],404);
    } else {
        $i = db()->prepare("INSERT INTO company_vehicles (company_id,plate,nickname,model,year,odometer_km,status,created_at,updated_at) VALUES (?,?,?,?,?,?,'active',?,?)");
        $i->execute([$companyId,$plate,$nickname,$model,$year?:null,$odometer,$now,$now]);
        $id = (int)db()->lastInsertId();
    }
    audit_log('company.vehicle.save',['company_id'=>$companyId,'vehicle_id'=>$id,'user_id'=>$userId]);
    json_response(['ok'=>true,'vehicle_id'=>$id]);
}

if ($action === 'drivers') {
    company_require_manager($context);
    $day = date('Y-m-d');
    $sql = "SELECT u.id AS user_id,u.name,u.username,u.email,m.member_role,
                   v.id AS vehicle_id,v.plate,v.nickname,v.model,
                   p.last_seen_at,p.speed_kmh,
                   COALESCE(k.distance_m,0) AS distance_m
            FROM company_members m
            JOIN users u ON u.id=m.user_id
            LEFT JOIN company_driver_vehicle a ON a.company_id=m.company_id AND a.user_id=m.user_id AND a.active=1
            LEFT JOIN company_vehicles v ON v.id=a.vehicle_id AND v.company_id=m.company_id
            LEFT JOIN company_presence p ON p.company_id=m.company_id AND p.user_id=m.user_id
            LEFT JOIN company_daily_km k ON k.company_id=m.company_id AND k.user_id=m.user_id AND k.vehicle_id=COALESCE(v.id,0) AND k.day_key=?
            WHERE m.company_id=? AND m.status='active' AND m.member_role='driver'
            ORDER BY u.name,u.username";
    $s = db()->prepare($sql); $s->execute([$day,$companyId]); $rows=$s->fetchAll()?:[];
    $now=time(); $out=[];
    foreach($rows as $r){
        $last=(string)($r['last_seen_at']??''); $age=$last!==''?max(0,$now-(strtotime($last)?:0)):PHP_INT_MAX;
        $vehicle=null;
        if((int)($r['vehicle_id']??0)>0)$vehicle=['id'=>(int)$r['vehicle_id'],'plate'=>(string)$r['plate'],'nickname'=>(string)$r['nickname'],'model'=>(string)$r['model']];
        $out[]=['user_id'=>(int)$r['user_id'],'name'=>(string)$r['name'],'username'=>(string)$r['username'],'email'=>(string)$r['email'],'role'=>(string)$r['member_role'],'vehicle'=>$vehicle,
            'km_today'=>round(((float)$r['distance_m'])/1000.0,1),'last_seen_at'=>$last,'speed_kmh'=>(float)($r['speed_kmh']??0),'presence'=>$age<=120?'NA ESTRADA':($last!==''?'OFFLINE':'SEM JORNADA')];
    }
    json_response(['ok'=>true,'drivers'=>$out]);
}

if ($action === 'add_driver') {
    company_require_manager($context);
    $identifier = trim((string)($data['identifier'] ?? ''));
    if (strlen($identifier)<3) json_response(['ok'=>false,'error'=>'Informe usuário ou e-mail.'],422);
    $s=db()->prepare("SELECT id,name,username,email,status FROM users WHERE (username=? OR email=?) LIMIT 1");
    $s->execute([$identifier,$identifier]); $driver=$s->fetch();
    if(!$driver || (($driver['status']??'active')!=='active')) json_response(['ok'=>false,'error'=>'Conta Estrada Play não encontrada.'],404);
    $driverId=(int)$driver['id']; $now=date('Y-m-d H:i:s');
    $exists=db()->prepare('SELECT id FROM company_members WHERE company_id=? AND user_id=? LIMIT 1'); $exists->execute([$companyId,$driverId]); $memberId=(int)($exists->fetchColumn()?:0);
    if($memberId>0){$u=db()->prepare("UPDATE company_members SET member_role='driver',status='active',updated_at=? WHERE id=?");$u->execute([$now,$memberId]);}
    else{$i=db()->prepare("INSERT INTO company_members (company_id,user_id,member_role,status,created_at,updated_at) VALUES (?,?,'driver','active',?,?)");$i->execute([$companyId,$driverId,$now,$now]);}
    audit_log('company.driver.add',['company_id'=>$companyId,'driver_id'=>$driverId,'user_id'=>$userId]);
    json_response(['ok'=>true,'driver'=>['user_id'=>$driverId,'name'=>(string)$driver['name'],'username'=>(string)$driver['username']]]);
}

if ($action === 'assign') {
    company_require_manager($context);
    $driverId=(int)($data['user_id']??0); $vehicleId=(int)($data['vehicle_id']??0);
    if($driverId<=0||$vehicleId<=0) json_response(['ok'=>false,'error'=>'Motorista e veículo são obrigatórios.'],422);
    $m=db()->prepare("SELECT id FROM company_members WHERE company_id=? AND user_id=? AND status='active' LIMIT 1");$m->execute([$companyId,$driverId]);if(!$m->fetchColumn())json_response(['ok'=>false,'error'=>'Motorista não pertence a esta empresa.'],404);
    $v=db()->prepare("SELECT id FROM company_vehicles WHERE company_id=? AND id=? AND status='active' LIMIT 1");$v->execute([$companyId,$vehicleId]);if(!$v->fetchColumn())json_response(['ok'=>false,'error'=>'Veículo não pertence a esta empresa.'],404);
    db()->beginTransaction();
    try{
        $off=db()->prepare('UPDATE company_driver_vehicle SET active=0,ended_at=? WHERE company_id=? AND user_id=? AND active=1');$off->execute([date('Y-m-d H:i:s'),$companyId,$driverId]);
        $i=db()->prepare('INSERT INTO company_driver_vehicle (company_id,user_id,vehicle_id,active,assigned_at,ended_at) VALUES (?,?,?,1,?,NULL)');$i->execute([$companyId,$driverId,$vehicleId,date('Y-m-d H:i:s')]);
        db()->commit();
    }catch(Throwable $e){if(db()->inTransaction())db()->rollBack();throw $e;}
    audit_log('company.driver.assign',['company_id'=>$companyId,'driver_id'=>$driverId,'vehicle_id'=>$vehicleId,'user_id'=>$userId]);
    json_response(['ok'=>true]);
}

if ($action === 'journey_ping') {
    $vehicle = $context['active_vehicle'] ?? null;
    if (!$vehicle || (int)($vehicle['id']??0)<=0) json_response(['ok'=>true,'tracking'=>false,'reason'=>'no_vehicle']);
    $vehicleId=(int)$vehicle['id'];
    $lat=(float)($data['lat']??NAN); $lon=(float)($data['lon']??NAN); $speed=max(0.0,min(260.0,(float)($data['speed_kmh']??0))); $heading=(float)($data['heading']??-1);
    if(!is_finite($lat)||!is_finite($lon)||$lat < -34.0||$lat > 6.0||$lon < -75.0||$lon > -30.0) json_response(['ok'=>false,'error'=>'Localização inválida.'],422);
    $nowTs=time(); $now=date('Y-m-d H:i:s',$nowTs); $day=date('Y-m-d',$nowTs);
    $p=db()->prepare('SELECT * FROM company_presence WHERE company_id=? AND user_id=? LIMIT 1');$p->execute([$companyId,$userId]);$prev=$p->fetch();
    $delta=0.0; $journeyId=0; $gap=PHP_INT_MAX;
    if($prev){
        $prevTs=strtotime((string)($prev['last_seen_at']??''))?:0; $gap=$prevTs>0?max(0,$nowTs-$prevTs):PHP_INT_MAX; $journeyId=(int)($prev['journey_id']??0);
        if($gap>0&&$gap<=180){
            $candidate=company_distance_m((float)$prev['lat'],(float)$prev['lon'],$lat,$lon);
            $maxPlausible=max(300.0,$gap*75.0);
            if(is_finite($candidate)&&$candidate>=2.0&&$candidate<=$maxPlausible)$delta=$candidate;
        }
    }
    if($journeyId<=0||$gap>600){
        if($journeyId>0){$close=db()->prepare("UPDATE company_journeys SET ended_at=?,status='closed' WHERE id=? AND company_id=?");$close->execute([$now,$journeyId,$companyId]);}
        $j=db()->prepare("INSERT INTO company_journeys (company_id,user_id,vehicle_id,started_at,ended_at,distance_m,status) VALUES (?,?,?,?,NULL,0,'active')");$j->execute([$companyId,$userId,$vehicleId,$now]);$journeyId=(int)db()->lastInsertId();$delta=0.0;
    }
    if($delta>0){
        $u=db()->prepare('UPDATE company_journeys SET distance_m=distance_m+? WHERE id=? AND company_id=?');$u->execute([$delta,$journeyId,$companyId]);
        company_add_daily_km($companyId,$userId,$vehicleId,$day,$delta,$now);
    }
    company_upsert_presence($companyId,$userId,$vehicleId,$journeyId,$lat,$lon,$speed,$heading,$now);
    json_response(['ok'=>true,'tracking'=>true,'journey_id'=>$journeyId,'delta_m'=>round($delta,1)]);
}

if ($action === 'journey_stop') {
    $p=db()->prepare('SELECT journey_id FROM company_presence WHERE company_id=? AND user_id=? LIMIT 1');$p->execute([$companyId,$userId]);$journeyId=(int)($p->fetchColumn()?:0);
    if($journeyId>0){$u=db()->prepare("UPDATE company_journeys SET ended_at=?,status='closed' WHERE id=? AND company_id=?");$u->execute([date('Y-m-d H:i:s'),$journeyId,$companyId]);}
    $u=db()->prepare('UPDATE company_presence SET journey_id=NULL WHERE company_id=? AND user_id=?');$u->execute([$companyId,$userId]);
    json_response(['ok'=>true]);
}

json_response(['ok'=>false,'error'=>'Ação inválida.'],400);

function company_ensure_schema(): void
{
    static $done=false; if($done)return; $done=true; $mysql=(string)db()->getAttribute(PDO::ATTR_DRIVER_NAME)==='mysql';
    if($mysql){
        db()->exec("CREATE TABLE IF NOT EXISTS companies (id BIGINT AUTO_INCREMENT PRIMARY KEY,name VARCHAR(180) NOT NULL,owner_user_id BIGINT NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'active',created_at DATETIME NOT NULL,updated_at DATETIME NOT NULL,KEY idx_company_owner(owner_user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        db()->exec("CREATE TABLE IF NOT EXISTS company_members (id BIGINT AUTO_INCREMENT PRIMARY KEY,company_id BIGINT NOT NULL,user_id BIGINT NOT NULL,member_role VARCHAR(24) NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'active',created_at DATETIME NOT NULL,updated_at DATETIME NOT NULL,UNIQUE KEY uq_company_member(company_id,user_id),KEY idx_company_member_user(user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        db()->exec("CREATE TABLE IF NOT EXISTS company_vehicles (id BIGINT AUTO_INCREMENT PRIMARY KEY,company_id BIGINT NOT NULL,plate VARCHAR(16) NOT NULL,nickname VARCHAR(80) NOT NULL DEFAULT '',model VARCHAR(120) NOT NULL DEFAULT '',year INT NULL,odometer_km DOUBLE NOT NULL DEFAULT 0,status VARCHAR(20) NOT NULL DEFAULT 'active',created_at DATETIME NOT NULL,updated_at DATETIME NOT NULL,UNIQUE KEY uq_company_plate(company_id,plate),KEY idx_company_vehicle(company_id,status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        db()->exec("CREATE TABLE IF NOT EXISTS company_driver_vehicle (id BIGINT AUTO_INCREMENT PRIMARY KEY,company_id BIGINT NOT NULL,user_id BIGINT NOT NULL,vehicle_id BIGINT NOT NULL,active TINYINT NOT NULL DEFAULT 1,assigned_at DATETIME NOT NULL,ended_at DATETIME NULL,KEY idx_company_assignment(company_id,user_id,active),KEY idx_company_assignment_vehicle(company_id,vehicle_id,active)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        db()->exec("CREATE TABLE IF NOT EXISTS company_journeys (id BIGINT AUTO_INCREMENT PRIMARY KEY,company_id BIGINT NOT NULL,user_id BIGINT NOT NULL,vehicle_id BIGINT NOT NULL,started_at DATETIME NOT NULL,ended_at DATETIME NULL,distance_m DOUBLE NOT NULL DEFAULT 0,status VARCHAR(20) NOT NULL DEFAULT 'active',KEY idx_company_journey(company_id,started_at),KEY idx_company_journey_driver(company_id,user_id,started_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        db()->exec("CREATE TABLE IF NOT EXISTS company_daily_km (company_id BIGINT NOT NULL,user_id BIGINT NOT NULL,vehicle_id BIGINT NOT NULL,day_key DATE NOT NULL,distance_m DOUBLE NOT NULL DEFAULT 0,updated_at DATETIME NOT NULL,PRIMARY KEY(company_id,user_id,vehicle_id,day_key),KEY idx_company_daily(company_id,day_key)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        db()->exec("CREATE TABLE IF NOT EXISTS company_presence (company_id BIGINT NOT NULL,user_id BIGINT NOT NULL,vehicle_id BIGINT NOT NULL,journey_id BIGINT NULL,lat DOUBLE NOT NULL,lon DOUBLE NOT NULL,speed_kmh DOUBLE NOT NULL DEFAULT 0,heading DOUBLE NOT NULL DEFAULT -1,last_seen_at DATETIME NOT NULL,PRIMARY KEY(company_id,user_id),KEY idx_company_presence(company_id,last_seen_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
    }else{
        db()->exec("CREATE TABLE IF NOT EXISTS companies (id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,owner_user_id INTEGER NOT NULL,status TEXT NOT NULL DEFAULT 'active',created_at TEXT NOT NULL,updated_at TEXT NOT NULL)");
        db()->exec("CREATE TABLE IF NOT EXISTS company_members (id INTEGER PRIMARY KEY AUTOINCREMENT,company_id INTEGER NOT NULL,user_id INTEGER NOT NULL,member_role TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'active',created_at TEXT NOT NULL,updated_at TEXT NOT NULL,UNIQUE(company_id,user_id))");
        db()->exec("CREATE TABLE IF NOT EXISTS company_vehicles (id INTEGER PRIMARY KEY AUTOINCREMENT,company_id INTEGER NOT NULL,plate TEXT NOT NULL,nickname TEXT NOT NULL DEFAULT '',model TEXT NOT NULL DEFAULT '',year INTEGER,odometer_km REAL NOT NULL DEFAULT 0,status TEXT NOT NULL DEFAULT 'active',created_at TEXT NOT NULL,updated_at TEXT NOT NULL,UNIQUE(company_id,plate))");
        db()->exec("CREATE TABLE IF NOT EXISTS company_driver_vehicle (id INTEGER PRIMARY KEY AUTOINCREMENT,company_id INTEGER NOT NULL,user_id INTEGER NOT NULL,vehicle_id INTEGER NOT NULL,active INTEGER NOT NULL DEFAULT 1,assigned_at TEXT NOT NULL,ended_at TEXT)");
        db()->exec("CREATE TABLE IF NOT EXISTS company_journeys (id INTEGER PRIMARY KEY AUTOINCREMENT,company_id INTEGER NOT NULL,user_id INTEGER NOT NULL,vehicle_id INTEGER NOT NULL,started_at TEXT NOT NULL,ended_at TEXT,distance_m REAL NOT NULL DEFAULT 0,status TEXT NOT NULL DEFAULT 'active')");
        db()->exec("CREATE TABLE IF NOT EXISTS company_daily_km (company_id INTEGER NOT NULL,user_id INTEGER NOT NULL,vehicle_id INTEGER NOT NULL,day_key TEXT NOT NULL,distance_m REAL NOT NULL DEFAULT 0,updated_at TEXT NOT NULL,PRIMARY KEY(company_id,user_id,vehicle_id,day_key))");
        db()->exec("CREATE TABLE IF NOT EXISTS company_presence (company_id INTEGER NOT NULL,user_id INTEGER NOT NULL,vehicle_id INTEGER NOT NULL,journey_id INTEGER,lat REAL NOT NULL,lon REAL NOT NULL,speed_kmh REAL NOT NULL DEFAULT 0,heading REAL NOT NULL DEFAULT -1,last_seen_at TEXT NOT NULL,PRIMARY KEY(company_id,user_id))");
    }
}

function company_context_for_user(array $user,bool $autoProvision=false): ?array
{
    $uid=(int)($user['id']??0); if($uid<=0)return null;
    $q=db()->prepare("SELECT c.id,c.name,m.member_role FROM company_members m JOIN companies c ON c.id=m.company_id WHERE m.user_id=? AND m.status='active' AND c.status='active' ORDER BY m.id ASC LIMIT 1");$q->execute([$uid]);$row=$q->fetch();
    if(!$row && $autoProvision){
        $role=strtolower(trim((string)($user['role']??'')));
        if(in_array($role,['company','company_admin','fleet_admin'],true)){
            $now=date('Y-m-d H:i:s');$name=trim((string)($user['name']??''));if($name==='')$name='Empresa Estrada Play';
            $i=db()->prepare("INSERT INTO companies (name,owner_user_id,status,created_at,updated_at) VALUES (?,?,'active',?,?)");$i->execute([$name,$uid,$now,$now]);$cid=(int)db()->lastInsertId();
            $m=db()->prepare("INSERT INTO company_members (company_id,user_id,member_role,status,created_at,updated_at) VALUES (?,?,'owner','active',?,?)");$m->execute([$cid,$uid,$now,$now]);
            $row=['id'=>$cid,'name'=>$name,'member_role'=>'owner'];
        }
    }
    if(!$row)return null; $cid=(int)$row['id'];
    $a=db()->prepare("SELECT v.id,v.plate,v.nickname,v.model FROM company_driver_vehicle a JOIN company_vehicles v ON v.id=a.vehicle_id WHERE a.company_id=? AND a.user_id=? AND a.active=1 AND v.status='active' ORDER BY a.id DESC LIMIT 1");$a->execute([$cid,$uid]);$vehicle=$a->fetch();
    return ['id'=>$cid,'name'=>(string)$row['name'],'role'=>(string)$row['member_role'],'can_manage'=>in_array((string)$row['member_role'],['owner','admin','manager'],true),'active_vehicle'=>$vehicle?['id'=>(int)$vehicle['id'],'plate'=>(string)$vehicle['plate'],'nickname'=>(string)$vehicle['nickname'],'model'=>(string)$vehicle['model']]:null];
}

function company_require_manager(array $context): void
{
    if(empty($context['can_manage']))json_response(['ok'=>false,'error'=>'Sua conta não tem permissão para administrar a frota.'],403);
}
function company_scalar(string $sql,array $args): int { $s=db()->prepare($sql);$s->execute($args);return (int)($s->fetchColumn()?:0); }
function company_short(string $value,int $max): string { $value=trim($value);return function_exists('mb_substr')?mb_substr($value,0,$max):substr($value,0,$max); }
function company_distance_m(float $aLat,float $aLon,float $bLat,float $bLon): float { $r=6371000.0;$p1=deg2rad($aLat);$p2=deg2rad($bLat);$dp=deg2rad($bLat-$aLat);$dl=deg2rad($bLon-$aLon);$x=sin($dp/2)**2+cos($p1)*cos($p2)*sin($dl/2)**2;return 2*$r*atan2(sqrt($x),sqrt(max(0.0,1.0-$x))); }
function company_add_daily_km(int $cid,int $uid,int $vid,string $day,float $delta,string $now): void { if((string)db()->getAttribute(PDO::ATTR_DRIVER_NAME)==='mysql'){$s=db()->prepare('INSERT INTO company_daily_km (company_id,user_id,vehicle_id,day_key,distance_m,updated_at) VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE distance_m=distance_m+VALUES(distance_m),updated_at=VALUES(updated_at)');}else{$s=db()->prepare('INSERT INTO company_daily_km (company_id,user_id,vehicle_id,day_key,distance_m,updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT(company_id,user_id,vehicle_id,day_key) DO UPDATE SET distance_m=distance_m+excluded.distance_m,updated_at=excluded.updated_at');}$s->execute([$cid,$uid,$vid,$day,$delta,$now]); }
function company_upsert_presence(int $cid,int $uid,int $vid,int $jid,float $lat,float $lon,float $speed,float $heading,string $now): void { if((string)db()->getAttribute(PDO::ATTR_DRIVER_NAME)==='mysql'){$s=db()->prepare('INSERT INTO company_presence (company_id,user_id,vehicle_id,journey_id,lat,lon,speed_kmh,heading,last_seen_at) VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE vehicle_id=VALUES(vehicle_id),journey_id=VALUES(journey_id),lat=VALUES(lat),lon=VALUES(lon),speed_kmh=VALUES(speed_kmh),heading=VALUES(heading),last_seen_at=VALUES(last_seen_at)');}else{$s=db()->prepare('INSERT INTO company_presence (company_id,user_id,vehicle_id,journey_id,lat,lon,speed_kmh,heading,last_seen_at) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(company_id,user_id) DO UPDATE SET vehicle_id=excluded.vehicle_id,journey_id=excluded.journey_id,lat=excluded.lat,lon=excluded.lon,speed_kmh=excluded.speed_kmh,heading=excluded.heading,last_seen_at=excluded.last_seen_at');}$s->execute([$cid,$uid,$vid,$jid,$lat,$lon,$speed,$heading,$now]); }
