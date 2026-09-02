<?php
declare(strict_types=1);
require_once __DIR__ . '/context_v7_core.php';

function ep7_road_live_recount(int $eventId): array
{
    ep7_ensure_schema();
    $positive=0;$negative=0;
    try {
        $s=db()->prepare('SELECT vote,COUNT(*) c FROM road_live_votes WHERE event_id=? GROUP BY vote');$s->execute([$eventId]);
        foreach ($s->fetchAll() ?: [] as $r) {
            if ((int)$r['vote'] > 0) $positive += (int)$r['c'];
            elseif ((int)$r['vote'] < 0) $negative += (int)$r['c'];
        }
        $u=db()->prepare('UPDATE road_live_events SET confirmations=?,dismissals=?,last_feedback_at=? WHERE id=?');
        $u->execute([$positive,$negative,date('Y-m-d H:i:s'),$eventId]);
    } catch (Throwable $e) {}
    return ['confirmations'=>$positive,'dismissals'=>$negative];
}

function ep7_live_events(array $route, float $corridorM = 6000.0, int $limit = 80): array
{
    ep7_ensure_schema();
    [$minLat,$maxLat,$minLon,$maxLon]=ep7_bbox($route,$corridorM);
    $now=date('Y-m-d H:i:s');
    try {
        $s=db()->prepare('SELECT id,event_type,latitude,longitude,road,note,confirmations,dismissals,heading,speed_kmh,occurred_at,expires_at,created_at FROM road_live_events WHERE expires_at>? AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? ORDER BY created_at DESC LIMIT 300');
        $s->execute([$now,$minLat,$maxLat,$minLon,$maxLon]);
        $out=[];
        foreach ($s->fetchAll() ?: [] as $r) {
            $m=ep7_route_metrics((float)$r['latitude'],(float)$r['longitude'],$route);
            if ($m['lateral_m']>$corridorM) continue;
            $createdMs=(int)(strtotime((string)$r['created_at'])*1000);$expiresMs=(int)(strtotime((string)$r['expires_at'])*1000);
            $cf=ep7_confidence((int)$r['confirmations'],(int)$r['dismissals'],$createdMs,$expiresMs);
            if ($cf['score'] < 0.16) continue;
            $out[]=[
                'id'=>(int)$r['id'],'type'=>(string)$r['event_type'],'lat'=>(float)$r['latitude'],'lon'=>(float)$r['longitude'],
                'road'=>(string)($r['road']??''),'note'=>(string)($r['note']??''),'heading'=>isset($r['heading'])?(int)$r['heading']:null,
                'speed_kmh'=>isset($r['speed_kmh'])?(int)$r['speed_kmh']:null,'confirmations'=>(int)$r['confirmations'],'dismissals'=>(int)$r['dismissals'],
                'confidence'=>$cf,'occurred_at'=>(int)($r['occurred_at']??0),'created_at'=>$createdMs,'expires_at'=>$expiresMs,
                'distance_to_route_m'=>(int)round($m['lateral_m']),'ahead_m'=>(int)round($m['ahead_m'])
            ];
        }
        usort($out,fn($a,$b)=>($a['ahead_m']<=>$b['ahead_m'])?:($b['confidence']['score']<=>$a['confidence']['score']));
        return array_slice($out,0,max(1,min(150,$limit)));
    } catch (Throwable $e) { error_log('EP7 live context: '.$e->getMessage()); return []; }
}

