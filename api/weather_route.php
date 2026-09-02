<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';
require_once __DIR__ . '/server_context_v7.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: private, max-age=300');

$body=input_json();
$user=native_require_json_user($body);
$lat=(float)($body['lat']??($_GET['lat']??0));$lon=(float)($body['lon']??($_GET['lon']??0));
if(!ep7_valid_coord($lat,$lon)) json_response(['ok'=>false,'error'=>'Coordenada inválida.'],422);
$route=ep7_route_points($body['route_points']??[],$lat,$lon);
$speed=(float)($body['speed_kmh']??80);
try{
    $weather=ep7_route_weather($route,$speed,5);
    json_response(['ok'=>true,'version'=>'7.0','weather'=>$weather,'route'=>['points'=>count($route),'distance_m'=>(int)round(ep7_route_distance($route))],'cache_ttl_s'=>900,'source'=>'open-meteo']);
}catch(Throwable $e){error_log('WEATHER_ROUTE_V7: '.$e->getMessage());json_response(['ok'=>false,'error'=>'Não foi possível consultar o clima da rota agora.'],503);}
