<?php
require __DIR__ . '/bootstrap.php';
if (!current_user()) json_response(['ok'=>false,'error'=>'Sua sessão expirou. Entre novamente no MusicRoad.','session_expired'=>true],401);
require_login();
require_session_rate_limit('route',30,60);
session_write_close();
require_once dirname(__DIR__) . '/lib/RouteEngine.php';
global $config;

$origin = trim((string)($_GET['origin'] ?? ''));
$destination = trim((string)($_GET['destination'] ?? ''));
$destinationCity = trim((string)($_GET['destination_city'] ?? ''));
$destinationUf = strtoupper(trim((string)($_GET['destination_uf'] ?? '')));
$destinationMunicipalityId = preg_replace('/\D+/', '', (string)($_GET['destination_ibge_id'] ?? ''));
$destinationLat = isset($_GET['destination_lat']) ? (float)$_GET['destination_lat'] : null;
$destinationLon = isset($_GET['destination_lon']) ? (float)$_GET['destination_lon'] : null;
if ($origin === '' || $destination === '') json_response(['ok'=>false,'error'=>'Informe origem e destino.'],422);

function mr_geocode_cached(string $value): ?array {
    if (preg_match('/^\s*(-?\d+(?:[\.,]\d+)?)\s*,\s*(-?\d+(?:[\.,]\d+)?)\s*$/', $value, $m)) {
        $lat=(float)str_replace(',','.',$m[1]);$lon=(float)str_replace(',','.',$m[2]);
        if($lat>=-90&&$lat<=90&&$lon>=-180&&$lon<=180)return ['lat'=>$lat,'lon'=>$lon,'display_name'=>$value,'label'=>$value];
    }
    $key='mr_geo_'.sha1(strtolower(trim($value)));
    if(function_exists('apcu_fetch')){$cached=apcu_fetch($key,$ok);if($ok&&is_array($cached))return $cached;}
    $dir=dirname(__DIR__).'/storage/cache';if(!is_dir($dir))@mkdir($dir,0775,true);$file=$dir.'/'.$key.'.json';
    if(is_file($file)&&filemtime($file)>time()-2592000){$j=json_decode((string)@file_get_contents($file),true);if(is_array($j)&&isset($j['lat'],$j['lon']))return $j;}
    $geo=geocode_place($value);
    if($geo){if(function_exists('apcu_store'))@apcu_store($key,$geo,2592000);$encoded=json_encode($geo,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);if(is_string($encoded))write_runtime_file($file,$encoded);}
    return $geo?:null;
}

$originGeo=mr_geocode_cached($origin);
$destinationGeo=null;
if($destinationLat !== null && $destinationLon !== null && $destinationLat >= -90 && $destinationLat <= 90 && $destinationLon >= -180 && $destinationLon <= 180){
    $destinationGeo=['lat'=>$destinationLat,'lon'=>$destinationLon,'display_name'=>($destinationCity!==''?$destinationCity.', '.$destinationUf:$destination),'label'=>($destinationCity!==''?$destinationCity.', '.$destinationUf:$destination),'municipality_id'=>$destinationMunicipalityId?:null];
}
if(!$destinationGeo && $destinationCity!=='' && preg_match('/^[A-Z]{2}$/',$destinationUf)){
    $key='mr_geo_city_'.sha1(mb_strtolower($destinationCity.'|'.$destinationUf));$dir=dirname(__DIR__).'/storage/cache';if(!is_dir($dir))@mkdir($dir,0775,true);$file=$dir.'/'.$key.'.json';
    if(is_file($file)&&filemtime($file)>time()-2592000){$j=json_decode((string)@file_get_contents($file),true);if(is_array($j)&&isset($j['lat'],$j['lon']))$destinationGeo=$j;}
    if(!$destinationGeo){$query=http_build_query(['city'=>$destinationCity,'state'=>$destinationUf,'country'=>'Brasil','countrycodes'=>'br','format'=>'jsonv2','limit'=>1,'addressdetails'=>1]);$data=http_json(rtrim($config['routing']['nominatim_url'],'?').'?'.$query,null,[],20);if($data&&!empty($data[0]['lat'])&&!empty($data[0]['lon'])){$destinationGeo=['lat'=>(float)$data[0]['lat'],'lon'=>(float)$data[0]['lon'],'display_name'=>$data[0]['display_name']??($destinationCity.', '.$destinationUf),'label'=>$data[0]['display_name']??($destinationCity.', '.$destinationUf)];$encoded=json_encode($destinationGeo,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);if(is_string($encoded))write_runtime_file($file,$encoded);}}
}
if(!$destinationGeo)$destinationGeo=mr_geocode_cached($destination);
if(!$originGeo||!$destinationGeo)json_response(['ok'=>false,'error'=>'Não consegui localizar origem ou destino. Selecione um endereço da lista e tente novamente.'],422);

$olat=(float)$originGeo['lat'];$olon=(float)$originGeo['lon'];$dlat=(float)$destinationGeo['lat'];$dlon=(float)$destinationGeo['lon'];
$started=microtime(true);$calc=mr_route_compute($olat,$olon,$dlat,$dlon,false,true);
if(empty($calc['ok']))json_response(['ok'=>false,'error'=>$calc['error']??'Não foi possível calcular a rota agora.','routing'=>['attempt_count'=>count($calc['attempts']??[])]],503);
$route=$calc['route'];

json_response([
  'ok'=>true,'origin'=>$originGeo,'destination'=>$destinationGeo,'route'=>$route,'radars'=>[],
  'radars_pending'=>true,
  'routing'=>['engine'=>'osrm','origin_snap_m'=>$calc['origin_snap_m']??null,'destination_snap_m'=>$calc['destination_snap_m']??null,'attempt_count'=>count($calc['attempts']??[])],
  'timing'=>['route_ms'=>(int)round((microtime(true)-$started)*1000)]
]);