function ep7_hazards(array $route, float $corridorM = 1800.0, int $limit = 220): array
{
    require_once __DIR__ . '/road_hazard_db.php';
    road_hazard_ensure_tables();
    [$minLat,$maxLat,$minLon,$maxLon]=ep7_bbox($route,$corridorM);
    $items=[];$seen=[];
    try {
        $s=db()->prepare('SELECT id,external_id,latitude,longitude,rodovia,heading,sentido,velocidade,tipo,fonte FROM radars WHERE ativo=1 AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT 5000');
        $s->execute([$minLat,$maxLat,$minLon,$maxLon]);
        foreach ($s->fetchAll() ?: [] as $r) {
            $m=ep7_route_metrics((float)$r['latitude'],(float)$r['longitude'],$route); if($m['lateral_m']>$corridorM)continue;
            $key='R:'.(string)($r['external_id']?:$r['id']); if(isset($seen[$key]))continue;$seen[$key]=1;
            $items[]=['id'=>$key,'type'=>strtoupper((string)($r['tipo']??'RADAR')),'lat'=>(float)$r['latitude'],'lon'=>(float)$r['longitude'],'road'=>(string)($r['rodovia']??''),'speed'=>is_numeric($r['velocidade']??null)?(int)$r['velocidade']:null,'heading'=>is_numeric($r['heading']??null)?(float)$r['heading']:null,'source'=>(string)($r['fonte']??'BASE_LOCAL'),'distance_to_route_m'=>(int)round($m['lateral_m']),'ahead_m'=>(int)round($m['ahead_m'])];
        }
    } catch (Throwable $e) {}
    try {
        foreach (road_hazard_bbox_rows($minLat,$maxLat,$minLon,$maxLon,7000) as $r) {
            $m=ep7_route_metrics((float)$r['latitude'],(float)$r['longitude'],$route); if($m['lateral_m']>$corridorM)continue;
            $key='H:'.(string)$r['hazard_key'];if(isset($seen[$key]))continue;$seen[$key]=1;
            $items[]=['id'=>$key,'type'=>(string)$r['type'],'lat'=>(float)$r['latitude'],'lon'=>(float)$r['longitude'],'road'=>(string)($r['road']??''),'speed'=>isset($r['speed'])?(int)$r['speed']:null,'heading'=>isset($r['heading'])?(float)$r['heading']:null,'source'=>(string)($r['source']??'OPENSTREETMAP'),'distance_to_route_m'=>(int)round($m['lateral_m']),'ahead_m'=>(int)round($m['ahead_m'])];
        }
    } catch (Throwable $e) {}
    usort($items,fn($a,$b)=>($a['ahead_m']<=>$b['ahead_m'])?:($a['distance_to_route_m']<=>$b['distance_to_route_m']));
    return array_slice($items,0,max(1,min(500,$limit)));
}

function ep7_traffic_cell_key(string $road, float $lat, float $lon, float $heading): string
{
    $r=strtoupper(trim($road));$r=preg_replace('/[^A-Z0-9-]/','',$r)?:'GEO';
    $la=(int)round($lat*100.0);$lo=(int)round($lon*100.0);
    $bucket=(int)floor((fmod($heading+360.0,360.0)+22.5)/45.0)%8;
    return substr($r,0,32).':'.$la.':'.$lo.':'.$bucket;
}

function ep7_store_traffic_sample(array $body, string $deviceToken): array
{
    ep7_ensure_schema();
    if (!ep7_parse_bool($body['traffic_opt_in'] ?? ($body['opt_in'] ?? false), false)) return ['stored'=>false,'reason'=>'opt_in_required'];
    $lat=(float)($body['lat']??0);$lon=(float)($body['lon']??0);$speed=(float)($body['speed_kmh']??-1);
    if (!ep7_valid_coord($lat,$lon) || !is_finite($speed) || $speed<0 || $speed>190) return ['stored'=>false,'reason'=>'invalid_sample'];
    $deviceHash=ep7_device_hash($deviceToken); if($deviceHash==='')return ['stored'=>false,'reason'=>'device_required'];
    $heading=(float)($body['heading']??0); if(!is_finite($heading))$heading=0.0;
    $limit=isset($body['limit_kmh'])?(int)$body['limit_kmh']:null;if($limit!==null&&($limit<20||$limit>160))$limit=null;
    $accuracy=isset($body['accuracy_m'])?(int)round((float)$body['accuracy_m']):null;if($accuracy!==null&&($accuracy<0||$accuracy>250))$accuracy=null;
    if ($accuracy !== null && $accuracy > 80) return ['stored'=>false,'reason'=>'low_accuracy'];
    $road=strtoupper(trim((string)($body['road']??'')));$road=substr($road,0,60);
    $cell=ep7_traffic_cell_key($road,$lat,$lon,$heading);$now=date('Y-m-d H:i:s');$exp=date('Y-m-d H:i:s',time()+7200);$captured=(int)($body['captured_at']??round(microtime(true)*1000));
    try {
        if(ep7_is_mysql())$sql='INSERT INTO road_traffic_samples (device_hash,cell_key,latitude,longitude,road,heading,speed_kmh,limit_kmh,accuracy_m,captured_at,expires_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE latitude=VALUES(latitude),longitude=VALUES(longitude),road=VALUES(road),heading=VALUES(heading),speed_kmh=VALUES(speed_kmh),limit_kmh=VALUES(limit_kmh),accuracy_m=VALUES(accuracy_m),captured_at=VALUES(captured_at),expires_at=VALUES(expires_at),updated_at=VALUES(updated_at)';
        else $sql='INSERT INTO road_traffic_samples (device_hash,cell_key,latitude,longitude,road,heading,speed_kmh,limit_kmh,accuracy_m,captured_at,expires_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(device_hash,cell_key) DO UPDATE SET latitude=excluded.latitude,longitude=excluded.longitude,road=excluded.road,heading=excluded.heading,speed_kmh=excluded.speed_kmh,limit_kmh=excluded.limit_kmh,accuracy_m=excluded.accuracy_m,captured_at=excluded.captured_at,expires_at=excluded.expires_at,updated_at=excluded.updated_at';
        $s=db()->prepare($sql);$s->execute([$deviceHash,$cell,$lat,$lon,$road!==''?$road:null,(int)round($heading),(int)round($speed),$limit,$accuracy,$captured,$exp,$now,$now]);
        return ['stored'=>true,'cell'=>$cell,'expires_in_s'=>7200];
    } catch(Throwable $e){error_log('EP7 traffic store: '.$e->getMessage());return ['stored'=>false,'reason'=>'storage_error'];}
}

