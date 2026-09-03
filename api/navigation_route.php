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
$error = '';
$token = ep_nav_mapbox_token();
if ($token !== '') {
    try {
        $coords = sprintf('%.7F,%.7F;%.7F,%.7F', $fromLon, $fromLat, $toLon, $toLat);
        $params = [
            'access_token'=>$token,
            'alternatives'=>'false',
            'geometries'=>'geojson',
            'overview'=>'full',
            'steps'=>'true',
            'annotations'=>'distance,duration,maxspeed',
            'language'=>'pt-BR',
            'continue_straight'=>'true',
            'voice_instructions'=>'false',
            'banner_instructions'=>'false',
        ];
        $url = 'https://api.mapbox.com/directions/v5/mapbox/driving/' . $coords . '?' . http_build_query($params, '', '&', PHP_QUERY_RFC3986);
        $json = http_json($url);
        if (is_array($json) && is_array($json['routes'][0] ?? null)) {
            $route = $json['routes'][0];
            $router = 'mapbox-directions-v5';
        }
    } catch (Throwable $e) {
        $error = $e->getMessage();
    }
}

// Operational fallback: keeps routing available if Mapbox is unavailable.
if (!is_array($route)) {
    try {
        $coords = sprintf('%.7F,%.7F;%.7F,%.7F', $fromLon, $fromLat, $toLon, $toLat);
        $url = 'https://router.project-osrm.org/route/v1/driving/' . $coords
             . '?overview=full&geometries=geojson&steps=true&alternatives=false&continue_straight=true';
        $json = http_json($url);
        if (is_array($json) && is_array($json['routes'][0] ?? null)) {
            $route = $json['routes'][0];
            $router = 'osrm-fallback';
        }
    } catch (Throwable $e) {
        if ($error === '') $error = $e->getMessage();
    }
}

if (!is_array($route) || !is_array($route['geometry']['coordinates'] ?? null) || count($route['geometry']['coordinates']) < 2) {
    json_response(['ok'=>false,'error'=>'Não foi possível calcular uma rota dirigível agora.','detail'=>$error], 502);
}

// Keep payload bounded even if an upstream service changes unexpectedly.
if (count($route['geometry']['coordinates']) > 50000) {
    json_response(['ok'=>false,'error'=>'A rota retornada excedeu o limite seguro.'], 502);
}

json_response([
    'ok'=>true,
    'route'=>$route,
    'router'=>$router,
    'navigation'=>[
        'version'=>'2.0.8',
        'steps'=>true,
        'map_matching'=>'device-polyline',
        'off_route_recalculation'=>true,
    ],
    'account'=>native_account_payload($user),
]);

function ep_nav_coord(float $lat, float $lon): bool
{
    return is_finite($lat) && is_finite($lon)
        && $lat >= -35.5 && $lat <= 6.5
        && $lon >= -75.5 && $lon <= -30.0;
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
        if (function_exists('app_setting')) {
            foreach (['mapbox_public_token','mapbox_access_token','mapbox_token'] as $key) {
                $v = trim((string)app_setting($key, ''));
                if ($valid($v)) return $v;
            }
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
