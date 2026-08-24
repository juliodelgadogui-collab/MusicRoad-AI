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

function mr_fast_feature_to_geo(array $f,string $fallback): ?array {
    $p=is_array($f['properties']??null)?$f['properties']:[];
    $pc=is_array($p['coordinates']??null)?$p['coordinates']:[];
    $g=is_array($f['geometry']??null)?$f['geometry']:[];
    $gc=is_array($g['coordinates']??null)?$g['coordinates']:[];
    $lon=$pc['longitude']??($gc[0]??null);
    $lat=$pc['latitude']??($gc[1]??null);
    if(!is_numeric($lat)||!is_numeric($lon))return null;
    $label=trim((string)($p['full_address']??''));
    if($label===''){
        $name=trim((string)($p['name']??($f['place_name']??'')));
        $formatted=trim((string)($p['place_formatted']??''));
        $label=trim($name.($formatted!==''?', '.$formatted:''));
    }
    if($label==='')$label=$fallback;
    return ['lat'=>(float)$lat,'lon'=>(float)$lon,'display_name'=>$label,'engine'=>'mapbox-geocoding-v6'];
}

function mr_fast_mapbox_geocode(string $value,?array $near=null): ?array {
    $token=mr_fast_token();
    if($token==='')return null;

    // Tentativa principal: busca ampla/autocomplete. A versão anterior usava
    // autocomplete=false e um filtro rígido de tipos, o que podia zerar cidades
    // e endereços simples em algumas contas/respostas do Geocoding v6.
    $attempts=[];
    $base=[
        'q'=>$value,
        'access_token'=>$token,
        'country'=>'BR',
        'language'=>'pt',
        'autocomplete'=>'true',
        'limit'=>'5',
    ];
    if($near&&isset($near['lat'],$near['lon'])){
        $base['proximity']=sprintf('%.7F,%.7F',(float)$near['lon'],(float)$near['lat']);
    }
    $attempts[]=$base+['types'=>'address,street,postcode,district,place,locality,neighborhood,region'];
    $attempts[]=$base;
    if(isset($base['proximity'])){
        $withoutProximity=$base;
        unset($withoutProximity['proximity']);
        $attempts[]=$withoutProximity;
    }

    foreach($attempts as $params){
        $url='https://api.mapbox.com/search/geocode/v6/forward?'.http_build_query($params,'','&',PHP_QUERY_RFC3986);
        $data=http_json($url);
        $features=is_array($data)&&is_array($data['features']??null)?$data['features']:[];
        foreach($features as $f){
            if(!is_array($f))continue;
            $geo=mr_fast_feature_to_geo($f,$value);
            if($geo)return $geo;
        }
    }
    return null;
}

function mr_fast_nominatim_geocode(string $value): ?array {
    global $config;
    $base=trim((string)($config['routing']['nominatim_url']??''));
    if($base==='')$base='https://nominatim.openstreetmap.org/search';
    $params=[
        'q'=>$value,
        'format'=>'jsonv2',
        'limit'=>'3',
        'addressdetails'=>'1',
        'countrycodes'=>'br',
        'accept-language'=>'pt-BR,pt',
    ];
    $url=rtrim($base,'?').'?'.http_build_query($params,'','&',PHP_QUERY_RFC3986);
    $data=http_json($url);
    if(!is_array($data))return null;
    foreach($data as $item){
        if(!is_array($item)||!is_numeric($item['lat']??null)||!is_numeric($item['lon']??null))continue;
        $label=trim((string)($item['display_name']??$value));
        return ['lat'=>(float)$item['lat'],'lon'=>(float)$item['lon'],'display_name'=>$label!==''?$label:$value,'engine'=>'nominatim-fallback'];
    }
    return null;
}

function mr_fast_geocode(string $value,?array $near=null): ?array {
    $coord=mr_fast_coord($value);if($coord)return $coord;

    // Mapbox continua preferencial. Nominatim devolve a robustez que a versão
    // anterior do servidor já possuía quando a Search API da Mapbox não responde.
    $geo=mr_fast_mapbox_geocode($value,$near);
    if($geo)return $geo;
    return mr_fast_nominatim_geocode($value);
}

$originGeo=mr_fast_coord($origin);
if(!$originGeo)$originGeo=mr_fast_geocode($origin,null);
if(!$originGeo)json_response(['ok'=>false,'error'=>'Não consegui identificar sua origem.'],422);
$destinationGeo=mr_fast_geocode($destination,$originGeo);
if(!$destinationGeo)json_response(['ok'=>false,'error'=>'Não consegui localizar esse destino. Tente cidade + UF ou rua, número e cidade.'],422);

$token=mr_fast_token();if($token==='')json_response(['ok'=>false,'error'=>'Token público Mapbox indisponível para calcular a rota.'],503);
$coords=sprintf('%.7F,%.7F;%.7F,%.7F',(float)$originGeo['lon'],(float)$originGeo['lat'],(float)$destinationGeo['lon'],(float)$destinationGeo['lat']);
$params=['access_token'=>$token,'alternatives'=>'false','geometries'=>'geojson','overview'=>'full','steps'=>'true','annotations'=>'maxspeed','language'=>'pt-BR','continue_straight'=>'true'];
$url='https://api.mapbox.com/directions/v5/mapbox/driving/'.$coords.'?'.http_build_query($params,'','&',PHP_QUERY_RFC3986);
$data=http_json($url);$route=is_array($data)?($data['routes'][0]??null):null;
if(!is_array($route)||empty($route['geometry']['coordinates']))json_response(['ok'=>false,'error'=>'A Mapbox não encontrou uma rota dirigível para esse destino.'],502);

json_response([
    'ok'=>true,
    'origin'=>$originGeo,
    'destination'=>$destinationGeo,
    'route'=>$route,
    'router'=>'mapbox-directions-v5-fast',
    'geocoder'=>$destinationGeo['engine']??'unknown',
    'route_profile'=>'driving',
    'route_selection'=>'mapbox-recommended',
    'version'=>'2.2.0.1'
]);
