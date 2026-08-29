<?php
declare(strict_types=1);
require __DIR__.'/bootstrap.php';
require_once __DIR__.'/native_auth.php';
if (!native_restore_user_from_request(null)) json_response(['ok'=>false,'error'=>'Sessão expirada. Entre novamente neste aparelho.'],401);
require __DIR__.'/road_safety_pack_helpers.php';
require __DIR__.'/road_hazard_db.php';
@set_time_limit(110);
header('Cache-Control: private, max-age=900');

$lat=(float)($_GET['lat']??0);$lon=(float)($_GET['lon']??0);$heading=(float)($_GET['heading']??-1);
$distance=max(100000,min(300000,(int)($_GET['distance']??250000)));
$width=max(10000,min(30000,(int)($_GET['width']??22000)));
if(!$lat||!$lon||$lat<-90||$lat>90||$lon<-180||$lon>180||$heading<0||$heading>=360){
    json_response(['ok'=>false,'error'=>'Posição ou direção inválida para reserva da viagem.'],422);
}

$line=[];$step=25000;
for($m=0;$m<=$distance;$m+=$step)$line[]=ep2_destination($lat,$lon,$heading,(float)$m);
$last=end($line);$dest=ep2_destination($lat,$lon,$heading,(float)$distance);
if(!is_array($last)||abs((float)$last[0]-(float)$dest[0])>0.00001||abs((float)$last[1]-(float)$dest[1])>0.00001)$line[]=$dest;

$lats=array_column($line,0);$lons=array_column($line,1);
$padLat=$width/110540.0;
$midLat=array_sum($lats)/max(1,count($lats));
$padLon=$width/(111320.0*max(0.25,cos(deg2rad($midLat))));
$minLat=min($lats)-$padLat;$maxLat=max($lats)+$padLat;$minLon=min($lons)-$padLon;$maxLon=max($lons)+$padLon;
$normalizeLocalType=static function($raw): string {
    $v=strtoupper(trim((string)$raw));
    if(stripos($v,'QUEBRA')!==false||stripos($v,'LOMB')!==false)return 'QUEBRA_MOLAS';
    if(stripos($v,'SEM')===0)return 'SEMAFORO';
    if(stripos($v,'PED')===0)return 'PEDAGIO';
    if(stripos($v,'PASS')===0)return 'PASSAGEM_NIVEL';
    return 'RADAR';
};

road_hazard_ensure_tables();
$items=[];$seen=[];$localCount=0;$storedCount=0;$osmOk=false;$message='';
try{
    $stmt=db()->prepare('SELECT id, external_id, latitude, longitude, rodovia, heading, sentido, velocidade, tipo, fonte FROM radars WHERE ativo = 1 AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT 20000');
    $stmt->execute([$minLat,$maxLat,$minLon,$maxLon]);
    foreach(($stmt->fetchAll()?:[]) as $r){
        $rlat=(float)$r['latitude'];$rlon=(float)$r['longitude'];
        if(ep2_distance_to_line($rlat,$rlon,$line)>$width+1200)continue;
        ep2_add($items,$seen,[
            'id'=>!empty($r['external_id'])?(string)$r['external_id']:'db-radar-'.(string)$r['id'],
            'type'=>$normalizeLocalType($r['tipo']??'RADAR'),'lat'=>$rlat,'lon'=>$rlon,'road'=>$r['rodovia']??'',
            'speed'=>ep2_speed($r['velocidade']??null),
            'heading'=>is_numeric($r['heading']??null)?(float)$r['heading']:ep2_heading($r['sentido']??null),
            'source'=>$r['fonte']??'BASE_LOCAL'
        ]);
        $localCount++;
    }
}catch(Throwable $e){$message='Base local indisponível.';}

try{
    foreach(road_hazard_bbox_rows($minLat,$maxLat,$minLon,$maxLon) as $h){
        $hlat=(float)$h['latitude'];$hlon=(float)$h['longitude'];
        if(ep2_distance_to_line($hlat,$hlon,$line)>$width+1200)continue;
        ep2_add($items,$seen,[
            'id'=>(string)$h['hazard_key'],'type'=>(string)$h['type'],'lat'=>$hlat,'lon'=>$hlon,
            'road'=>$h['road']??'','speed'=>$h['speed']??null,'heading'=>$h['heading']??null,'source'=>$h['source']??'OPENSTREETMAP'
        ]);
        $storedCount++;
    }
}catch(Throwable $e){$message=$message!==''?$message:'Base unificada de alertas indisponível.';}

if($storedCount===0){
    try{
        $query=ep2_osm_query_for_line($line,$width);
        $osm=ep2_overpass_json($query,80);
        if(is_array($osm)){
            $osmOk=true;
            foreach(($osm['elements']??[]) as $el){
                if(count($items)>=30000)break;
                $h=ep2_osm_to_hazard($el);if($h===null)continue;
                if(ep2_distance_to_line((float)$h['lat'],(float)$h['lon'],$line)>$width+1200)continue;
                ep2_add($items,$seen,$h);
            }
        }else{$message=$message!==''?$message:'OpenStreetMap não respondeu a tempo; mantendo a base local.';}
    }catch(Throwable $e){$message=$message!==''?$message:'Falha ao atualizar o corredor.';}
}else{
    $osmOk=true;
}

$mid=ep2_destination($lat,$lon,$heading,$distance/2.0);
$ok=count($items)>0||$osmOk;
json_response([
    'ok'=>$ok,
    'version'=>'1.2',
    'kind'=>'corridor',
    'start'=>['lat'=>$lat,'lon'=>$lon,'heading'=>$heading],
    'center'=>['lat'=>$mid[0],'lon'=>$mid[1]],
    'distance_m'=>$distance,
    'width_m'=>$width,
    'generated_at'=>gmdate('c'),
    'expires_in_s'=>$storedCount>0?86400:($osmOk?86400:3600),
    'hazards'=>$items,
    'coverage'=>[
        'local_radars'=>$localCount,
        'stored_hazards'=>$storedCount,
        'osm_ok'=>$osmOk,
        'total'=>count($items),
        'truncated'=>count($items)>=30000
    ],
    'message'=>$message
],$ok?200:502);
