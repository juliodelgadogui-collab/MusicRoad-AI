<?php
declare(strict_types=1);

/**
 * MusicRoad AI v1.2.0 — motor de rota resiliente.
 * Usa OSRM sem chave, com failover entre servidores compatíveis.
 */

function mr_route_endpoint_list(): array
{
    global $config;
    $items = [];
    $primary = trim((string)($config['routing']['osrm_base_url'] ?? 'https://router.project-osrm.org'));
    if ($primary !== '') $items[] = rtrim($primary, '/');

    $configured = $config['routing']['osrm_fallback_urls'] ?? [];
    if (is_string($configured)) $configured = preg_split('/\s*,\s*/', $configured, -1, PREG_SPLIT_NO_EMPTY) ?: [];
    if (is_array($configured)) {
        foreach ($configured as $base) {
            $base = trim((string)$base);
            if ($base !== '') $items[] = rtrim($base, '/');
        }
    }

    // Segundo servidor OSRM público, sem token. É usado somente quando o principal falha.
    $items[] = 'https://routing.openstreetmap.de/routed-car';

    $out = [];
    foreach ($items as $base) {
        if (!preg_match('~^https?://~i', $base)) continue;
        $key = strtolower(rtrim($base, '/'));
        if (!isset($out[$key])) $out[$key] = rtrim($base, '/');
    }
    return array_values($out);
}

function mr_route_http_json(string $url, int $timeoutSeconds = 14): array
{
    global $config;
    $ua = (string)($config['routing']['user_agent'] ?? 'MusicRoadAI/1.2.0');
    $started = microtime(true);$maxBytes=16777216;
    if (function_exists('curl_init')) {
        $ch = curl_init($url);$raw='';$tooLarge=false;
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => false,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_CONNECTTIMEOUT => min(7, max(3, $timeoutSeconds)),
            CURLOPT_TIMEOUT => max(5, $timeoutSeconds),
            CURLOPT_ENCODING => '',
            CURLOPT_HTTPHEADER => ['User-Agent: '.$ua, 'Accept: application/json'],
            CURLOPT_WRITEFUNCTION => static function($handle,string $chunk)use(&$raw,&$tooLarge,$maxBytes):int{$length=strlen($chunk);if(strlen($raw)+$length>$maxBytes){$tooLarge=true;return 0;}$raw.=$chunk;return $length;},
        ]);
        $success = curl_exec($ch);
        $error = curl_error($ch);
        $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
        curl_close($ch);
        $data = $success!==false&&!$tooLarge&&$raw!=='' ? json_decode($raw, true) : null;
        return [
            'ok' => $status >= 200 && $status < 300 && is_array($data),
            'status' => $status,
            'data' => is_array($data) ? $data : null,
            'error' => $tooLarge ? 'response_too_large' : ($error !== '' ? $error : null),
            'ms' => (int)round((microtime(true)-$started)*1000),
        ];
    }

    $ctx = stream_context_create(['http' => [
        'method' => 'GET',
        'header' => "User-Agent: {$ua}\r\nAccept: application/json\r\n",
        'timeout' => max(5, $timeoutSeconds),
        'ignore_errors' => true,
    ]]);
    $raw = @file_get_contents($url, false, $ctx, 0, $maxBytes+1);
    $status = 0;
    foreach (($http_response_header ?? []) as $line) {
        if (preg_match('~^HTTP/\S+\s+(\d{3})~', $line, $m)) $status = (int)$m[1];
    }
    $tooLarge=is_string($raw)&&strlen($raw)>$maxBytes;$data = is_string($raw)&&!$tooLarge ? json_decode($raw, true) : null;
    return [
        'ok' => $status >= 200 && $status < 300 && is_array($data),
        'status' => $status,
        'data' => is_array($data) ? $data : null,
        'error' => $tooLarge ? 'response_too_large' : ($raw === false ? 'network_error' : null),
        'ms' => (int)round((microtime(true)-$started)*1000),
    ];
}

