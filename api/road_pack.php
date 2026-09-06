<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';
if (!native_restore_user_from_request(null)) json_response(['ok'=>false,'error'=>'Sessão expirada. Entre novamente neste aparelho.'],401);
require_once __DIR__.'/road_safety_pack_helpers.php';
require_once __DIR__.'/road_hazard_db.php';

header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
$lat=(float)($_GET['lat']??0);$lon=(float)($_GET['lon']??0);$radius=max(5000,min(30000,(int)($_GET['radius']??16000)));
if(!$lat||!$lon||$lat<-90||$lat>90||$lon<-180||$lon>180)json_response(['ok'=>false,'error'=>'Localização inválida.'],422);

$latBox=$radius/110540.0;$lonScale=max(0.25,cos(deg2rad($lat)));$lonBox=$radius/(111320.0*$lonScale);
$minLat=$lat-$latBox;$maxLat=$lat+$latBox;$minLon=$lon-$lonBox;$maxLon=$lon+$lonBox;
$items=[];$seen=[];$localCount=0;$storedCount=0;$liveFallback=false;

$addNear=static function(array $h) use (&$items,&$seen,$lat,$lon,$radius): void {
    if(!isset($h['lat'],$h['lon'])||!is_numeric($h['lat'])||!is_numeric($h['lon']))return;
    if(ep2_haversine($lat,$lon,(float)$h['lat'],(float)$h['lon'])>$radius+900)return;
    ep2_add($items,$seen,$h);
};
$normalizeLocalType=static function($raw): string {
    $v=strtoupper(trim((string)$raw));
    $v=strtr($v,['Á'=>'A','À'=>'A','Ã'=>'A','Â'=>'A','É'=>'E','Ê'=>'E','Í'=>'I','Ó'=>'O','Ô'=>'O','Õ'=>'O','Ú'=>'U','Ç'=>'C']);
    if(str_contains($v,'QUEBRA')||str_contains($v,'LOMB'))return 'QUEBRA_MOLAS';
    if((str_contains($v,'SEMAFOR')||str_contains($v,'REDLIGHT'))&&(str_contains($v,'RADAR')||str_contains($v,'CAMERA')||str_contains($v,'REDLIGHT')))return 'SEMAFORO_RADAR';
    if(str_contains($v,'SEMAFOR')||str_contains($v,'TRAFFIC_SIGNAL'))return 'SEMAFORO';
    if(str_contains($v,'MONITOR')||str_contains($v,'CCTV')||str_contains($v,'SURVEILLANCE')||str_contains($v,'VIDEO'))return 'CAMERA_MONITORAMENTO';
    if(str_contains($v,'PEDAG')||str_contains($v,'TOLL'))return 'PEDAGIO';
    if(str_contains($v,'PASSAGEM')||str_contains($v,'LEVEL_CROSSING'))return 'PASSAGEM_NIVEL';
    return 'RADAR';
};

// Imported/official/proprietary records have priority in deduplication.
try{
    $stmt=db()->prepare('SELECT id,external_id,latitude,longitude,rodovia,heading,sentido,velocidade,tipo,fonte FROM radars WHERE ativo=1 AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT 10000');
    $stmt->execute([$minLat,$maxLat,$minLon,$maxLon]);
    foreach(($stmt->fetchAll()?:[]) as $r){
        $addNear([
            'id'=>!empty($r['external_id'])?(string)$r['external_id']:'db-point-'.(string)$r['id'],
            'type'=>$normalizeLocalType($r['tipo']??'RADAR'),'lat'=>(float)$r['latitude'],'lon'=>(float)$r['longitude'],
            'road'=>$r['rodovia']??'','speed'=>ep2_speed($r['velocidade']??null),
            'heading'=>is_numeric($r['heading']??null)?(float)$r['heading']:ep2_heading($r['sentido']??null),
            'source'=>$r['fonte']??'BASE_LOCAL'
        ]);$localCount++;
    }
}catch(Throwable $e){}

// Permanent national OSM/aggregated database. This is the fast path after cron/import sync.
try{
    foreach(road_hazard_bbox_rows($minLat,$maxLat,$minLon,$maxLon,50000) as $h){
        $addNear([
            'id'=>(string)$h['hazard_key'],'type'=>(string)$h['type'],'lat'=>(float)$h['latitude'],'lon'=>(float)$h['longitude'],
            'road'=>$h['road']??'','speed'=>$h['speed']??null,'heading'=>$h['heading']??null,'source'=>$h['source']??'OPENSTREETMAP'
        ]);$storedCount++;
    }
}catch(Throwable $e){}

// Resilience fallback: if this region has not been synchronized yet, obtain a local snapshot.
if($storedCount===0){
    try{
        $osm=road_hazard_overpass_request(ep2_osm_query_for_bbox($minLat,$minLon,$maxLat,$maxLon,35),45);
        if(is_array($osm)){
            $liveFallback=true;
            foreach(($osm['elements']??[]) as $el){
                if(count($items)>=12000)break;
                $h=ep2_osm_to_hazard($el);if($h!==null)$addNear($h);
            }
        }
    }catch(Throwable $e){}
}

usort($items,static fn(array $a,array $b): int => ep2_haversine($lat,$lon,(float)$a['lat'],(float)$a['lon']) <=> ep2_haversine($lat,$lon,(float)$b['lat'],(float)$b['lon']));
$typeCounts=[];foreach($items as $h){$t=(string)($h['type']??'OUTRO');$typeCounts[$t]=($typeCounts[$t]??0)+1;}

json_response([
    'ok'=>true,'version'=>'2.0','center'=>['lat'=>$lat,'lon'=>$lon],'radius_m'=>$radius,
    'generated_at'=>gmdate('c'),'expires_in_s'=>$storedCount>0?604800:86400,'hazards'=>$items,
    'coverage'=>[
        'local_points'=>$localCount,'national_points'=>$storedCount,'fallback_used'=>$liveFallback,
        'source_ok'=>$storedCount>0||$liveFallback,'total'=>count($items),'by_type'=>$typeCounts,'truncated'=>count($items)>=12000
    ]
]);
