<?php
declare(strict_types=1);
require_once __DIR__.'/bootstrap.php';
require_once __DIR__.'/native_auth.php';
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

$body=input_json();
$user=native_require_json_user($body);
$pdo=db();
$pdo->exec("CREATE TABLE IF NOT EXISTS estrada_convoys (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 code VARCHAR(8) NOT NULL UNIQUE,
 owner_user_id BIGINT NULL,
 title VARCHAR(80) NULL,
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

$action=strtolower(trim((string)($body['action']??'state')));
$device=native_device_token_from_request($body);
$userId=(int)($user['id']??0);
if($device==='')json_response(['ok'=>false,'error'=>'Aparelho não identificado.'],422);

function convoy_code(string $raw): string {
    $v=strtoupper(preg_replace('/[^A-Z0-9]/i','',$raw)??'');
    return substr($v,0,8);
}
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
    if($n==='')$n='MOTORISTA-'.strtoupper(substr($device,max(0,strlen($device)-4)));
    return substr($n,0,60);
}
function convoy_coords(array $body): array {
    $lat=array_key_exists('lat',$body)?(float)$body['lat']:NAN;
    $lon=array_key_exists('lon',$body)?(float)$body['lon']:NAN;
    if(is_finite($lat)&&is_finite($lon)&&$lat>=-35&&$lat<=6&&$lon>=-75&&$lon<=-30)return [$lat,$lon];
    return [null,null];
}
function convoy_find(PDO $pdo,string $code): ?array {
    $q=$pdo->prepare('SELECT id,code,title,owner_user_id,UNIX_TIMESTAMP(expires_at)*1000 AS expires_ms FROM estrada_convoys WHERE code=? AND expires_at>NOW() LIMIT 1');
    $q->execute([$code]);$r=$q->fetch(PDO::FETCH_ASSOC);return $r?:null;
}
function convoy_member_upsert(PDO $pdo,int $convoyId,int $userId,string $device,string $nickname,$lat,$lon,array $body): void {
    $speed=isset($body['speed_kmh'])?(float)$body['speed_kmh']:null;if($speed!==null&&($speed<0||$speed>260))$speed=null;
    $heading=isset($body['heading'])?(float)$body['heading']:null;if($heading!==null&&!is_finite($heading))$heading=null;
    if($heading!==null){$heading=fmod($heading,360.0);if($heading<0)$heading+=360.0;}
    $q=$pdo->prepare("INSERT INTO estrada_convoy_members (convoy_id,user_id,device_token,nickname,latitude,longitude,speed_kmh,heading,joined_at,last_seen) VALUES (?,?,?,?,?,?,?,?,NOW(),NOW()) ON DUPLICATE KEY UPDATE user_id=VALUES(user_id),nickname=VALUES(nickname),latitude=COALESCE(VALUES(latitude),latitude),longitude=COALESCE(VALUES(longitude),longitude),speed_kmh=VALUES(speed_kmh),heading=VALUES(heading),last_seen=NOW()");
    $q->execute([$convoyId,$userId?:null,$device,$nickname,$lat,$lon,$speed,$heading]);
}
function convoy_state(PDO $pdo,array $convoy,string $device): array {
    $q=$pdo->prepare("SELECT device_token,nickname,latitude,longitude,speed_kmh,heading,UNIX_TIMESTAMP(last_seen)*1000 AS seen_ms,TIMESTAMPDIFF(SECOND,last_seen,NOW()) AS age_s FROM estrada_convoy_members WHERE convoy_id=? AND last_seen>=DATE_SUB(NOW(),INTERVAL 3 MINUTE) ORDER BY last_seen DESC");
    $q->execute([(int)$convoy['id']]);$members=[];
    while($r=$q->fetch(PDO::FETCH_ASSOC)){
        $members[]=['device'=>(string)$r['device_token'],'nickname'=>(string)$r['nickname'],'lat'=>$r['latitude']===null?null:(float)$r['latitude'],'lon'=>$r['longitude']===null?null:(float)$r['longitude'],'speed_kmh'=>$r['speed_kmh']===null?0:(float)$r['speed_kmh'],'heading'=>$r['heading']===null?null:(float)$r['heading'],'last_seen'=>(int)($r['seen_ms']??0),'age_s'=>(int)($r['age_s']??0),'self'=>((string)$r['device_token']===$device)];
    }
    return ['ok'=>true,'code'=>(string)$convoy['code'],'title'=>(string)($convoy['title']??''),'expires_at'=>(int)($convoy['expires_ms']??0),'self_device'=>$device,'members'=>$members,'member_count'=>count($members),'server_time'=>round(microtime(true)*1000)];
}

