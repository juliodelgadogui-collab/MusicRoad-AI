<?php
declare(strict_types=1);
require_once __DIR__ . '/../../bootstrap.php';
$user = v3_require_user_json();
$body = v3_input();
$fromLat=(float)($body['from_lat']??NAN);$fromLon=(float)($body['from_lon']??NAN);
$toLat=(float)($body['to_lat']??NAN);$toLon=(float)($body['to_lon']??NAN);
if(!v3_coord($fromLat,$fromLon)||!v3_coord($toLat,$toLon))v3_json(['ok'=>false,'error'=>'Coordenadas inválidas.'],422);

$route=null;$router='';$detail='';$coords=sprintf('%.7F,%.7F;%.7F,%.7F',$fromLon,$fromLat,$toLon,$toLat);
$token=v3_mapbox_token();
if($token!==''){
    $url='https://api.mapbox.com/directions/v5/mapbox/driving/'.$coords.'?'.http_build_query([
        'access_token'=>$token,'alternatives'=>'false','geometries'=>'geojson','overview'=>'full','steps'=>'true','language'=>'pt-BR','continue_straight'=>'true'
    ],'','&',PHP_QUERY_RFC3986);
    $data=v3_http_json($url,[],28);
    if(is_array($data['routes'][0]??null)){$route=$data['routes'][0];$router='mapbox-directions-v5';}
}
if(!is_array($route)){
    $url='https://router.project-osrm.org/route/v1/driving/'.$coords.'?overview=full&geometries=geojson&steps=true&alternatives=false&continue_straight=true';
    $data=v3_http_json($url,[],28);
    if(is_array($data['routes'][0]??null)){$route=$data['routes'][0];$router='osrm-fallback';}
    else $detail='Nenhum roteador respondeu com uma rota válida.';
}
if(!is_array($route)||!is_array($route['geometry']['coordinates']??null)||count($route['geometry']['coordinates'])<2)v3_json(['ok'=>false,'error'=>'Não foi possível calcular a rota agora.','detail'=>$detail],502);
if(count($route['geometry']['coordinates'])>50000)v3_json(['ok'=>false,'error'=>'Rota acima do limite seguro.'],502);

v3_json(['ok'=>true,'version'=>EPC_V3_VERSION,'router'=>$router,'route'=>$route,'account'=>v3_account($user)]);
