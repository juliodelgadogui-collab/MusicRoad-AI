<?php
declare(strict_types=1);
require_once __DIR__.'/bootstrap.php';
require_once __DIR__.'/native_auth.php';
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

$body=input_json();
$user=native_require_json_user($body);
$pdo=db();
$pdo->exec("CREATE TABLE IF NOT EXISTS road_collective_events (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NULL,
 device_token VARCHAR(160) NULL,
 event_type VARCHAR(40) NOT NULL,
 latitude DECIMAL(10,7) NOT NULL,
 longitude DECIMAL(10,7) NOT NULL,
 speed_kmh DECIMAL(7,2) NULL,
 severity DECIMAL(7,2) NULL,
 radar_id VARCHAR(190) NULL,
 road VARCHAR(190) NULL,
 limit_kmh SMALLINT NULL,
 source VARCHAR(190) NULL,
 answer VARCHAR(24) NULL,
 occurred_at BIGINT NULL,
 created_at DATETIME NOT NULL,
 INDEX idx_collective_geo (latitude,longitude),
 INDEX idx_collective_type_time (event_type,created_at),
 INDEX idx_collective_radar (radar_id),
 INDEX idx_collective_device (device_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

$type=strtolower(trim((string)($body['event_type']??'')));
$allowed=['road_surface','radar_confirmation'];
if(!in_array($type,$allowed,true))json_response(['ok'=>false,'error'=>'Evento inválido.'],422);
$lat=(float)($body['lat']??0);$lon=(float)($body['lon']??0);
if(!is_finite($lat)||!is_finite($lon)||$lat < -35||$lat > 6||$lon < -75||$lon > -30)json_response(['ok'=>false,'error'=>'Coordenada inválida.'],422);
$speed=isset($body['speed_kmh'])?(float)$body['speed_kmh']:null;if($speed!==null&&($speed<0||$speed>260))$speed=null;
$severity=isset($body['severity'])?(float)$body['severity']:null;if($severity!==null)$severity=max(0,min(10,$severity));
$radarId=trim((string)($body['radar_id']??''));$road=trim((string)($body['road']??''));$source=trim((string)($body['source']??''));$answer=strtolower(trim((string)($body['answer']??'')));
$limit=isset($body['limit_kmh'])?(int)$body['limit_kmh']:null;if($limit!==null&&($limit<10||$limit>180))$limit=null;
if($type==='radar_confirmation'&&!in_array($answer,['exists','removed','unknown'],true))$answer='unknown';
if($type==='road_surface'){$radarId='';$answer='';}
$radarId=substr($radarId,0,190);$road=substr($road,0,190);$source=substr($source,0,190);
$occurred=isset($body['occurred_at'])?(int)$body['occurred_at']:(isset($body['confirmed_at'])?(int)$body['confirmed_at']:round(microtime(true)*1000));
$device=native_device_token_from_request($body);$userId=(int)($user['id']??0);

// Compact dedupe: same device/type in roughly the same 11 m cell within 10 minutes.
try{
 $q=$pdo->prepare("SELECT id FROM road_collective_events WHERE event_type=? AND COALESCE(device_token,'')=? AND ABS(latitude-?)<0.00010 AND ABS(longitude-?)<0.00010 AND created_at>=DATE_SUB(NOW(),INTERVAL 10 MINUTE) ORDER BY id DESC LIMIT 1");
 $q->execute([$type,$device,$lat,$lon]);$existing=(int)($q->fetchColumn()?:0);
 if($existing>0)json_response(['ok'=>true,'id'=>$existing,'duplicate'=>true]);
}catch(Throwable $e){}

try{
 $q=$pdo->prepare('INSERT INTO road_collective_events (user_id,device_token,event_type,latitude,longitude,speed_kmh,severity,radar_id,road,limit_kmh,source,answer,occurred_at,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())');
 $q->execute([$userId?:null,$device!==''?$device:null,$type,$lat,$lon,$speed,$severity,$radarId!==''?$radarId:null,$road!==''?$road:null,$limit,$source!==''?$source:null,$answer!==''?$answer:null,$occurred]);
 $id=(int)$pdo->lastInsertId();
 if(function_exists('audit_log'))audit_log('road_collective.submit',['id'=>$id,'type'=>$type]);
 json_response(['ok'=>true,'id'=>$id,'stored'=>true]);
}catch(Throwable $e){error_log('ROAD_COLLECTIVE: '.$e->getMessage());json_response(['ok'=>false,'error'=>'Não foi possível salvar o evento agora.'],503);}
