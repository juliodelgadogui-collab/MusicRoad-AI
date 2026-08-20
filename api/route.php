<?php
require __DIR__ . '/bootstrap.php';
require_login();
require __DIR__ . '/radar_route_helpers.php';
global $config;

$origin = trim((string)($_GET['origin'] ?? ''));
$destination = trim((string)($_GET['destination'] ?? ''));
if ($origin === '' || $destination === '') {
    json_response(['ok' => false, 'error' => 'Informe origem e destino. Pode ser cidade, endereço ou lat,lon.'], 422);
}

function mr_route_mapbox_token(): string
{
    global $config;
    $valid = static fn(string $v): bool => preg_match('/^pk\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}$/', trim($v)) === 1;
    try {
        if (function_exists('mapbox_client_config')) {
            $c = mapbox_client_config();
            $v = trim((string)($c['token'] ?? ''));
            if ($valid($v)) return $v;
        }
        if (function_exists('app_setting')) {
            foreach (['mapbox_public_token','mapbox_access_token','mapbox_token'] as $key) {
                $v = trim((string)app_setting($key, ''));
                if ($valid($v)) return $v;
            }
        }
        if (isset($config) && is_array($config)) {
            foreach ([
                $config['mapbox']['public_token'] ?? '',
                $config['mapbox']['token'] ?? '',
                $config['mapbox_token'] ?? '',
            ] as $candidate) {
                $v = trim((string)$candidate);
                if ($valid($v)) return $v;
            }
        }
    } catch (Throwable $e) {}
    foreach (['MAPBOX_PUBLIC_TOKEN','MAPBOX_ACCESS_TOKEN','MAPBOX_TOKEN'] as $key) {
        $v = trim((string)(getenv($key) ?: ''));
        if ($valid($v)) return $v;
    }
    return '';
}

function mr_route_geocode(string $value): ?array
{
    if (preg_match('/^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*$/', $value, $m)) {
        $lat=(float)$m[1]; $lon=(float)$m[2];
        if ($lat>=-90 && $lat<=90 && $lon>=-180 && $lon<=180) {
            return ['lat'=>$lat,'lon'=>$lon,'display_name'=>$value,'engine'=>'coordinates'];
        }
    }

    $key='mr_mapbox_geo_'.sha1(mb_strtolower(trim($value),'UTF-8'));
    if (function_exists('apcu_fetch')) {
        $cached=apcu_fetch($key,$ok);
        if ($ok && is_array($cached) && isset($cached['lat'],$cached['lon'])) return $cached;
    }
    $dir=dirname(__DIR__).'/storage/cache';
    if (!is_dir($dir)) @mkdir($dir,0775,true);
    $file=$dir.'/'.$key.'.json';
    if (is_file($file) && filemtime($file)>time()-2592000) {
        $j=json_decode((string)@file_get_contents($file),true);
        if (is_array($j) && isset($j['lat'],$j['lon'])) return $j;
    }

    $token=mr_route_mapbox_token();
    if ($token==='') return null;
    $params=[
        'q'=>$value,
        'access_token'=>$token,
        'country'=>'br',
        'language'=>'pt-BR',
        'types'=>'address,street,postcode,place,locality,neighborhood',
        'autocomplete'=>'false',
        'limit'=>'1',
    ];
    $url='https://api.mapbox.com/search/geocode/v6/forward?'.http_build_query($params,'','&',PHP_QUERY_RFC3986);
    $data=http_json($url);
    $feature=is_array($data) ? (($data['features'][0] ?? null)) : null;
    if (!is_array($feature)) return null;
    $p=is_array($feature['properties'] ?? null) ? $feature['properties'] : [];
    $pc=is_array($p['coordinates'] ?? null) ? $p['coordinates'] : [];
    $geom=is_array($feature['geometry'] ?? null) ? $feature['geometry'] : [];
    $gc=is_array($geom['coordinates'] ?? null) ? $geom['coordinates'] : [];
    $lon=$pc['longitude'] ?? ($gc[0] ?? null);
    $lat=$pc['latitude'] ?? ($gc[1] ?? null);
    if (!is_numeric($lat) || !is_numeric($lon)) return null;
    $name=trim((string)($p['name'] ?? ''));
    $formatted=trim((string)($p['place_formatted'] ?? ''));
    $label=trim((string)($p['full_address'] ?? ''));
    if ($label==='') $label=trim($name.($formatted!==''?', '.$formatted:''));
    if ($label==='') $label=$value;
    $geo=['lat'=>(float)$lat,'lon'=>(float)$lon,'display_name'=>$label,'engine'=>'mapbox-geocoding-v6'];
    if (function_exists('apcu_store')) @apcu_store($key,$geo,2592000);
    @file_put_contents($file,json_encode($geo,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES),LOCK_EX);
    return $geo;
}

