<?php
declare(strict_types=1);
require __DIR__.'/bootstrap.php';
require_login();
require __DIR__.'/road_map_helpers.php';
@set_time_limit(120);
header('Cache-Control: private, max-age=1800');

$lat=(float)($_GET['lat']??0);$lon=(float)($_GET['lon']??0);$heading=(float)($_GET['heading']??-1);
$distance=max(100000,min(300000,(int)($_GET['distance']??250000)));
$width=max(8000,min(22000,(int)($_GET['width']??18000)));
if(!$lat||!$lon||$lat<-90||$lat>90||$lon<-180||$lon>180||$heading<0||$heading>=360){
    json_response(['ok'=>false,'error'=>'Posição ou direção inválida para mapa offline.'],422);
}

$line=[];$step=25000;
for($m=0;$m<=$distance;$m+=$step)$line[]=ep2_destination($lat,$lon,$heading,(float)$m);
if(($distance%$step)!==0)$line[]=ep2_destination($lat,$lon,$heading,(float)$distance);

$features=[];$coordinateCount=0;$osmOk=false;$message='';
try{
    global $config;
    $query=epm_road_query_for_line($line,$width);
    $osm=http_json($config['routing']['overpass_url'],'data='.urlencode($query),['Content-Type: application/x-www-form-urlencoded']);
    if(is_array($osm)){
        [$features,$coordinateCount]=epm_features($osm['elements']??[],12000,180000);
        $osmOk=count($features)>0;
    }
}catch(Throwable $e){$message='Não foi possível atualizar a malha viária agora.';}

$mid=ep2_destination($lat,$lon,$heading,$distance/2.0);
$bounds=epm_bounds_from_line($line,$width);
json_response([
    'ok'=>$osmOk,
    'version'=>'1.0',
    'kind'=>'corridor_map',
    'start'=>['lat'=>$lat,'lon'=>$lon,'heading'=>$heading],
    'center'=>['lat'=>$mid[0],'lon'=>$mid[1]],
    'bounds'=>['south'=>$bounds[0],'west'=>$bounds[1],'north'=>$bounds[2],'east'=>$bounds[3]],
    'distance_m'=>$distance,
    'width_m'=>$width,
    'generated_at'=>gmdate('c'),
    'expires_in_s'=>$osmOk?86400:1800,
    'roads'=>['type'=>'FeatureCollection','features'=>$features],
    'coverage'=>['osm_ok'=>$osmOk,'features'=>count($features),'coordinates'=>$coordinateCount],
    'message'=>$message
],$osmOk?200:502);
