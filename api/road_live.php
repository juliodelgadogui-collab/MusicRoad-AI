<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';
require_once __DIR__ . '/server_context_v7.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

$body = input_json();
$user = native_require_json_user($body);
ep7_ensure_schema();
ep7_housekeeping(false);

$action = strtolower(trim((string)($body['action'] ?? ($_GET['action'] ?? 'nearby'))));
$device = native_device_token_from_request($body);
$userId = (int)($user['id'] ?? 0);
$allowed = ['pothole','animal','accident','construction','flooding','traffic','object'];
$ttl = ['pothole'=>2592000,'animal'=>2700,'accident'=>10800,'construction'=>43200,'flooding'=>14400,'traffic'=>3600,'object'=>3600];

function ep7_live_coord(array $body): array
{
    $lat=(float)($body['lat']??0);$lon=(float)($body['lon']??0);
    if(!ep7_valid_coord($lat,$lon)) json_response(['ok'=>false,'error'=>'Coordenada inválida.'],422);
    return [$lat,$lon];
}

function ep7_live_row_payload(array $row,float $fromLat,float $fromLon): array
{
    $createdMs=(int)((strtotime((string)$row['created_at'])?:time())*1000);
    $expiresMs=(int)((strtotime((string)$row['expires_at'])?:time())*1000);
    $cf=ep7_confidence((int)($row['confirmations']??0),(int)($row['dismissals']??0),$createdMs,$expiresMs);
    return [
        'id'=>(int)$row['id'],'type'=>(string)$row['event_type'],'lat'=>(float)$row['latitude'],'lon'=>(float)$row['longitude'],
        'road'=>(string)($row['road']??''),'note'=>(string)($row['note']??''),'heading'=>isset($row['heading'])?(int)$row['heading']:null,
        'speed_kmh'=>isset($row['speed_kmh'])?(int)$row['speed_kmh']:null,'confirmations'=>(int)($row['confirmations']??0),'dismissals'=>(int)($row['dismissals']??0),
        'confidence'=>$cf,'occurred_at'=>(int)($row['occurred_at']??0),'created_at'=>$createdMs,'expires_at'=>$expiresMs,
        'distance_m'=>(int)round(haversine_m($fromLat,$fromLon,(float)$row['latitude'],(float)$row['longitude']))
    ];
}

if($action==='report'){
    [$lat,$lon]=ep7_live_coord($body);
    $type=strtolower(trim((string)($body['event_type']??'')));
    if(!in_array($type,$allowed,true)) json_response(['ok'=>false,'error'=>'Tipo de alerta inválido.'],422);
    $road=strtoupper(trim((string)($body['road']??'')));$note=trim((string)($body['note']??''));
    $road=substr($road,0,40);$note=function_exists('mb_substr')?mb_substr($note,0,160):substr($note,0,160);
    $heading=isset($body['heading'])?(int)round((float)$body['heading']):null;if($heading!==null)$heading=(($heading%360)+360)%360;
    $speed=isset($body['speed_kmh'])?(int)round((float)$body['speed_kmh']):null;if($speed!==null&&($speed<0||$speed>190))$speed=null;
    $occurred=(int)($body['occurred_at']??round(microtime(true)*1000));$seconds=(int)($ttl[$type]??3600);$cut=date('Y-m-d H:i:s',time()-300);

    try{
        $q=db()->prepare("SELECT id,latitude,longitude FROM road_live_events WHERE event_type=? AND COALESCE(device_token,'')=? AND created_at>=? ORDER BY id DESC LIMIT 20");
        $q->execute([$type,$device,$cut]);
        foreach($q->fetchAll()?:[] as $r){
            if(haversine_m($lat,$lon,(float)$r['latitude'],(float)$r['longitude'])>120)continue;
            $id=(int)$r['id'];$u=db()->prepare('UPDATE road_live_events SET road=?,note=?,heading=?,speed_kmh=?,occurred_at=?,expires_at=? WHERE id=?');
            $u->execute([$road!==''?$road:null,$note!==''?$note:null,$heading,$speed,$occurred,date('Y-m-d H:i:s',time()+$seconds),$id]);
            json_response(['ok'=>true,'id'=>$id,'duplicate'=>true,'confidence'=>ep7_confidence(0,0,(int)round(microtime(true)*1000),(int)round((microtime(true)+$seconds)*1000))]);
        }
    }catch(Throwable $e){}

    try{
        $q=db()->prepare('INSERT INTO road_live_events (user_id,device_token,event_type,latitude,longitude,road,note,confirmations,dismissals,heading,speed_kmh,occurred_at,expires_at,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)');
        $q->execute([$userId?:null,$device!==''?$device:null,$type,$lat,$lon,$road!==''?$road:null,$note!==''?$note:null,0,0,$heading,$speed,$occurred,date('Y-m-d H:i:s',time()+$seconds),date('Y-m-d H:i:s')]);
        $id=(int)db()->lastInsertId();if(function_exists('audit_log'))audit_log('road_live.report',['id'=>$id,'type'=>$type]);
        json_response(['ok'=>true,'id'=>$id,'stored'=>true,'expires_in_s'=>$seconds]);
    }catch(Throwable $e){error_log('ROAD_LIVE report: '.$e->getMessage());json_response(['ok'=>false,'error'=>'Não foi possível registrar o alerta agora.'],503);}
}

