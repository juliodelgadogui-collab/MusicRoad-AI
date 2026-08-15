<?php
require __DIR__ . '/bootstrap.php';
require_login();
global $config;

$origin = $_GET['origin'] ?? '';
$destination = $_GET['destination'] ?? '';
if (!$origin || !$destination) {
    json_response(['ok' => false, 'error' => 'Informe origem e destino. Pode ser cidade, endereço ou lat,lon.'], 422);
}

$originGeo = geocode_place($origin);
$destinationGeo = geocode_place($destination);
if (!$originGeo || !$destinationGeo) {
    json_response(['ok' => false, 'error' => 'Não consegui localizar origem ou destino. Tente cidade + UF, exemplo: Bom Jesus do Itabapoana RJ.'], 422);
}
$olat = $originGeo['lat'];
$olon = $originGeo['lon'];
$dlat = $destinationGeo['lat'];
$dlon = $destinationGeo['lon'];
$url = rtrim($config['routing']['osrm_base_url'], '/') . "/route/v1/driving/$olon,$olat;$dlon,$dlat?overview=full&geometries=geojson&steps=false";
$route = http_json($url);
if (!$route) {
    json_response(['ok' => false, 'error' => 'Não foi possível consultar o roteador OSRM agora.'], 502);
}
if (empty($route['routes'][0])) {
    json_response(['ok' => false, 'error' => 'Rota não encontrada.'], 404);
}
$coords = $route['routes'][0]['geometry']['coordinates'];
$minLat = min(array_column($coords, 1)) - 0.02;
$maxLat = max(array_column($coords, 1)) + 0.02;
$minLon = min(array_column($coords, 0)) - 0.02;
$maxLon = max(array_column($coords, 0)) + 0.02;
$stmt = db()->prepare('SELECT * FROM radars WHERE ativo = 1 AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT 2000');
$stmt->execute([$minLat, $maxLat, $minLon, $maxLon]);
$radars = $stmt->fetchAll();

if (!$radars) {
    $bbox = [$minLat, $minLon, $maxLat, $maxLon];
    $query = '[out:json][timeout:25];(node["highway"="speed_camera"](' . implode(',', $bbox) . ');way["highway"="speed_camera"](' . implode(',', $bbox) . ');relation["highway"="speed_camera"](' . implode(',', $bbox) . '););out center tags;';
    $osm = http_json($config['routing']['overpass_url'], 'data=' . urlencode($query), ['Content-Type: application/x-www-form-urlencoded']);
    foreach (($osm['elements'] ?? []) as $el) {
        $lat = $el['lat'] ?? ($el['center']['lat'] ?? null);
        $lon = $el['lon'] ?? ($el['center']['lon'] ?? null);
        if (!is_numeric($lat) || !is_numeric($lon)) {
            continue;
        }
        $tags = $el['tags'] ?? [];
        $externalId = 'osm-' . ($el['type'] ?? 'node') . '-' . ($el['id'] ?? md5((string)$lat . (string)$lon));
        $exists = db()->prepare('SELECT id FROM radars WHERE external_id = ? LIMIT 1');
        $exists->execute([$externalId]);
        if (!$exists->fetchColumn()) {
            $insert = db()->prepare('INSERT INTO radars (external_id, latitude, longitude, uf, cidade, rodovia, km, sentido, heading, velocidade, tipo, situacao, fonte, data_fonte, data_importacao, confiabilidade, quantidade_fontes, ativo) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime("now"), ?, ?, ?)');
            $insert->execute([
                $externalId, (float)$lat, (float)$lon, null, $tags['addr:city'] ?? null, $tags['road_ref'] ?? ($tags['ref'] ?? null),
                $tags['distance'] ?? null, $tags['direction'] ?? null, null, isset($tags['maxspeed']) ? (int)preg_replace('/\D+/', '', $tags['maxspeed']) : null,
                'RADAR_FIXO', 'ATIVO', 'OPENSTREETMAP', null, 'MÉDIA', 1, 1,
            ]);
        }
    }
    $stmt->execute([$minLat, $maxLat, $minLon, $maxLon]);
    $radars = $stmt->fetchAll();
}

json_response([
    'ok' => true,
    'origin' => $originGeo,
    'destination' => $destinationGeo,
    'route' => $route['routes'][0],
    'radars' => $radars,
    'message' => $radars ? 'Radares encontrados na base local/OSM.' : 'Nenhum radar encontrado na base local nem no OpenStreetMap para esta rota.',
]);
