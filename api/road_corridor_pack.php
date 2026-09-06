<?php
declare(strict_types=1);
require __DIR__.'/bootstrap.php';
require_once __DIR__.'/native_auth.php';
if (!native_restore_user_from_request(null)) json_response(['ok'=>false,'error'=>'Sessão expirada. Entre novamente neste aparelho.'],401);
require_once __DIR__.'/road_safety_pack_helpers.php';
require_once __DIR__.'/road_hazard_db.php';
@set_time_limit(110);
header('Cache-Control: private, max-age=900');

$lat=(float)($_GET['lat']??0);$lon=(float)($_GET['lon']??0);$heading=(float)($_GET['heading']??-1);
$distance=max(100000,min(300000,(int)($_GET['distance']??250000)));$width=max(10000,min(30000,(int)($_GET['width']??22000)));
if(!$lat||!$lon||$lat<-90||$lat>90||$lon<-180||$lon>180||$heading<0||$heading>=360)json_response(['ok'=>false,'error'=>'Posição ou direção inválida para reserva da viagem.'],422);

$line=[];$step=25000;for($m=0;$m<=$distance;$m+=$step)$line[]=ep2_destination($lat,$lon,$heading,(float)$m);
$last=end($line);$dest=ep2_destination($lat,$lon,$heading,(float)$distance);if(!is_array($last)||abs((float)$last[0]-(float)$dest[0])>0.00001||abs((float)$last[1]-(float)$dest[1])>0.00001)$line[]=$dest;
$lats=array_column($line,0);$lons=array_column($line,1);$padLat=$width/110540.0;$midLat=array_sum($lats)/max(1,count($lats));$padLon=$width/(111320.0*max(0.25,cos(deg2rad($midLat))));
$minLat=min($lats)-$padLat;$maxLat=max($lats)+$padLat;$minLon=min($lons)-$padLon;$maxLon=max($lons)+$padLon;

$normalizeLocalType=static function($raw): string {
    $v=strtoupper(trim((string)$raw));$v=strtr($v,['Á'=>'A','À'=>'A','Ã'=>'A','Â'=>'A','É'=>'E','Ê'=>'E','Í'=>'I','Ó'=>'O','Ô'=>'O','Õ'=>'O','Ú'=>'U','Ç'=>'C']);
    if(str_contains($v,'QUEBRA')||str_contains($v,'LOMB'))return 'QUEBRA_MOLAS';
    if((str_contains($v,'SEMAFOR')||str_contains($v,'REDLIGHT'))&&(str_contains($v,'RADAR')||str_contains($v,'CAMERA')||str_contains($v,'REDLIGHT')))return 'SEMAFORO_RADAR';
    if(str_contains($v,'SEM')||str_contains($v,'TRAFFIC_SIGNAL'))return 'SEMAFORO';
    if(str_contains($v,'MONITOR')||str_contains($v,'CCTV')||str_contains($v,'SURVEILLANCE')||str_contains($v,'VIDEO'))return 'CAMERA_MONITORAMENTO';
    if(str_contains($v,'PED')||str_contains($v,'TOLL'))return 'PEDAGIO';
    if(str_contains($v,'PASS')||str_contains($v,'LEVEL_CROSSING'))return 'PASSAGEM_NIVEL';
    return 'RADAR';
};

road_hazard_ensure_tables();$items=[];$seen=[];$localCount=0;$storedCount=0;$sourceOk=false;$message='';
try{
    $stmt=db()->prepare('SELECT id,external_id,latitude,longitude,rodovia,heading,sentido,velocidade,tipo,fonte FROM radars WHERE ativo=1 AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT 30000');
    $stmt->execute([$minLat,$maxLat,$minLon,$maxLon]);
    foreach(($stmt->fetchAll()?:[]) as $r){$rlat=(float)$r['latitude'];$rlon=(float)$r['longitude'];if(ep2_distance_to_line($rlat,$rlon,$line)>$width+1200)continue;ep2_add($items,$seen,[
        'id'=>!empty($r['external_id'])?(string)$r['external_id']:'db-point-'.(string)$r['id'],'type'=>$normalizeLocalType($r['tipo']??'RADAR'),'lat'=>$rlat,'lon'=>$rlon,'road'=>$r['rodovia']??'',
        'speed'=>ep2_speed($r['velocidade']??null),'heading'=>is_numeric($r['heading']??null)?(float)$r['heading']:ep2_heading($r['sentido']??null),'source'=>$r['fonte']??'BASE_LOCAL']);$localCount++;}
}catch(Throwable $e){$message='Proteção local temporariamente indisponível.';}

try{
    foreach(road_hazard_bbox_rows($minLat,$maxLat,$minLon,$maxLon,80000) as $h){$hlat=(float)$h['latitude'];$hlon=(float)$h['longitude'];if(ep2_distance_to_line($hlat,$hlon,$line)>$width+1200)continue;ep2_add($items,$seen,[
        'id'=>(string)$h['hazard_key'],'type'=>(string)$h['type'],'lat'=>$hlat,'lon'=>$hlon,'road'=>$h['road']??'','speed'=>$h['speed']??null,'heading'=>$h['heading']??null,'source'=>$h['source']??'OPENSTREETMAP']);$storedCount++;}
    if($storedCount>0)$sourceOk=true;
}catch(Throwable $e){$message=$message!==''?$message:'Proteção nacional temporariamente indisponível.';}

if($storedCount===0){
    try{$osm=road_hazard_overpass_request(ep2_osm_query_for_line($line,$width),80);if(is_array($osm)){$sourceOk=true;foreach(($osm['elements']??[]) as $el){if(count($items)>=50000)break;$h=ep2_osm_to_hazard($el);if($h===null)continue;if(ep2_distance_to_line((float)$h['lat'],(float)$h['lon'],$line)>$width+1200)continue;ep2_add($items,$seen,$h);}}else{$message=$message!==''?$message:'Não foi possível atualizar o corredor agora.';}}catch(Throwable $e){$message=$message!==''?$message:'Não foi possível atualizar o corredor agora.';}
}

$mid=ep2_destination($lat,$lon,$heading,$distance/2.0);$ok=count($items)>0||$sourceOk;$typeCounts=[];foreach($items as $h){$t=(string)($h['type']??'OUTRO');$typeCounts[$t]=($typeCounts[$t]??0)+1;}
json_response([
    'ok'=>$ok,'version'=>'2.0','kind'=>'corridor','start'=>['lat'=>$lat,'lon'=>$lon,'heading'=>$heading],'center'=>['lat'=>$mid[0],'lon'=>$mid[1]],
    'distance_m'=>$distance,'width_m'=>$width,'generated_at'=>gmdate('c'),'expires_in_s'=>$storedCount>0?604800:86400,'hazards'=>$items,
    'coverage'=>['local_points'=>$localCount,'national_points'=>$storedCount,'source_ok'=>$sourceOk,'total'=>count($items),'by_type'=>$typeCounts,'truncated'=>count($items)>=50000],
    'message'=>$message
],$ok?200:502);
