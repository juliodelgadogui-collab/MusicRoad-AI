<?php

function mr_parse_speed($raw): ?int
{
    if ($raw === null || $raw === '') return null;
    if (is_numeric($raw)) {
        $v = (int)round((float)$raw);
        return ($v >= 10 && $v <= 180) ? $v : null;
    }
    if (preg_match('/(\d{2,3})/', (string)$raw, $m)) {
        $v = (int)$m[1];
        return ($v >= 10 && $v <= 180) ? $v : null;
    }
    return null;
}

function mr_sample_route(array $coords, int $maxPoints = 120): array
{
    $n = count($coords);
    if ($n <= $maxPoints) return $coords;
    $out = [];
    $step = ($n - 1) / ($maxPoints - 1);
    for ($i = 0; $i < $maxPoints; $i++) {
        $idx = (int)round($i * $step);
        $idx = min($n - 1, max(0, $idx));
        $out[] = $coords[$idx];
    }
    return $out;
}

function mr_route_line_for_overpass(array $coords, int $maxPoints = 95): string
{
    $sample = mr_sample_route($coords, $maxPoints);
    $parts = [];
    foreach ($sample as $c) {
        if (!is_array($c) || count($c) < 2) continue;
        $lon = (float)$c[0]; $lat = (float)$c[1];
        if (!$lat || !$lon) continue;
        $parts[] = rtrim(rtrim(number_format($lat, 6, '.', ''), '0'), '.') . ',' . rtrim(rtrim(number_format($lon, 6, '.', ''), '0'), '.');
    }
    return implode(',', $parts);
}

function mr_osm_radars_for_route(array $coords, int $radiusM = 850): array
{
    global $config;
    $line = mr_route_line_for_overpass($coords);
    if ($line === '' || substr_count($line, ',') < 3) return ['ok' => false, 'items' => []];

    $around = '(around:' . max(200, min(1500, $radiusM)) . ',' . $line . ')';
    $query = '[out:json][timeout:35];'
        . 'rel["type"="enforcement"]["enforcement"="maxspeed"]' . $around . '->.enf;'
        . 'node(r.enf:"device")->.devices;'
        . '('
        . 'node["highway"="speed_camera"]' . $around . ';'
        . 'way["highway"="speed_camera"]' . $around . ';'
        . 'rel["highway"="speed_camera"]' . $around . ';'
        . 'node["enforcement"="maxspeed"]' . $around . ';'
        . 'way["enforcement"="maxspeed"]' . $around . ';'
        . '.devices;'
        . '.enf;'
        . ');out center tags;';

    $osm = http_json($config['routing']['overpass_url'], 'data=' . urlencode($query), ['Content-Type: application/x-www-form-urlencoded']);
    if (!is_array($osm)) return ['ok' => false, 'items' => []];

    $items = [];
    foreach (($osm['elements'] ?? []) as $el) {
        $lat = $el['lat'] ?? ($el['center']['lat'] ?? null);
        $lon = $el['lon'] ?? ($el['center']['lon'] ?? null);
        if (!is_numeric($lat) || !is_numeric($lon)) continue;
        $tags = is_array($el['tags'] ?? null) ? $el['tags'] : [];
        $speed = mr_parse_speed($tags['maxspeed'] ?? ($tags['maxspeed:forward'] ?? ($tags['maxspeed:backward'] ?? null)));
        $type = (($tags['enforcement'] ?? '') === 'maxspeed' || ($tags['type'] ?? '') === 'enforcement') ? 'FISCALIZACAO_VELOCIDADE' : 'RADAR_FIXO';
        $items[] = [
            'external_id' => 'osm-' . ($el['type'] ?? 'node') . '-' . ($el['id'] ?? md5((string)$lat . ':' . (string)$lon)),
            'latitude' => (float)$lat,
            'longitude' => (float)$lon,
            'uf' => $tags['addr:state'] ?? null,
            'cidade' => $tags['addr:city'] ?? null,
            'rodovia' => $tags['road_ref'] ?? ($tags['ref'] ?? ($tags['name'] ?? null)),
            'km' => $tags['distance'] ?? null,
            'sentido' => $tags['direction'] ?? null,
            'heading' => is_numeric($tags['direction'] ?? null) ? (float)$tags['direction'] : null,
            'velocidade' => $speed,
            'tipo' => $type,
            'situacao' => 'ATIVO',
            'fonte' => 'OPENSTREETMAP',
            'data_fonte' => null,
            'confiabilidade' => 'MÉDIA',
            'quantidade_fontes' => 1,
            'ativo' => 1,
        ];
    }
    return ['ok' => true, 'items' => $items];
}