try{
    $pdo->exec("DELETE m FROM estrada_convoy_members m LEFT JOIN estrada_convoys c ON c.id=m.convoy_id WHERE c.id IS NULL OR c.expires_at<NOW()");
    $pdo->exec("DELETE FROM estrada_convoys WHERE expires_at<NOW() LIMIT 100");
}catch(Throwable $e){}

if($action==='create'){
    $code=convoy_random_code($pdo);$title=substr(trim((string)($body['title']??'Comboio Estrada Play')),0,80);
    $q=$pdo->prepare('INSERT INTO estrada_convoys (code,owner_user_id,title,created_at,expires_at) VALUES (?,?,?,NOW(),DATE_ADD(NOW(),INTERVAL 24 HOUR))');$q->execute([$code,$userId?:null,$title!==''?$title:null]);
    $convoy=convoy_find($pdo,$code);if(!$convoy)json_response(['ok'=>false,'error'=>'Não consegui criar o comboio.'],503);
    [$lat,$lon]=convoy_coords($body);convoy_member_upsert($pdo,(int)$convoy['id'],$userId,$device,convoy_nickname($body,$device),$lat,$lon,$body);
    if(function_exists('audit_log'))audit_log('convoy.create',['code'=>$code]);
    json_response(convoy_state($pdo,$convoy,$device));
}

$code=convoy_code((string)($body['code']??''));
if(strlen($code)<4)json_response(['ok'=>false,'error'=>'Código do comboio inválido.'],422);
$convoy=convoy_find($pdo,$code);
if(!$convoy)json_response(['ok'=>false,'error'=>'Comboio não encontrado ou expirado.'],404);

if($action==='join'){
    [$lat,$lon]=convoy_coords($body);convoy_member_upsert($pdo,(int)$convoy['id'],$userId,$device,convoy_nickname($body,$device),$lat,$lon,$body);
    json_response(convoy_state($pdo,$convoy,$device));
}
if($action==='leave'){
    $q=$pdo->prepare('DELETE FROM estrada_convoy_members WHERE convoy_id=? AND device_token=?');$q->execute([(int)$convoy['id'],$device]);
    json_response(['ok'=>true,'left'=>true,'code'=>$code]);
}
if($action==='ping'){
    $q=$pdo->prepare('SELECT 1 FROM estrada_convoy_members WHERE convoy_id=? AND device_token=? LIMIT 1');$q->execute([(int)$convoy['id'],$device]);
    if(!$q->fetchColumn())json_response(['ok'=>false,'error'=>'Entre novamente no comboio.'],403);
    [$lat,$lon]=convoy_coords($body);convoy_member_upsert($pdo,(int)$convoy['id'],$userId,$device,convoy_nickname($body,$device),$lat,$lon,$body);
    json_response(convoy_state($pdo,$convoy,$device));
}
if($action==='state'){
    $q=$pdo->prepare('SELECT 1 FROM estrada_convoy_members WHERE convoy_id=? AND device_token=? LIMIT 1');$q->execute([(int)$convoy['id'],$device]);
    if(!$q->fetchColumn())json_response(['ok'=>false,'error'=>'Você não faz parte deste comboio.'],403);
    json_response(convoy_state($pdo,$convoy,$device));
}
json_response(['ok'=>false,'error'=>'Ação inválida.'],422);
