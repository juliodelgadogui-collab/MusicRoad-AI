<?php
declare(strict_types=1);
require_once __DIR__.'/bootstrap.php';
require_once __DIR__.'/native_auth.php';
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

$body=input_json();
$user=native_require_json_user($body);
$pdo=db();

// EPC_CONVOY_AUTO_SCHEMA_V239
// No manual SQL import is required. On authenticated use, the API checks the
// expected schema and only creates/adds missing Comboio structures. Once ready,
// normal requests perform no DDL.
function convoy_schema_ready(PDO $pdo): bool {
    $sql="SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND ("
        ."(TABLE_NAME='estrada_convoys' AND COLUMN_NAME IN ('id','code','owner_user_id','leader_device_token','title','destination_label','destination_lat','destination_lon','route_points_json','route_updated_at','created_at','expires_at')) OR "
        ."(TABLE_NAME='estrada_convoy_members' AND COLUMN_NAME IN ('convoy_id','user_id','device_token','nickname','latitude','longitude','speed_kmh','heading','joined_at','last_seen')) OR "
        ."(TABLE_NAME='estrada_convoy_blocks' AND COLUMN_NAME IN ('convoy_id','device_token','blocked_at'))"
        .")";
    return (int)$pdo->query($sql)->fetchColumn()===25;
}
function convoy_schema_has_column(PDO $pdo,string $table,string $column): bool {
    $q=$pdo->prepare('SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=? LIMIT 1');
    $q->execute([$table,$column]);
    return (bool)$q->fetchColumn();
}
function convoy_schema_add_column(PDO $pdo,string $table,string $column,string $definition): void {
    if(convoy_schema_has_column($pdo,$table,$column))return;
    $allowed=['estrada_convoys'];
    if(!in_array($table,$allowed,true))throw new RuntimeException('Tabela de migração inválida.');
    $pdo->exec('ALTER TABLE `'.$table.'` ADD COLUMN `'.$column.'` '.$definition);
}
function convoy_ensure_schema(PDO $pdo): void {
    if(convoy_schema_ready($pdo))return;
    $lock=false;
    try{
        $q=$pdo->query("SELECT GET_LOCK('epc_convoy_schema_v239',10)");
        $lock=((int)$q->fetchColumn()===1);
        if(!$lock)throw new RuntimeException('Tempo esgotado aguardando preparação do Comboio.');
        if(convoy_schema_ready($pdo))return;

        $pdo->exec("CREATE TABLE IF NOT EXISTS estrada_convoys (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 code VARCHAR(8) NOT NULL UNIQUE,
 owner_user_id BIGINT NULL,
 leader_device_token VARCHAR(160) NULL,
 title VARCHAR(80) NULL,
 destination_label VARCHAR(120) NULL,
 destination_lat DECIMAL(10,7) NULL,
 destination_lon DECIMAL(10,7) NULL,
 route_points_json MEDIUMTEXT NULL,
 route_updated_at DATETIME NULL,
 created_at DATETIME NOT NULL,
 expires_at DATETIME NOT NULL,
 INDEX idx_convoy_exp (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        $pdo->exec("CREATE TABLE IF NOT EXISTS estrada_convoy_members (
 convoy_id BIGINT UNSIGNED NOT NULL,
 user_id BIGINT NULL,
 device_token VARCHAR(160) NOT NULL,
 nickname VARCHAR(60) NOT NULL,
 latitude DECIMAL(10,7) NULL,
 longitude DECIMAL(10,7) NULL,
 speed_kmh DECIMAL(7,2) NULL,
 heading DECIMAL(7,2) NULL,
 joined_at DATETIME NOT NULL,
 last_seen DATETIME NOT NULL,
 PRIMARY KEY(convoy_id,device_token),
 INDEX idx_convoy_member_seen (convoy_id,last_seen),
 INDEX idx_convoy_member_device (device_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        $pdo->exec("CREATE TABLE IF NOT EXISTS estrada_convoy_blocks (
 convoy_id BIGINT UNSIGNED NOT NULL,
 device_token VARCHAR(160) NOT NULL,
 blocked_at DATETIME NOT NULL,
 PRIMARY KEY(convoy_id,device_token),
 INDEX idx_convoy_blocked_at (blocked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

        // Compatibility with the older 2.3.x Comboio table, without deleting data.
        convoy_schema_add_column($pdo,'estrada_convoys','leader_device_token','VARCHAR(160) NULL AFTER `owner_user_id`');
        convoy_schema_add_column($pdo,'estrada_convoys','destination_label','VARCHAR(120) NULL AFTER `title`');
        convoy_schema_add_column($pdo,'estrada_convoys','destination_lat','DECIMAL(10,7) NULL AFTER `destination_label`');
        convoy_schema_add_column($pdo,'estrada_convoys','destination_lon','DECIMAL(10,7) NULL AFTER `destination_lat`');
        convoy_schema_add_column($pdo,'estrada_convoys','route_points_json','MEDIUMTEXT NULL AFTER `destination_lon`');
        convoy_schema_add_column($pdo,'estrada_convoys','route_updated_at','DATETIME NULL AFTER `route_points_json`');

        if(!convoy_schema_ready($pdo))throw new RuntimeException('Estrutura do Comboio permaneceu incompleta.');
    } finally {
        if($lock){try{$pdo->query("SELECT RELEASE_LOCK('epc_convoy_schema_v239')");}catch(Throwable $ignored){}}
    }
}
try{
    convoy_ensure_schema($pdo);
}catch(Throwable $e){
    error_log('EPC convoy auto-schema: '.$e->getMessage());
    json_response(['ok'=>false,'error'=>'Não consegui preparar o Comboio automaticamente. Verifique a permissão do banco usada pelo servidor.'],503);
}

$action=strtolower(trim((string)($body['action']??'state')));
$device=native_device_token_from_request($body);
$userId=(int)($user['id']??0);
if($device==='')json_response(['ok'=>false,'error'=>'Aparelho não identificado.'],422);

function convoy_code(string $raw): string {
    $v=strtoupper(preg_replace('/[^A-Z0-9]/i','',$raw)??'');
    return substr($v,0,8);
}
function convoy_member_id(string $device): string { return substr(hash('sha256',$device),0,20); }
function convoy_random_code(PDO $pdo): string {
    $alphabet='ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    for($t=0;$t<20;$t++){
        $code='';for($i=0;$i<6;$i++)$code.=$alphabet[random_int(0,strlen($alphabet)-1)];
        $q=$pdo->prepare('SELECT id FROM estrada_convoys WHERE code=? LIMIT 1');$q->execute([$code]);
        if(!$q->fetchColumn())return $code;
    }
    return strtoupper(substr(bin2hex(random_bytes(4)),0,6));
}
function convoy_nickname(array $body,string $device): string {
    $n=trim((string)($body['nickname']??''));
    if($n==='')$n='MOTORISTA-'.strtoupper(substr(convoy_member_id($device),-4));
    return mb_substr($n,0,60,'UTF-8');
}
function convoy_coords(array $body): array {
    $lat=array_key_exists('lat',$body)?(float)$body['lat']:NAN;
    $lon=array_key_exists('lon',$body)?(float)$body['lon']:NAN;
    if(is_finite($lat)&&is_finite($lon)&&$lat>=-35&&$lat<=6&&$lon>=-75&&$lon<=-30)return [$lat,$lon];
    return [null,null];
}
function convoy_find(PDO $pdo,string $code): ?array {
    $q=$pdo->prepare('SELECT id,code,title,owner_user_id,leader_device_token,destination_label,destination_lat,destination_lon,route_points_json,UNIX_TIMESTAMP(route_updated_at)*1000 AS route_updated_ms,UNIX_TIMESTAMP(expires_at)*1000 AS expires_ms FROM estrada_convoys WHERE code=? AND expires_at>NOW() LIMIT 1');
    $q->execute([$code]);$r=$q->fetch(PDO::FETCH_ASSOC);return $r?:null;
}
function convoy_member_upsert(PDO $pdo,int $convoyId,int $userId,string $device,string $nickname,$lat,$lon,array $body): void {
    $speed=isset($body['speed_kmh'])?(float)$body['speed_kmh']:null;if($speed!==null&&($speed<0||$speed>260))$speed=null;
    $heading=isset($body['heading'])?(float)$body['heading']:null;if($heading!==null&&!is_finite($heading))$heading=null;
    if($heading!==null){$heading=fmod($heading,360.0);if($heading<0)$heading+=360.0;}
    $q=$pdo->prepare("INSERT INTO estrada_convoy_members (convoy_id,user_id,device_token,nickname,latitude,longitude,speed_kmh,heading,joined_at,last_seen) VALUES (?,?,?,?,?,?,?,?,NOW(),NOW()) ON DUPLICATE KEY UPDATE user_id=VALUES(user_id),nickname=VALUES(nickname),latitude=COALESCE(VALUES(latitude),latitude),longitude=COALESCE(VALUES(longitude),longitude),speed_kmh=VALUES(speed_kmh),heading=VALUES(heading),last_seen=NOW()");
    $q->execute([$convoyId,$userId?:null,$device,$nickname,$lat,$lon,$speed,$heading]);
}
function convoy_haversine(float $a,float $o,float $b,float $p): float {
    $r=6371000.0;$d1=deg2rad($b-$a);$d2=deg2rad($p-$o);$x=sin($d1/2)**2+cos(deg2rad($a))*cos(deg2rad($b))*sin($d2/2)**2;return $r*2*atan2(sqrt($x),sqrt(max(0,1-$x)));
}
function convoy_route_points($raw): array {
    if(is_string($raw)){try{$raw=json_decode($raw,true,512,JSON_THROW_ON_ERROR);}catch(Throwable $e){$raw=[];}}
    if(!is_array($raw))return [];$out=[];
    foreach($raw as $p){if(count($out)>=48)break;if(!is_array($p))continue;$lat=(float)($p['lat']??NAN);$lon=(float)($p['lon']??NAN);if(is_finite($lat)&&is_finite($lon)&&$lat>=-35&&$lat<=6&&$lon>=-75&&$lon<=-30)$out[]=['lat'=>$lat,'lon'=>$lon];}
    return $out;
}
function convoy_point_segment_m(float $lat,float $lon,array $a,array $b): float {
    $scale=max(.25,cos(deg2rad($lat)));$x=($lon-(float)$a['lon'])*111320.0*$scale;$y=($lat-(float)$a['lat'])*110540.0;$bx=((float)$b['lon']-(float)$a['lon'])*111320.0*$scale;$by=((float)$b['lat']-(float)$a['lat'])*110540.0;$den=$bx*$bx+$by*$by;$t=$den>0?max(0,min(1,($x*$bx+$y*$by)/$den)):0;$dx=$x-$t*$bx;$dy=$y-$t*$by;return sqrt($dx*$dx+$dy*$dy);
}
function convoy_route_deviation(array $route,float $lat,float $lon): ?float {
    if(count($route)<2)return null;$best=INF;for($i=0;$i<count($route)-1;$i++)$best=min($best,convoy_point_segment_m($lat,$lon,$route[$i],$route[$i+1]));return is_finite($best)?$best:null;
}
function convoy_is_blocked(PDO $pdo,int $convoyId,string $device): bool {$q=$pdo->prepare('SELECT 1 FROM estrada_convoy_blocks WHERE convoy_id=? AND device_token=? LIMIT 1');$q->execute([$convoyId,$device]);return (bool)$q->fetchColumn();}
function convoy_is_leader(array $convoy,string $device): bool {return isset($convoy['leader_device_token'])&&(string)$convoy['leader_device_token']!==''&&hash_equals((string)$convoy['leader_device_token'],$device);}

/** Personal convoys remain code-based. A code published by a company becomes roster-protected. */
function convoy_company_access_allowed(PDO $pdo,string $code,int $userId): bool {
    try{
        $q=$pdo->prepare('SELECT company_id FROM company_convoy_links WHERE convoy_code=? ORDER BY active DESC,updated_at DESC LIMIT 1');
        $q->execute([$code]);$companyId=(int)($q->fetchColumn()?:0);
    }catch(Throwable $e){return true;}
    if($companyId<=0)return true;
    if($userId<=0)return false;
    try{
        $m=$pdo->prepare("SELECT member_role FROM company_members WHERE company_id=? AND user_id=? AND status='active' LIMIT 1");
        $m->execute([$companyId,$userId]);$role=strtolower((string)($m->fetchColumn()?:''));
        if(in_array($role,['owner','admin','manager'],true))return true;
    }catch(Throwable $e){return false;}
    try{
        $r=$pdo->prepare('SELECT 1 FROM company_convoy_roster WHERE company_id=? AND convoy_code=? AND user_id=? AND active=1 LIMIT 1');
        $r->execute([$companyId,$code,$userId]);
        return (bool)$r->fetchColumn();
    }catch(Throwable $e){return false;}
}
function convoy_require_company_access(PDO $pdo,string $code,int $userId,int $convoyId,string $device): void {
    if(convoy_company_access_allowed($pdo,$code,$userId))return;
    try{$pdo->prepare('DELETE FROM estrada_convoy_members WHERE convoy_id=? AND device_token=?')->execute([$convoyId,$device]);}catch(Throwable $ignored){}
    json_response(['ok'=>false,'error'=>'Este Comboio pertence a uma empresa e sua conta não está na equipe autorizada.'],403);
}

function convoy_state(PDO $pdo,array $convoy,string $device): array {
    $q=$pdo->prepare("SELECT device_token,nickname,latitude,longitude,speed_kmh,heading,UNIX_TIMESTAMP(last_seen)*1000 AS seen_ms,TIMESTAMPDIFF(SECOND,last_seen,NOW()) AS age_s FROM estrada_convoy_members WHERE convoy_id=? AND last_seen>=DATE_SUB(NOW(),INTERVAL 90 SECOND) ORDER BY joined_at ASC,last_seen DESC");
    $q->execute([(int)$convoy['id']]);$rows=$q->fetchAll(PDO::FETCH_ASSOC)?:[];$route=convoy_route_points($convoy['route_points_json']??'');$leaderDevice=(string)($convoy['leader_device_token']??'');$leaderLat=null;$leaderLon=null;
    foreach($rows as $r)if($leaderDevice!==''&&(string)$r['device_token']===$leaderDevice){$leaderLat=$r['latitude']===null?null:(float)$r['latitude'];$leaderLon=$r['longitude']===null?null:(float)$r['longitude'];break;}
    $destLat=$convoy['destination_lat']===null?null:(float)$convoy['destination_lat'];$destLon=$convoy['destination_lon']===null?null:(float)$convoy['destination_lon'];$members=[];
    foreach($rows as $r){$raw=(string)$r['device_token'];$lat=$r['latitude']===null?null:(float)$r['latitude'];$lon=$r['longitude']===null?null:(float)$r['longitude'];$speed=$r['speed_kmh']===null?0:(float)$r['speed_kmh'];$age=(int)($r['age_s']??0);$isLeader=$leaderDevice!==''&&$raw===$leaderDevice;$toLeader=null;$deviation=null;$toDest=null;$eta=null;
        if($lat!==null&&$lon!==null&&$leaderLat!==null&&$leaderLon!==null&&!$isLeader)$toLeader=convoy_haversine($lat,$lon,$leaderLat,$leaderLon);
        if($lat!==null&&$lon!==null&&count($route)>=2)$deviation=convoy_route_deviation($route,$lat,$lon);
        if($lat!==null&&$lon!==null&&$destLat!==null&&$destLon!==null){$toDest=convoy_haversine($lat,$lon,$destLat,$destLon);$cruise=max(25,min(110,$speed>5?$speed:80));$eta=(int)round(($toDest/1000)/$cruise*3600);}
        $sep='OK';if($toLeader!==null&&$toLeader>3500)$sep='DISTANTE';elseif($toLeader!==null&&$toLeader>1500)$sep='ATENCAO';$presence=$age<=15?'ONLINE':($age<=35?'ATRASADO':'SEM_SINAL');
        $members[]=['member_id'=>convoy_member_id($raw),'nickname'=>(string)$r['nickname'],'lat'=>$lat,'lon'=>$lon,'speed_kmh'=>$speed,'heading'=>$r['heading']===null?null:(float)$r['heading'],'last_seen'=>(int)($r['seen_ms']??0),'age_s'=>$age,'presence'=>$presence,'self'=>$raw===$device,'leader'=>$isLeader,'distance_to_leader_m'=>$toLeader===null?null:(int)round($toLeader),'separation_status'=>$sep,'route_deviation_m'=>$deviation===null?null:(int)round($deviation),'off_route'=>$deviation!==null&&$deviation>180,'distance_to_destination_m'=>$toDest===null?null:(int)round($toDest),'eta_s'=>$eta];
    }
    return ['ok'=>true,'version'=>'2.3','code'=>(string)$convoy['code'],'title'=>(string)($convoy['title']??''),'expires_at'=>(int)($convoy['expires_ms']??0),'self_member_id'=>convoy_member_id($device),'leader_member_id'=>$leaderDevice===''?'':convoy_member_id($leaderDevice),'self_is_leader'=>convoy_is_leader($convoy,$device),'destination'=>($destLat!==null&&$destLon!==null)?['label'=>(string)($convoy['destination_label']??'Destino do comboio'),'lat'=>$destLat,'lon'=>$destLon,'updated_at'=>(int)($convoy['route_updated_ms']??0)]:null,'route_points'=>$route,'members'=>$members,'member_count'=>count($members),'presence_ttl_s'=>90,'server_time'=>round(microtime(true)*1000)];
}
function convoy_find_member_device(PDO $pdo,int $convoyId,string $memberId): ?string {$q=$pdo->prepare('SELECT device_token FROM estrada_convoy_members WHERE convoy_id=?');$q->execute([$convoyId]);while($raw=$q->fetchColumn()){if(hash_equals(convoy_member_id((string)$raw),$memberId))return (string)$raw;}return null;}

try{$pdo->exec("DELETE m FROM estrada_convoy_members m LEFT JOIN estrada_convoys c ON c.id=m.convoy_id WHERE c.id IS NULL OR c.expires_at<NOW()");$pdo->exec("DELETE b FROM estrada_convoy_blocks b LEFT JOIN estrada_convoys c ON c.id=b.convoy_id WHERE c.id IS NULL OR c.expires_at<NOW()");$pdo->exec("DELETE FROM estrada_convoys WHERE expires_at<NOW() LIMIT 100");}catch(Throwable $e){}

if($action==='create'){
    $code=convoy_random_code($pdo);$title=mb_substr(trim((string)($body['title']??'Comboio Estrada Play')),0,80,'UTF-8');
    $q=$pdo->prepare('INSERT INTO estrada_convoys (code,owner_user_id,leader_device_token,title,created_at,expires_at) VALUES (?,?,?,?,NOW(),DATE_ADD(NOW(),INTERVAL 24 HOUR))');$q->execute([$code,$userId?:null,$device,$title!==''?$title:null]);
    $convoy=convoy_find($pdo,$code);if(!$convoy)json_response(['ok'=>false,'error'=>'Não consegui criar o comboio.'],503);
    [$lat,$lon]=convoy_coords($body);convoy_member_upsert($pdo,(int)$convoy['id'],$userId,$device,convoy_nickname($body,$device),$lat,$lon,$body);
    if(function_exists('audit_log'))audit_log('convoy.create',['code'=>$code]);
    json_response(convoy_state($pdo,convoy_find($pdo,$code)?:$convoy,$device));
}

$code=convoy_code((string)($body['code']??''));
if(strlen($code)<4)json_response(['ok'=>false,'error'=>'Código do comboio inválido.'],422);
$convoy=convoy_find($pdo,$code);
if(!$convoy)json_response(['ok'=>false,'error'=>'Comboio não encontrado ou expirado.'],404);
$convoyId=(int)$convoy['id'];

if($action==='join'){
    convoy_require_company_access($pdo,$code,$userId,$convoyId,$device);
    if(convoy_is_blocked($pdo,$convoyId,$device))json_response(['ok'=>false,'error'=>'Este aparelho foi removido deste comboio pelo líder.'],403);
    [$lat,$lon]=convoy_coords($body);convoy_member_upsert($pdo,$convoyId,$userId,$device,convoy_nickname($body,$device),$lat,$lon,$body);
    if((string)($convoy['leader_device_token']??'')===''){$pdo->prepare('UPDATE estrada_convoys SET leader_device_token=? WHERE id=?')->execute([$device,$convoyId]);$convoy=convoy_find($pdo,$code)?:$convoy;}
    json_response(convoy_state($pdo,$convoy,$device));
}
if($action==='leave'){
    $wasLeader=convoy_is_leader($convoy,$device);$q=$pdo->prepare('DELETE FROM estrada_convoy_members WHERE convoy_id=? AND device_token=?');$q->execute([$convoyId,$device]);
    if($wasLeader){$q=$pdo->prepare('SELECT device_token,user_id FROM estrada_convoy_members WHERE convoy_id=? ORDER BY joined_at ASC LIMIT 1');$q->execute([$convoyId]);$next=$q->fetch(PDO::FETCH_ASSOC);if($next){$pdo->prepare('UPDATE estrada_convoys SET leader_device_token=?,owner_user_id=? WHERE id=?')->execute([(string)$next['device_token'],$next['user_id']?:null,$convoyId]);}else{$pdo->prepare('DELETE FROM estrada_convoys WHERE id=?')->execute([$convoyId]);}}
    json_response(['ok'=>true,'left'=>true,'code'=>$code]);
}
if($action==='ping'){
    convoy_require_company_access($pdo,$code,$userId,$convoyId,$device);
    $q=$pdo->prepare('SELECT 1 FROM estrada_convoy_members WHERE convoy_id=? AND device_token=? LIMIT 1');$q->execute([$convoyId,$device]);if(!$q->fetchColumn())json_response(['ok'=>false,'error'=>'Entre novamente no comboio.'],403);
    [$lat,$lon]=convoy_coords($body);convoy_member_upsert($pdo,$convoyId,$userId,$device,convoy_nickname($body,$device),$lat,$lon,$body);$pdo->prepare('UPDATE estrada_convoys SET expires_at=DATE_ADD(NOW(),INTERVAL 24 HOUR) WHERE id=?')->execute([$convoyId]);$convoy=convoy_find($pdo,$code)?:$convoy;json_response(convoy_state($pdo,$convoy,$device));
}
if($action==='set_route'){
    convoy_require_company_access($pdo,$code,$userId,$convoyId,$device);
    if(!convoy_is_leader($convoy,$device))json_response(['ok'=>false,'error'=>'Somente o líder pode compartilhar a rota.'],403);
    $dlat=(float)($body['destination_lat']??NAN);$dlon=(float)($body['destination_lon']??NAN);if(!is_finite($dlat)||!is_finite($dlon)||$dlat<-35||$dlat>6||$dlon<-75||$dlon>-30)json_response(['ok'=>false,'error'=>'Destino inválido.'],422);
    $label=mb_substr(trim((string)($body['destination_label']??'Destino do comboio')),0,120,'UTF-8');$route=convoy_route_points($body['route_points']??[]);$q=$pdo->prepare('UPDATE estrada_convoys SET destination_label=?,destination_lat=?,destination_lon=?,route_points_json=?,route_updated_at=NOW(),expires_at=DATE_ADD(NOW(),INTERVAL 24 HOUR) WHERE id=?');$q->execute([$label!==''?$label:'Destino do comboio',$dlat,$dlon,json_encode($route,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES),$convoyId]);$convoy=convoy_find($pdo,$code)?:$convoy;if(function_exists('audit_log'))audit_log('convoy.route',['code'=>$code]);json_response(convoy_state($pdo,$convoy,$device));
}
if($action==='clear_route'){
    convoy_require_company_access($pdo,$code,$userId,$convoyId,$device);
    if(!convoy_is_leader($convoy,$device))json_response(['ok'=>false,'error'=>'Somente o líder pode limpar a rota.'],403);$pdo->prepare('UPDATE estrada_convoys SET destination_label=NULL,destination_lat=NULL,destination_lon=NULL,route_points_json=NULL,route_updated_at=NULL WHERE id=?')->execute([$convoyId]);$convoy=convoy_find($pdo,$code)?:$convoy;json_response(convoy_state($pdo,$convoy,$device));
}
if($action==='kick'){
    convoy_require_company_access($pdo,$code,$userId,$convoyId,$device);
    if(!convoy_is_leader($convoy,$device))json_response(['ok'=>false,'error'=>'Somente o líder pode remover participantes.'],403);$targetId=strtolower(trim((string)($body['member_id']??'')));$target=convoy_find_member_device($pdo,$convoyId,$targetId);if($target===null)json_response(['ok'=>false,'error'=>'Participante não encontrado.'],404);if(hash_equals($target,$device))json_response(['ok'=>false,'error'=>'O líder deve sair do comboio em vez de remover a si mesmo.'],422);$pdo->prepare('INSERT INTO estrada_convoy_blocks (convoy_id,device_token,blocked_at) VALUES (?,?,NOW()) ON DUPLICATE KEY UPDATE blocked_at=NOW()')->execute([$convoyId,$target]);$pdo->prepare('DELETE FROM estrada_convoy_members WHERE convoy_id=? AND device_token=?')->execute([$convoyId,$target]);if(function_exists('audit_log'))audit_log('convoy.kick',['code'=>$code,'member_id'=>$targetId]);json_response(convoy_state($pdo,$convoy,$device));
}
if($action==='state'){
    convoy_require_company_access($pdo,$code,$userId,$convoyId,$device);
    $q=$pdo->prepare('SELECT 1 FROM estrada_convoy_members WHERE convoy_id=? AND device_token=? LIMIT 1');$q->execute([$convoyId,$device]);if(!$q->fetchColumn())json_response(['ok'=>false,'error'=>'Você não faz parte deste comboio.'],403);json_response(convoy_state($pdo,$convoy,$device));
}
json_response(['ok'=>false,'error'=>'Ação inválida.'],422);
