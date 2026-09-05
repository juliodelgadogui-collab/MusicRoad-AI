<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
require_login();
require __DIR__ . '/radar_route_helpers.php';

header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');

$raw=(string)file_get_contents('php://input');
$body=json_decode($raw,true);
$coords=is_array($body)?($body['coordinates']??null):null;
if(!is_array($coords)||count($coords)<2) json_response(['ok'=>false,'error'=>'Rota inválida para consultar alertas.'],422);

// Limit payload/work while preserving the complete corridor shape.
if(count($coords)>5000){
    $step=(int)ceil(count($coords)/5000);$sampled=[];
    for($i=0,$n=count($coords);$i<$n;$i+=$step)$sampled[]=$coords[$i];
    $last=$coords[count($coords)-1];if(end($sampled)!=$last)$sampled[]=$last;$coords=$sampled;
}
$clean=[];
foreach($coords as $c){
    if(!is_array($c)||count($c)<2||!is_numeric($c[0])||!is_numeric($c[1]))continue;
    $lon=(float)$c[0];$lat=(float)$c[1];if($lat<-90||$lat>90||$lon<-180||$lon>180)continue;$clean[]=[$lon,$lat];
}
if(count($clean)<2) json_response(['ok'=>false,'error'=>'Geometria da rota inválida.'],422);
$coords=$clean;

$lats=array_column($coords,1);$lons=array_column($coords,0);$pad=0.025;
$minLat=min($lats)-$pad;$maxLat=max($lats)+$pad;$minLon=min($lons)-$pad;$maxLon=max($lons)+$pad;

$stmt=db()->prepare('SELECT * FROM radars WHERE ativo = 1 AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT 10000');
$stmt->execute([$minLat,$maxLat,$minLon,$maxLon]);$local=$stmt->fetchAll()?:[];
foreach($local as &$r){if(empty($r['fonte']))$r['fonte']='BASE_LOCAL';}unset($r);

// OSM is intentionally asynchronous relative to route drawing in the app.
$osmResult=mr_osm_radars_for_route($coords,850);$osm=$osmResult['items']??[];
foreach($osm as $r){try{mr_upsert_osm_radar($r);}catch(Throwable $e){}}
$merged=mr_dedupe_radars(array_merge($local,$osm));
[$metricRoute,$cum]=mr_route_metrics($coords);$onRoute=[];
foreach($merged as $r){
    $m=mr_radar_position_on_route($r,$metricRoute,$cum);
    // Narrower corridor than the old 900 m threshold to reduce parallel-road alerts.
    if($m['distance_to_route_m']>180)continue;
    $r['distance_to_route_m']=(int)round($m['distance_to_route_m']);$r['route_m']=(int)round($m['route_m']);$r['velocidade']=mr_parse_speed($r['velocidade']??null);$onRoute[]=$r;
}
usort($onRoute,fn($a,$b)=>((int)($a['route_m']??0))<=>((int)($b['route_m']??0)));
json_response(['ok'=>true,'radars'=>$onRoute,'version'=>'1.6.2','corridor_m'=>180,'coverage'=>['local_candidates'=>count($local),'osm_candidates'=>count($osm),'merged_candidates'=>count($merged),'on_route'=>count($onRoute),'osm_ok'=>(bool)($osmResult['ok']??false)]]);
