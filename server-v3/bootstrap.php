<?php
declare(strict_types=1);

// EPC_SERVER_V3_ALPHA1: parallel server foundation. It reuses the current DB/config
// only as a compatibility bridge while the v3 modules are migrated independently.
require_once dirname(__DIR__) . '/api/bootstrap.php';

const EPC_V3_VERSION = '3.0.0-alpha1';

function v3_json(array $payload, int $status = 200): never
{
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function v3_input(): array
{
    $raw = file_get_contents('php://input');
    if (!is_string($raw) || trim($raw) === '') return [];
    $data = json_decode($raw, true);
    return is_array($data) ? $data : [];
}

function v3_require_user_json(): array
{
    $user = current_user();
    if (!$user) v3_json(['ok'=>false,'error'=>'Sessão expirada. Entre novamente na versão web.'], 401);
    return $user;
}

function v3_require_user_page(): array
{
    $user = current_user();
    if (!$user) {
        header('Location: ../login.php');
        exit;
    }
    return $user;
}

function v3_coord(float $lat, float $lon): bool
{
    return is_finite($lat) && is_finite($lon)
        && $lat >= -35.5 && $lat <= 6.5
        && $lon >= -75.5 && $lon <= -30.0;
}

function v3_haversine(float $lat1, float $lon1, float $lat2, float $lon2): float
{
    $r = 6371000.0;
    $p1 = deg2rad($lat1); $p2 = deg2rad($lat2);
    $dp = deg2rad($lat2-$lat1); $dl = deg2rad($lon2-$lon1);
    $a = sin($dp/2)**2 + cos($p1)*cos($p2)*sin($dl/2)**2;
    return $r * 2 * atan2(sqrt($a), sqrt(max(0.0, 1.0-$a)));
}

function v3_http_json(string $url, array $headers = [], int $timeout = 25): ?array
{
    $headers = array_values(array_merge(['Accept: application/json','User-Agent: EstradaPlay-Web/3.0'], $headers));
    try {
        if (function_exists('curl_init')) {
            $ch = curl_init($url);
            curl_setopt_array($ch, [
                CURLOPT_RETURNTRANSFER=>true,
                CURLOPT_FOLLOWLOCATION=>true,
                CURLOPT_CONNECTTIMEOUT=>8,
                CURLOPT_TIMEOUT=>max(8,min(45,$timeout)),
                CURLOPT_HTTPHEADER=>$headers,
                CURLOPT_ENCODING=>'',
            ]);
            $raw = curl_exec($ch);
            $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
            curl_close($ch);
            if (!is_string($raw) || $raw === '' || $status >= 400) return null;
        } else {
            $ctx = stream_context_create(['http'=>[
                'method'=>'GET','timeout'=>max(8,min(45,$timeout)),
                'ignore_errors'=>true,'header'=>implode("\r\n",$headers),
            ]]);
            $raw = @file_get_contents($url, false, $ctx);
            if (!is_string($raw) || $raw === '') return null;
        }
        $data = json_decode($raw, true);
        return is_array($data) ? $data : null;
    } catch (Throwable $e) {
        error_log('EPC V3 HTTP: ' . $e->getMessage());
        return null;
    }
}

function v3_mapbox_token(): string
{
    global $config;
    $valid = static fn(string $v): bool => preg_match('/^pk\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}$/', trim($v)) === 1;
    try {
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
    } catch (Throwable $e) {}
    foreach (['MAPBOX_PUBLIC_TOKEN','MAPBOX_ACCESS_TOKEN','MAPBOX_TOKEN'] as $key) {
        $v = trim((string)(getenv($key) ?: ''));
        if ($valid($v)) return $v;
    }
    return '';
}

function v3_account(array $user): array
{
    return [
        'id'=>(int)($user['id']??0),
        'name'=>(string)($user['name']??''),
        'username'=>(string)($user['username']??''),
        'role'=>(string)($user['role']??'client'),
    ];
}