function mr_route_coord(float $lat, float $lon): bool
{
    return is_finite($lat) && is_finite($lon) && $lat >= -90 && $lat <= 90 && $lon >= -180 && $lon <= 180;
}

function mr_route_url(string $base, float $olat, float $olon, float $dlat, float $dlon, bool $steps, bool $overview): string
{
    $coords = sprintf('%.7F,%.7F;%.7F,%.7F', $olon, $olat, $dlon, $dlat);
    $query = http_build_query([
        'overview' => $overview ? 'full' : 'false',
        'geometries' => 'geojson',
        'steps' => $steps ? 'true' : 'false',
        'alternatives' => 'false',
    ], '', '&', PHP_QUERY_RFC3986);
    return rtrim($base, '/').'/route/v1/driving/'.$coords.'?'.$query;
}

function mr_route_result_is_valid(?array $data, bool $needGeometry): bool
{
    if (!is_array($data) || ($data['code'] ?? '') !== 'Ok' || empty($data['routes'][0])) return false;
    if (!$needGeometry) return true;
    $coords = $data['routes'][0]['geometry']['coordinates'] ?? null;
    return is_array($coords) && count($coords) >= 2;
}

function mr_route_compute(float $olat, float $olon, float $dlat, float $dlon, bool $steps = false, bool $overview = true): array
{
    if (!mr_route_coord($olat, $olon) || !mr_route_coord($dlat, $dlon)) {
        return ['ok'=>false, 'error'=>'Coordenadas inválidas para calcular a rota.', 'attempts'=>[]];
    }

    $attempts = [];
    foreach (mr_route_endpoint_list() as $index => $base) {
        $url = mr_route_url($base, $olat, $olon, $dlat, $dlon, $steps, $overview);
        $res = mr_route_http_json($url, $index === 0 ? 13 : 16);
        $data = $res['data'];
        $code = is_array($data) ? (string)($data['code'] ?? '') : '';
        $message = is_array($data) ? (string)($data['message'] ?? '') : '';
        $attempts[] = [
            'engine' => parse_url($base, PHP_URL_HOST) ?: $base,
            'status' => (int)$res['status'],
            'code' => $code !== '' ? $code : null,
            'message' => $message !== '' ? $message : ($res['error'] ?? null),
            'ms' => (int)$res['ms'],
        ];

        if (mr_route_result_is_valid($data, $overview)) {
            $route = $data['routes'][0];
            $wps = is_array($data['waypoints'] ?? null) ? $data['waypoints'] : [];
            $originSnap = isset($wps[0]['distance']) && is_numeric($wps[0]['distance']) ? (float)$wps[0]['distance'] : null;
            $destSnap = isset($wps[1]['distance']) && is_numeric($wps[1]['distance']) ? (float)$wps[1]['distance'] : null;
            return [
                'ok'=>true,
                'data'=>$data,
                'route'=>$route,
                'engine'=>parse_url($base, PHP_URL_HOST) ?: $base,
                'origin_snap_m'=>$originSnap,
                'destination_snap_m'=>$destSnap,
                'attempts'=>$attempts,
            ];
        }

        // Falha temporária/limite/sem rota: tenta o próximo servidor em vez de abortar a viagem.
        if ($index === 0) usleep(180000);
    }

    $last = end($attempts) ?: [];
    $codes = array_values(array_filter(array_map(fn($a)=>(string)($a['code'] ?? ''), $attempts)));
    $noRoute = in_array('NoRoute', $codes, true) || in_array('NoSegment', $codes, true);
    $error = $noRoute
        ? 'Não encontrei uma via navegável entre a origem e o destino. Tente selecionar outro ponto do mesmo endereço.'
        : 'O serviço de rotas não respondeu. O MusicRoad tentou mais de um servidor automaticamente.';
    return ['ok'=>false, 'error'=>$error, 'attempts'=>$attempts, 'last'=>$last];
}
