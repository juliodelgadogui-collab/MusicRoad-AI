<?php
require __DIR__ . '/bootstrap.php';
$user = require_login();

$action = $_GET['action'] ?? 'near';

if ($action === 'near') {
    $lat = (float)($_GET['lat'] ?? 0);
    $lon = (float)($_GET['lon'] ?? 0);
    if (!$lat || !$lon) {
        json_response(['ok' => false, 'error' => 'Latitude e longitude são obrigatórias.'], 422);
    }
    $radius = min(50000, max(1000, (int)($_GET['radius'] ?? 10000)));
    $box = $radius / 111320;
    $stmt = db()->prepare('SELECT * FROM radars WHERE ativo = 1 AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT 1000');
    $stmt->execute([$lat - $box, $lat + $box, $lon - $box, $lon + $box]);
    $items = array_values(array_filter($stmt->fetchAll(), fn($r) => haversine_m($lat, $lon, (float)$r['latitude'], (float)$r['longitude']) <= $radius));
    if (!$items) {
        global $config;
        $minLat = $lat - $box;
        $maxLat = $lat + $box;
        $minLon = $lon - $box;
        $maxLon = $lon + $box;
        $bbox = [$minLat, $minLon, $maxLat, $maxLon];
        $query = '[out:json][timeout:25];(node["highway"="speed_camera"](' . implode(',', $bbox) . ');way["highway"="speed_camera"](' . implode(',', $bbox) . ');relation["highway"="speed_camera"](' . implode(',', $bbox) . '););out center tags;';
        $osm = http_json($config['routing']['overpass_url'], 'data=' . urlencode($query), ['Content-Type: application/x-www-form-urlencoded']);
        foreach (($osm['elements'] ?? []) as $el) {
            $rlat = $el['lat'] ?? ($el['center']['lat'] ?? null);
            $rlon = $el['lon'] ?? ($el['center']['lon'] ?? null);
            if (!is_numeric($rlat) || !is_numeric($rlon)) {
                continue;
            }
            $tags = $el['tags'] ?? [];
            $externalId = 'osm-' . ($el['type'] ?? 'node') . '-' . ($el['id'] ?? md5((string)$rlat . (string)$rlon));
            $exists = db()->prepare('SELECT id FROM radars WHERE external_id = ? LIMIT 1');
            $exists->execute([$externalId]);
            if (!$exists->fetchColumn()) {
                $insert = db()->prepare('INSERT INTO radars (external_id, latitude, longitude, uf, cidade, rodovia, km, sentido, heading, velocidade, tipo, situacao, fonte, data_fonte, data_importacao, confiabilidade, quantidade_fontes, ativo) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime("now"), ?, ?, ?)');
                $insert->execute([
                    $externalId, (float)$rlat, (float)$rlon, null, $tags['addr:city'] ?? null, $tags['road_ref'] ?? ($tags['ref'] ?? null),
                    $tags['distance'] ?? null, $tags['direction'] ?? null, null, isset($tags['maxspeed']) ? (int)preg_replace('/\D+/', '', $tags['maxspeed']) : null,
                    'RADAR_FIXO', 'ATIVO', 'OPENSTREETMAP', null, 'MÉDIA', 1, 1,
                ]);
            }
        }
        $stmt->execute([$lat - $box, $lat + $box, $lon - $box, $lon + $box]);
        $items = array_values(array_filter($stmt->fetchAll(), fn($r) => haversine_m($lat, $lon, (float)$r['latitude'], (float)$r['longitude']) <= $radius));
    }
    json_response(['ok' => true, 'radars' => $items, 'csrf' => csrf_token(), 'message' => $items ? 'Consulta feita na base local/OpenStreetMap.' : 'Nenhum radar encontrado próximo neste raio.']);
}

if ($action === 'admin_summary') {
    if (($user['role'] ?? '') !== 'admin') json_response(['ok'=>false,'error'=>'Acesso negado.'],403);
    $summary = [
        'total' => (int)db()->query('SELECT COUNT(*) FROM radars')->fetchColumn(),
        'ativos' => (int)db()->query('SELECT COUNT(*) FROM radars WHERE ativo = 1')->fetchColumn(),
        'inativos' => (int)db()->query('SELECT COUNT(*) FROM radars WHERE ativo = 0')->fetchColumn(),
        'sem_velocidade' => (int)db()->query('SELECT COUNT(*) FROM radars WHERE velocidade IS NULL')->fetchColumn(),
        'sem_sentido' => (int)db()->query('SELECT COUNT(*) FROM radars WHERE sentido IS NULL OR sentido = ""')->fetchColumn(),
        'por_estado' => db()->query('SELECT uf, COUNT(*) total FROM radars GROUP BY uf ORDER BY total DESC')->fetchAll(),
        'por_fonte' => db()->query('SELECT fonte, COUNT(*) total FROM radars GROUP BY fonte ORDER BY total DESC')->fetchAll(),
    ];
    json_response(['ok' => true, 'summary' => $summary]);
}

if ($action === 'save') {
    if (($user['role'] ?? '') !== 'admin') json_response(['ok'=>false,'error'=>'Acesso negado.'],403);
    require_csrf();
    $data = input_json();
    $stmt = db()->prepare('INSERT INTO radars
      (external_id, latitude, longitude, uf, cidade, rodovia, km, sentido, heading, velocidade, tipo, situacao, fonte, data_fonte, data_importacao, confiabilidade, quantidade_fontes, ativo)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime("now"), ?, ?, ?)');
    $stmt->execute([
        $data['external_id'] ?? null, (float)$data['latitude'], (float)$data['longitude'], $data['uf'] ?? null,
        $data['cidade'] ?? null, $data['rodovia'] ?? null, $data['km'] ?? null, $data['sentido'] ?? null,
        $data['heading'] ?? null, $data['velocidade'] ?? null, $data['tipo'] ?? 'RADAR_FIXO',
        $data['situacao'] ?? 'ATIVO', $data['fonte'] ?? 'USUARIO', $data['data_fonte'] ?? null,
        $data['confiabilidade'] ?? 'BAIXA', $data['quantidade_fontes'] ?? 1, $data['ativo'] ?? 1,
    ]);
    json_response(['ok' => true, 'id' => (int)db()->lastInsertId()]);
}

json_response(['ok' => false, 'error' => 'Ação inválida.'], 400);
