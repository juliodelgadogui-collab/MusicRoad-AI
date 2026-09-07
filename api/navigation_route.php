<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: private, no-store, max-age=0');

$data = input_json();
$user = native_require_json_user($data);
$fromLat = (float)($data['from_lat'] ?? NAN);
$fromLon = (float)($data['from_lon'] ?? NAN);
$toLat = (float)($data['to_lat'] ?? NAN);
$toLon = (float)($data['to_lon'] ?? NAN);
if (!ep_nav_coord($fromLat, $fromLon) || !ep_nav_coord($toLat, $toLon)) {
    json_response(['ok'=>false,'error'=>'Coordenadas de navegação inválidas.'], 422);
}

$route = null;
$router = '';
$errors = [];
$coords = sprintf('%.7F,%.7F;%.7F,%.7F', $fromLon, $fromLat, $toLon, $toLat);

$own = ep_nav_osrm_base();
if ($own !== '') {
    try {
        $route = ep_nav_fetch_osrm($own, $coords);
        if (is_array($route)) $router = 'estradaplay-osrm';
    } catch (Throwable $e) { $errors[] = 'own: '.$e->getMessage(); }
}

if (!is_array($route)) {
    $token = ep_nav_mapbox_token();
    if ($token !== '') {
        try {
            $params = [
                'access_token'=>$token,'alternatives'=>'false','geometries'=>'geojson','overview'=>'full',
                'steps'=>'true','annotations'=>'distance,duration,maxspeed','language'=>'pt-BR',
                'continue_straight'=>'true','voice_instructions'=>'false','banner_instructions'=>'false',
            ];
            $url = 'https://api.mapbox.com/directions/v5/mapbox/driving/' . $coords . '?' . http_build_query($params, '', '&', PHP_QUERY_RFC3986);
            $json = http_json($url);
            if (is_array($json) && is_array($json['routes'][0] ?? null)) {
                $route = $json['routes'][0];
                $router = 'mapbox-directions-v5';
            }
        } catch (Throwable $e) { $errors[] = 'mapbox: '.$e->getMessage(); }
    }
}

if (!is_array($route) && app_setting('routing_public_fallback', '0') === '1') {
    try {
        $route = ep_nav_fetch_osrm('https://router.project-osrm.org', $coords);
        if (is_array($route)) $router = 'public-osrm-emergency';
    } catch (Throwable $e) { $errors[] = 'public: '.$e->getMessage(); }
}

if (!is_array($route) || !is_array($route['geometry']['coordinates'] ?? null) || count($route['geometry']['coordinates']) < 2) {
    json_response([
        'ok'=>false,
        'error'=>'Não foi possível calcular uma rota dirigível agora.',
        'routing_ready'=>$own !== '',
        'detail'=>implode(' | ', array_slice($errors, -3)),
    ], 502);
}
if (count($route['geometry']['coordinates']) > 50000) {
    json_response(['ok'=>false,'error'=>'A rota retornada excedeu o limite seguro.'], 502);
}

json_response([
    'ok'=>true,'route'=>$route,'router'=>$router,
    'routing'=>['own_ready'=>$own !== '','public_fallback'=>app_setting('routing_public_fallback','0') === '1'],
    'navigation'=>['version'=>'4.0.0','steps'=>true,'map_matching'=>'device-polyline','off_route_recalculation'=>true],
    'account'=>native_account_payload($user),
]);

function ep_nav_fetch_osrm(string $base, string $coords): ?array
{
    $base = rtrim(trim($base), '/');
    if ($base === '' || !preg_match('~^https?://~i', $base)) return null;
    $url = $base . '/route/v1/driving/' . $coords . '?overview=full&geometries=geojson&steps=true&alternatives=false&continue_straight=true';
    $json = http_json($url);
    return is_array($json) && is_array($json['routes'][0] ?? null) ? $json['routes'][0] : null;
}

function ep_nav_osrm_base(): string
{
    $candidates = [trim((string)app_setting('routing_osrm_url','')), trim((string)(getenv('ESTRADAPLAY_OSRM_URL') ?: ''))];
    foreach ($candidates as $v) {
        if ($v !== '' && preg_match('~^https?://[A-Za-z0-9._:\-\[\]]+(?:/[^\s]*)?$~', $v)) return rtrim($v, '/');
    }
    return '';
}

function ep_nav_coord(float $lat, float $lon): bool
{
    return is_finite($lat) && is_finite($lon) && $lat >= -35.5 && $lat <= 6.5 && $lon >= -75.5 && $lon <= -30.0;
}

function ep_nav_mapbox_token(): string
{
    global $config;
    $valid = static fn(string $v): bool => preg_match('/^pk\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}$/', trim($v)) === 1;
    try {
        if (function_exists('mapbox_client_config')) {
            $c = mapbox_client_config();
            $v = trim((string)($c['token'] ?? ''));
            if ($valid($v)) return $v;
        }
        foreach (['mapbox_public_token','mapbox_access_token','mapbox_token'] as $key) {
            $v = trim((string)app_setting($key, ''));
            if ($valid($v)) return $v;
        }
        if (isset($config) && is_array($config)) {
            foreach ([$config['mapbox']['public_token'] ?? '', $config['mapbox']['token'] ?? '', $config['mapbox_token'] ?? ''] as $candidate) {
                $v = trim((string)$candidate);
                if ($valid($v)) return $v;
            }
        }
    } catch (Throwable $ignored) {}
    foreach (['MAPBOX_PUBLIC_TOKEN','MAPBOX_ACCESS_TOKEN','MAPBOX_TOKEN'] as $key) {
        $v = trim((string)(getenv($key) ?: ''));
        if ($valid($v)) return $v;
    }
    return '';
}
