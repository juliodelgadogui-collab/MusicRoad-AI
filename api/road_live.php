<?php
declare(strict_types=1);
require_once __DIR__.'/bootstrap.php';
require_once __DIR__.'/native_auth.php';
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

$body=input_json();
$user=native_require_json_user($body);
$pdo=db();
$pdo->exec("CREATE TABLE IF NOT EXISTS road_live_events (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NULL,
 device_token VARCHAR(160) NULL,
 event_type VARCHAR(32) NOT NULL,
 latitude DECIMAL(10,7) NOT NULL,
 longitude DECIMAL(10,7) NOT NULL,
 road VARCHAR(40) NULL,
 note VARCHAR(160) NULL,
 confirmations INT UNSIGNED NOT NULL DEFAULT 0,
 occurred_at BIGINT NULL,
 expires_at DATETIME NOT NULL,
 created_at DATETIME NOT NULL,
 INDEX idx_road_live_geo (latitude,longitude),
 INDEX idx_road_live_exp (expires_at),
 INDEX idx_road_live_type_time (event_type,created_at),
 INDEX idx_road_live_device (device_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
$pdo->exec("CREATE TABLE IF NOT EXISTS road_live_confirmations (
 event_id BIGINT UNSIGNED NOT NULL,
 device_token VARCHAR(160) NOT NULL,
 created_at DATETIME NOT NULL,
 PRIMARY KEY(event_id,device_token),
 INDEX idx_road_live_confirm_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

$action=strtolower(trim((string)($body['action']??'nearby')));
$device=native_device_token_from_request($body);
$userId=(int)($user['id']??0);
$allowed=['pothole','animal','accident','construction','flooding','traffic','object'];
$ttl=['pothole'=>2592000,'animal'=>2700,'accident'=>10800,'construction'=>43200,'flooding'=>14400,'traffic'=>3600,'object'=>3600];

function road_live_coord(array $body): array {
    $lat=(float)($body['lat']??0);$lon=(float)($body['lon']??0);
    if(!is_finite($lat)||!is_finite($lon)||$lat < -35||$lat > 6||$lon < -75||$lon > -30)
        json_response(['ok'=>false,'error'=>'Coordenada inválida.'],422);
    return [$lat,$lon];
}
function road_live_distance_m(float $a,float $b,float $c,float $d): float {
    $p1=deg2rad($a);$p2=deg2rad($c);$dp=$p2-$p1;$dl=deg2rad($d-$b);
    $x=sin($dp/2)**2+cos($p1)*cos($p2)*sin($dl/2)**2;
    return 6371000.0*2.0*atan2(sqrt($x),sqrt(max(0.0,1.0-$x)));
}

try{$pdo->exec("DELETE FROM road_live_events WHERE expires_at<NOW() LIMIT 500");}catch(Throwable $e){}

if($action==='report'){
    [$lat,$lon]=road_live_coord($body);
    $type=strtolower(trim((string)($body['event_type']??'')));
    if(!in_array($type,$allowed,true))json_response(['ok'=>false,'error'=>'Tipo de alerta inválido.'],422);
    $road=strtoupper(trim((string)($body['road']??'')));$note=trim((string)($body['note']??''));
    $road=substr($road,0,40);$note=substr($note,0,160);
    $occurred=isset($body['occurred_at'])?(int)$body['occurred_at']:round(microtime(true)*1000);
    $seconds=(int)($ttl[$type]??3600);
    try{
        $q=$pdo->prepare("SELECT id FROM road_live_events WHERE event_type=? AND COALESCE(device_token,'')=? AND ABS(latitude-?)<0.00018 AND ABS(longitude-?)<0.00018 AND created_at>=DATE_SUB(NOW(),INTERVAL 5 MINUTE) ORDER BY id DESC LIMIT 1");
        $q->execute([$type,$device,$lat,$lon]);$existing=(int)($q->fetchColumn()?:0);
        if($existing>0)json_response(['ok'=>true,'id'=>$existing,'duplicate'=>true]);
    }catch(Throwable $e){}
    $q=$pdo->prepare('INSERT INTO road_live_events (user_id,device_token,event_type,latitude,longitude,road,note,confirmations,occurred_at,expires_at,created_at) VALUES (?,?,?,?,?,?,?,?,?,DATE_ADD(NOW(),INTERVAL ? SECOND),NOW())');
    $q->execute([$userId?:null,$device!==''?$device:null,$type,$lat,$lon,$road!==''?$road:null,$note!==''?$note:null,0,$occurred,$seconds]);
    $id=(int)$pdo->lastInsertId();
    if(function_exists('audit_log'))audit_log('road_live.report',['id'=>$id,'type'=>$type]);
    json_response(['ok'=>true,'id'=>$id,'stored'=>true]);
}

if($action==='confirm'){
    $id=(int)($body['event_id']??0);
    if($id<=0||$device==='')json_response(['ok'=>false,'error'=>'Confirmação inválida.'],422);
    $q=$pdo->prepare('SELECT id FROM road_live_events WHERE id=? AND expires_at>NOW() LIMIT 1');$q->execute([$id]);
    if(!(int)($q->fetchColumn()?:0))json_response(['ok'=>false,'error'=>'Alerta não encontrado ou expirado.'],404);
    $pdo->beginTransaction();
    try{
        $q=$pdo->prepare('INSERT IGNORE INTO road_live_confirmations (event_id,device_token,created_at) VALUES (?,?,NOW())');$q->execute([$id,$device]);
        if($q->rowCount()>0){$u=$pdo->prepare('UPDATE road_live_events SET confirmations=confirmations+1 WHERE id=?');$u->execute([$id]);}
        $pdo->commit();
    }catch(Throwable $e){if($pdo->inTransaction())$pdo->rollBack();throw $e;}
    json_response(['ok'=>true,'event_id'=>$id]);
}

if($action!=='nearby')json_response(['ok'=>false,'error'=>'Ação inválida.'],422);
[$lat,$lon]=road_live_coord($body);
$radius=max(1.0,min(30.0,(float)($body['radius_km']??12.0)));
$latDelta=$radius/110.6;$lonDelta=$radius/max(30.0,111.3*cos(deg2rad($lat)));
$q=$pdo->prepare('SELECT id,event_type,latitude,longitude,road,note,confirmations,occurred_at,UNIX_TIMESTAMP(created_at)*1000 AS created_ms,UNIX_TIMESTAMP(expires_at)*1000 AS expires_ms FROM road_live_events WHERE expires_at>NOW() AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? ORDER BY created_at DESC LIMIT 180');
$q->execute([$lat-$latDelta,$lat+$latDelta,$lon-$lonDelta,$lon+$lonDelta]);
$events=[];
while($r=$q->fetch(PDO::FETCH_ASSOC)){
    $d=road_live_distance_m($lat,$lon,(float)$r['latitude'],(float)$r['longitude']);
    if($d>$radius*1000.0)continue;
    $events[]=[
        'id'=>(int)$r['id'],'type'=>(string)$r['event_type'],'lat'=>(float)$r['latitude'],'lon'=>(float)$r['longitude'],
        'road'=>(string)($r['road']??''),'note'=>(string)($r['note']??''),'confirmations'=>(int)$r['confirmations'],
        'occurred_at'=>(int)($r['occurred_at']??0),'created_at'=>(int)($r['created_ms']??0),'expires_at'=>(int)($r['expires_ms']??0),
        'distance_m'=>(int)round($d)
    ];
}
usort($events,fn($a,$b)=>($a['distance_m']<=>$b['distance_m'])?:($b['created_at']<=>$a['created_at']));
$events=array_slice($events,0,80);
json_response(['ok'=>true,'events'=>$events,'radius_km'=>$radius,'server_time'=>round(microtime(true)*1000)]);