function mr_upsert_osm_radar(array $r): void
{
    if (empty($r['external_id'])) return;
    $exists = db()->prepare('SELECT id, velocidade FROM radars WHERE external_id = ? LIMIT 1');
    $exists->execute([$r['external_id']]);
    $row = $exists->fetch();
    if ($row) {
        if (empty($row['velocidade']) && !empty($r['velocidade'])) {
            $u = db()->prepare('UPDATE radars SET velocidade = ?, rodovia = COALESCE(rodovia, ?), sentido = COALESCE(sentido, ?), data_importacao = CURRENT_TIMESTAMP WHERE id = ?');
            $u->execute([$r['velocidade'], $r['rodovia'] ?? null, $r['sentido'] ?? null, $row['id']]);
        }
        return;
    }
    $insert = db()->prepare('INSERT INTO radars (external_id, latitude, longitude, uf, cidade, rodovia, km, sentido, heading, velocidade, tipo, situacao, fonte, data_fonte, data_importacao, confiabilidade, quantidade_fontes, ativo) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?)');
    $insert->execute([
        $r['external_id'], $r['latitude'], $r['longitude'], $r['uf'] ?? null, $r['cidade'] ?? null,
        $r['rodovia'] ?? null, $r['km'] ?? null, $r['sentido'] ?? null, $r['heading'] ?? null,
        $r['velocidade'] ?? null, $r['tipo'] ?? 'RADAR_FIXO', $r['situacao'] ?? 'ATIVO', $r['fonte'] ?? 'OPENSTREETMAP',
        $r['data_fonte'] ?? null, $r['confiabilidade'] ?? 'MÉDIA', $r['quantidade_fontes'] ?? 1, $r['ativo'] ?? 1,
    ]);
}

function mr_route_metrics(array $coords): array
{
    $route = mr_sample_route($coords, 1500);
    $cum = [0.0];
    $total = 0.0;
    for ($i = 1; $i < count($route); $i++) {
        $total += haversine_m((float)$route[$i-1][1], (float)$route[$i-1][0], (float)$route[$i][1], (float)$route[$i][0]);
        $cum[$i] = $total;
    }
    return [$route, $cum];
}

function mr_point_segment_projection(float $plat, float $plon, float $alat, float $alon, float $blat, float $blon): array
{
    $lat0 = deg2rad(($plat + $alat + $blat) / 3.0);
    $mx = 111320.0 * max(0.2, cos($lat0));
    $my = 110540.0;
    $ax = ($alon - $plon) * $mx; $ay = ($alat - $plat) * $my;
    $bx = ($blon - $plon) * $mx; $by = ($blat - $plat) * $my;
    $vx = $bx - $ax; $vy = $by - $ay;
    $den = $vx*$vx + $vy*$vy;
    $t = $den > 0 ? -($ax*$vx + $ay*$vy) / $den : 0.0;
    $t = max(0.0, min(1.0, $t));
    $x = $ax + $t*$vx; $y = $ay + $t*$vy;
    return ['distance' => sqrt($x*$x + $y*$y), 't' => $t];
}

function mr_radar_position_on_route(array $r, array $route, array $cum): array
{
    $lat = (float)$r['latitude']; $lon = (float)$r['longitude'];
    $best = INF; $bestM = 0.0;
    $n = count($route);
    for ($i = 1; $i < $n; $i++) {
        $a = $route[$i-1]; $b = $route[$i];
        $p = mr_point_segment_projection($lat, $lon, (float)$a[1], (float)$a[0], (float)$b[1], (float)$b[0]);
        if ($p['distance'] < $best) {
            $best = $p['distance'];
            $seg = max(0.0, ($cum[$i] ?? 0) - ($cum[$i-1] ?? 0));
            $bestM = ($cum[$i-1] ?? 0) + $seg * $p['t'];
        }
    }
    return ['distance_to_route_m' => $best, 'route_m' => $bestM];
}

function mr_dedupe_radars(array $items): array
{
    $out = [];
    foreach ($items as $r) {
        if (!isset($r['latitude'],$r['longitude']) || !is_numeric($r['latitude']) || !is_numeric($r['longitude'])) continue;
        $merged = false;
        foreach ($out as $i => $e) {
            $sameId = !empty($r['external_id']) && !empty($e['external_id']) && $r['external_id'] === $e['external_id'];
            $near = haversine_m((float)$r['latitude'], (float)$r['longitude'], (float)$e['latitude'], (float)$e['longitude']) <= 28;
            if ($sameId || $near) {
                if (empty($out[$i]['velocidade']) && !empty($r['velocidade'])) $out[$i]['velocidade'] = $r['velocidade'];
                if (empty($out[$i]['rodovia']) && !empty($r['rodovia'])) $out[$i]['rodovia'] = $r['rodovia'];
                $merged = true; break;
            }
        }
        if (!$merged) $out[] = $r;
    }
    return $out;
}
