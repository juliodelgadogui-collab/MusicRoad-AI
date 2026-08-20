<?php
require __DIR__ . '/bootstrap.php';
require_login();

$body = input_json();
$coords = is_array($body['coords'] ?? null) ? $body['coords'] : [];
if (count($coords) < 2) json_response(['ok'=>false,'error'=>'Rota inválida para consulta de fiscalização.'],422);

$clean=[];
foreach($coords as $p){
    if(!is_array($p) || count($p)<2) continue;
    $lon=(float)$p[0]; $lat=(float)$p[1];
    if($lat < -90 || $lat > 90 || $lon < -180 || $lon > 180) continue;
    $clean[]=[$lon,$lat];
}
if(count($clean)<2) json_response(['ok'=>false,'error'=>'Rota sem coordenadas válidas.'],422);

// Amostra a geometria para manter a checagem rápida mesmo em rotas longas.
$sample=[];$n=count($clean);$max=160;
if($n<=$max){$sample=$clean;}else{
    $step=($n-1)/($max-1);
    for($i=0;$i<$max;$i++)$sample[]=$clean[(int)round($i*$step)];
}

$minLat=90;$maxLat=-90;$minLon=180;$maxLon=-180;
foreach($sample as $p){$minLon=min($minLon,$p[0]);$maxLon=max($maxLon,$p[0]);$minLat=min($minLat,$p[1]);$maxLat=max($maxLat,$p[1]);}
$margin=.03; // ~3 km de folga para o corredor da rota.
$stmt=db()->prepare('SELECT * FROM radars WHERE ativo = 1 AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT 3000');
$stmt->execute([$minLat-$margin,$maxLat+$margin,$minLon-$margin,$maxLon+$margin]);
$candidates=$stmt->fetchAll();

$items=[];$radius=1800.0;
foreach($candidates as $r){
    $lat=(float)$r['latitude'];$lon=(float)$r['longitude'];$best=INF;
    foreach($sample as $p){
        $d=haversine_m($lat,$lon,(float)$p[1],(float)$p[0]);
        if($d<$best)$best=$d;
        if($best<=$radius)break;
    }
    if($best<=$radius){$r['distance_to_route_m']=(int)round($best);$items[]=$r;}
}
usort($items,fn($a,$b)=>($a['distance_to_route_m']??0)<=>($b['distance_to_route_m']??0));
json_response(['ok'=>true,'radars'=>$items,'points'=>$items,'mode'=>'local','count'=>count($items)]);
