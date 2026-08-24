<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
require_login();
global $config;

header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');

$origin=trim((string)($_GET['origin'] ?? ''));
$destination=trim((string)($_GET['destination'] ?? ''));
if($origin===''||$destination==='') json_response(['ok'=>false,'error'=>'Informe origem e destino.'],422);

function mr_fast_token(): string {
    global $config;
    $valid=static fn(string $v):bool=>preg_match('/^pk\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}$/',trim($v))===1;
    try{
        if(function_exists('mapbox_client_config')){$c=mapbox_client_config();$v=trim((string)($c['token']??''));if($valid($v))return $v;}
        if(function_exists('app_setting')){foreach(['mapbox_public_token','mapbox_access_token','mapbox_token'] as $k){$v=trim((string)app_setting($k,''));if($valid($v))return $v;}}
        if(isset($config)&&is_array($config)){foreach([$config['mapbox']['public_token']??'',$config['mapbox']['token']??'',$config['mapbox_token']??''] as $x){$v=trim((string)$x);if($valid($v))return $v;}}
    }catch(Throwable $e){}
    foreach(['MAPBOX_PUBLIC_TOKEN','MAPBOX_ACCESS_TOKEN','MAPBOX_TOKEN'] as $k){$v=trim((string)(getenv($k)?:''));if($valid($v))return $v;}
    return '';
}

function mr_fast_coord(string $value): ?array {
    if(!preg_match('/^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*$/',$value,$m))return null;
    $lat=(float)$m[1];$lon=(float)$m[2];
    return ($lat>=-90&&$lat<=90&&$lon>=-180&&$lon<=180)?['lat'=>$lat,'lon'=>$lon,'display_name'=>$value,'engine'=>'coordinates']:null;
}

function mr_fast_geocode(string $value,?array $near=null): ?array {
    $coord=mr_fast_coord($value);if($coord)return $coord;
    $token=mr_fast_token();if($token==='')return null;
    $params=['q'=>$value,'access_token'=>$token,'country'=>'br','language'=>'pt-BR','types'=>'address,street,postcode,place,locality,neighborhood','autocomplete'=>'false','limit'=>'1'];
    if($near&&isset($near['lat'],$near['lon']))$params['proximity']=sprintf('%.7F,%.7F',(float)$near['lon'],(float)$near['lat']);
    $url='https://api.mapbox.com/search/geocode/v6/forward?'.http_build_query($params,'','&',PHP_QUERY_RFC3986);
    $data=http_json($url);$f=is_array($data)?($data['features'][0]??null):null;if(!is_array($f))return null;
    $p=is_array($f['properties']??null)?$f['properties']:[];$pc=is_array($p['coordinates']??null)?$p['coordinates']:[];$g=is_array($f['geometry']??null)?$f['geometry']:[];$gc=is_array($g['coordinates']??null)?$g['coordinates']:[];
    $lon=$pc['longitude']??($gc[0]??null);$lat=$pc['latitude']??($gc[1]??null);if(!is_numeric($lat)||!is_numeric($lon))return null;
    $label=trim((string)($p['full_address']??''));if($label===''){ $name=trim((string)($p['name']??''));$formatted=trim((string)($p['place_formatted']??''));$label=trim($name.($formatted!==''?', '.$formatted:'')); }if($label==='')$label=$value;
    return ['lat'=>(float)$lat,'lon'=>(float)$lon,'display_name'=>$label,'engine'=>'mapbox-geocoding-v6'];
}

$originGeo=mr_fast_coord($origin);
if(!$originGeo) $originGeo=mr_fast_geocode($origin,null);
if(!$originGeo) json_response(['ok'=>false,'error'=>'Não consegui identificar sua origem.'],422);
$destinationGeo=mr_fast_geocode($destination,$originGeo);
if(!$destinationGeo) json_response(['ok'=>false,'error'=>'Não encontrei esse destino no Mapbox. Informe rua, número e cidade quando possível.'],422);

$token=mr_fast_token();if($token==='')json_response(['ok'=>false,'error'=>'Token público Mapbox indisponível.'],503);
$coords=sprintf('%.7F,%.7F;%.7F,%.7F',(float)$originGeo['lon'],(float)$originGeo['lat'],(float)$destinationGeo['lon'],(float)$destinationGeo['lat']);
$params=['access_token'=>$token,'alternatives'=>'false','geometries'=>'geojson','overview'=>'full','steps'=>'true','annotations'=>'maxspeed','language'=>'pt-BR','continue_straight'=>'true'];
$url='https://api.mapbox.com/directions/v5/mapbox/driving/'.$coords.'?'.http_build_query($params,'','&',PHP_QUERY_RFC3986);
$data=http_json($url);$route=is_array($data)?($data['routes'][0]??null):null;
if(!is_array($route)||empty($route['geometry']['coordinates']))json_response(['ok'=>false,'error'=>'A Mapbox não encontrou uma rota dirigível para esse destino.'],502);

json_response(['ok'=>true,'origin'=>$originGeo,'destination'=>$destinationGeo,'route'=>$route,'router'=>'mapbox-directions-v5-fast','route_profile'=>'driving','route_selection'=>'mapbox-recommended','version'=>'2.2.0']);