$originGeo = mr_route_geocode($origin);
$destinationGeo = mr_route_geocode($destination);
if (!$originGeo || !$destinationGeo) {
    json_response(['ok' => false, 'error' => 'Não consegui localizar origem ou destino pelo Mapbox. Informe um endereço mais completo.'], 422);
}

$olat = (float)$originGeo['lat']; $olon = (float)$originGeo['lon'];
$dlat = (float)$destinationGeo['lat']; $dlon = (float)$destinationGeo['lon'];
$url = rtrim($config['routing']['osrm_base_url'], '/') . "/route/v1/driving/$olon,$olat;$dlon,$dlat?overview=full&geometries=geojson&steps=true";
$routeData = http_json($url);
if (!$routeData || empty($routeData['routes'][0])) {
    json_response(['ok' => false, 'error' => 'Não foi possível calcular a rota agora.'], 502);
}
$route = $routeData['routes'][0];
$coords = $route['geometry']['coordinates'] ?? [];
if (count($coords) < 2) json_response(['ok' => false, 'error' => 'A rota retornou sem geometria.'], 502);

$lats = array_column($coords, 1); $lons = array_column($coords, 0);
$pad = 0.025;
$minLat = min($lats) - $pad; $maxLat = max($lats) + $pad;
$minLon = min($lons) - $pad; $maxLon = max($lons) + $pad;

$stmt = db()->prepare('SELECT * FROM radars WHERE ativo = 1 AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT 10000');
$stmt->execute([$minLat, $maxLat, $minLon, $maxLon]);
$local = $stmt->fetchAll() ?: [];
foreach ($local as &$r) { if (empty($r['fonte'])) $r['fonte'] = 'BASE_LOCAL'; }
unset($r);

$osmResult = mr_osm_radars_for_route($coords, 850);
$osm = $osmResult['items'] ?? [];
foreach ($osm as $r) {
    try { mr_upsert_osm_radar($r); } catch (Throwable $e) { }
}

$merged = mr_dedupe_radars(array_merge($local, $osm));
[$metricRoute, $cum] = mr_route_metrics($coords);
$onRoute = [];
foreach ($merged as $r) {
    $m = mr_radar_position_on_route($r, $metricRoute, $cum);
    if ($m['distance_to_route_m'] > 900) continue;
    $r['distance_to_route_m'] = (int)round($m['distance_to_route_m']);
    $r['route_m'] = (int)round($m['route_m']);
    $r['velocidade'] = mr_parse_speed($r['velocidade'] ?? null);
    $onRoute[] = $r;
}
usort($onRoute, fn($a,$b) => ((int)($a['route_m'] ?? 0)) <=> ((int)($b['route_m'] ?? 0)));

json_response([
    'ok' => true,
    'origin' => $originGeo,
    'destination' => $destinationGeo,
    'route' => $route,
    'radars' => $onRoute,
    'geocoder' => 'mapbox-geocoding-v6',
    'coverage' => [
        'local_candidates' => count($local),
        'osm_candidates' => count($osm),
        'merged_candidates' => count($merged),
        'on_route' => count($onRoute),
        'corridor_m' => 900,
        'osm_ok' => (bool)($osmResult['ok'] ?? false),
    ],
    'message' => count($onRoute) . ' radares encontrados no corredor da rota usando base local + OpenStreetMap.',
]);