function ep7_traffic_context(array $route, float $corridorM = 3500.0, int $limit = 40): array
{
    ep7_ensure_schema();[$minLat,$maxLat,$minLon,$maxLon]=ep7_bbox($route,$corridorM);$now=date('Y-m-d H:i:s');
    try{$s=db()->prepare('SELECT cell_key,latitude,longitude,road,heading,speed_kmh,limit_kmh,accuracy_m,updated_at FROM road_traffic_samples WHERE expires_at>? AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT 2000');$s->execute([$now,$minLat,$maxLat,$minLon,$maxLon]);$groups=[];
        foreach($s->fetchAll()?:[] as $r){$m=ep7_route_metrics((float)$r['latitude'],(float)$r['longitude'],$route);if($m['lateral_m']>$corridorM)continue;$k=(string)$r['cell_key'];if(!isset($groups[$k]))$groups[$k]=['lat'=>0.0,'lon'=>0.0,'speed'=>0.0,'limit'=>0.0,'limit_n'=>0,'n'=>0,'road'=>(string)($r['road']??''),'heading'=>(int)($r['heading']??0),'ahead_m'=>$m['ahead_m'],'lateral_m'=>$m['lateral_m'],'latest'=>0];$g=&$groups[$k];$g['lat']+=(float)$r['latitude'];$g['lon']+=(float)$r['longitude'];$g['speed']+=(float)$r['speed_kmh'];if((int)($r['limit_kmh']??0)>0){$g['limit']+=(int)$r['limit_kmh'];$g['limit_n']++;}$g['n']++;$g['ahead_m']=min($g['ahead_m'],$m['ahead_m']);$g['lateral_m']=min($g['lateral_m'],$m['lateral_m']);$g['latest']=max($g['latest'],strtotime((string)$r['updated_at'])?:0);unset($g);}
        $out=[];foreach($groups as $k=>$g){$n=max(1,(int)$g['n']);$avg=$g['speed']/$n;$expected=$g['limit_n']>0?$g['limit']/$g['limit_n']:80.0;$ratio=$expected>0?$avg/$expected:1.0;$level=$avg<=8?'PARADO':($ratio<0.38?'MUITO_LENTO':($ratio<0.65?'LENTO':($ratio<0.85?'MODERADO':'LIVRE')));$conf=min(1.0,0.25+$n*0.15);$out[]=['cell'=>$k,'lat'=>round($g['lat']/$n,6),'lon'=>round($g['lon']/$n,6),'road'=>$g['road'],'heading'=>$g['heading'],'avg_speed_kmh'=>round($avg,1),'reference_kmh'=>round($expected,1),'level'=>$level,'samples'=>$n,'confidence'=>round($conf,2),'ahead_m'=>(int)round($g['ahead_m']),'distance_to_route_m'=>(int)round($g['lateral_m']),'updated_at'=>$g['latest']*1000];}
        usort($out,fn($a,$b)=>($a['ahead_m']<=>$b['ahead_m'])?:($b['samples']<=>$a['samples']));return array_slice($out,0,max(1,min(100,$limit)));
    }catch(Throwable $e){error_log('EP7 traffic context: '.$e->getMessage());return [];}
}