if(in_array($action,['confirm','dismiss'],true)){
    $id=(int)($body['event_id']??0);
    if($id<=0||$device==='')json_response(['ok'=>false,'error'=>'Feedback inválido.'],422);
    $q=db()->prepare('SELECT id,device_token,created_at,expires_at FROM road_live_events WHERE id=? AND expires_at>? LIMIT 1');$q->execute([$id,date('Y-m-d H:i:s')]);$event=$q->fetch();
    if(!$event)json_response(['ok'=>false,'error'=>'Alerta não encontrado ou expirado.'],404);
    if(hash_equals((string)($event['device_token']??''),$device))json_response(['ok'=>true,'event_id'=>$id,'ignored'=>'author_vote']);
    $hash=ep7_device_hash($device);$vote=$action==='confirm'?1:-1;$now=date('Y-m-d H:i:s');
    try{
        if(ep7_is_mysql())$sql='INSERT INTO road_live_votes (event_id,device_hash,vote,created_at,updated_at) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE vote=VALUES(vote),updated_at=VALUES(updated_at)';
        else$sql='INSERT INTO road_live_votes (event_id,device_hash,vote,created_at,updated_at) VALUES (?,?,?,?,?) ON CONFLICT(event_id,device_hash) DO UPDATE SET vote=excluded.vote,updated_at=excluded.updated_at';
        $s=db()->prepare($sql);$s->execute([$id,$hash,$vote,$now,$now]);$counts=ep7_road_live_recount($id);
        $cf=ep7_confidence($counts['confirmations'],$counts['dismissals'],(int)((strtotime((string)$event['created_at'])?:time())*1000),(int)((strtotime((string)$event['expires_at'])?:time())*1000));
        if(function_exists('audit_log'))audit_log('road_live.feedback',['id'=>$id,'vote'=>$vote]);
        json_response(['ok'=>true,'event_id'=>$id,'vote'=>$vote,'confirmations'=>$counts['confirmations'],'dismissals'=>$counts['dismissals'],'confidence'=>$cf]);
    }catch(Throwable $e){error_log('ROAD_LIVE feedback: '.$e->getMessage());json_response(['ok'=>false,'error'=>'Não foi possível registrar a confirmação agora.'],503);}
}

if($action!=='nearby')json_response(['ok'=>false,'error'=>'Ação inválida.'],422);
[$lat,$lon]=ep7_live_coord($body);
$radius=max(1.0,min(30.0,(float)($body['radius_km']??($_GET['radius_km']??12.0))));
$route=[['lat'=>$lat,'lon'=>$lon]];$events=ep7_live_events($route,$radius*1000.0,100);
foreach($events as &$e){$e['distance_m']=(int)round(haversine_m($lat,$lon,(float)$e['lat'],(float)$e['lon']));unset($e['distance_to_route_m'],$e['ahead_m']);}unset($e);
usort($events,fn($a,$b)=>($a['distance_m']<=>$b['distance_m'])?:($b['confidence']['score']<=>$a['confidence']['score']));
json_response(['ok'=>true,'version'=>'7.0','events'=>$events,'radius_km'=>$radius,'server_time'=>round(microtime(true)*1000)]);
