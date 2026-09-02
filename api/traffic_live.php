<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';
require_once __DIR__ . '/server_context_v7.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

$body=input_json();
$user=native_require_json_user($body);
$device=native_device_token_from_request($body);
$action=strtolower(trim((string)($body['action']??($_GET['action']??'nearby'))));
ep7_ensure_schema();ep7_housekeeping(false);

if($action==='sample'){
    $result=ep7_store_traffic_sample($body,$device);
    if(empty($result['stored'])&&($result['reason']??'')==='opt_in_required') json_response(['ok'=>false,'error'=>'O envio colaborativo de trânsito exige consentimento explícito.','privacy'=>['device_id_stored'=>false,'retention_h'=>2]],403);
    if(empty($result['stored'])) json_response(['ok'=>false,'error'=>'A amostra de trânsito não pôde ser usada.','reason'=>$result['reason']??'invalid_sample'],422);
    json_response(['ok'=>true,'version'=>'7.0','sample'=>$result,'privacy'=>['device_id_stored'=>false,'device_hash_only'=>true,'retention_h'=>2]]);
}

if($action!=='nearby') json_response(['ok'=>false,'error'=>'Ação inválida.'],422);
$lat=(float)($body['lat']??($_GET['lat']??0));$lon=(float)($body['lon']??($_GET['lon']??0));
if(!ep7_valid_coord($lat,$lon)) json_response(['ok'=>false,'error'=>'Coordenada inválida.'],422);
$route=ep7_route_points($body['route_points']??[],$lat,$lon);
$traffic=ep7_traffic_context($route,(float)max(1500,min(8000,(int)($body['corridor_m']??3500))),80);
json_response(['ok'=>true,'version'=>'7.0','traffic'=>$traffic,'route'=>['points'=>count($route),'distance_m'=>(int)round(ep7_route_distance($route))],'privacy'=>['device_id_stored'=>false,'retention_h'=>2]]);
