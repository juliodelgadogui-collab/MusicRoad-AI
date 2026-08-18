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

$originGeo = geocode_place($origin);
$destinationGeo = geocode_place($destination);
if (!$originGeo || !$destinationGeo) {
    json_response(['ok' => false, 'error' => 'Não consegui localizar origem ou destino. Tente cidade + UF.'], 422);
}

$olat = $originGeo['lat']; $olon = $originGeo['lon'];
$dlat = $destinationGeo['lat']; $dlon = $destinationGeo['lon'];
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
