<?php
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: private, max-age=300');
header('X-Content-Type-Options: nosniff');

$token = '';
$style = 'mapbox://styles/mapbox/navigation-night-v1';
$enabled = true;
$source = 'none';

$validToken = static fn(string $v): bool => preg_match('/^pk\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}$/', trim($v)) === 1;
$validStyle = static fn(string $v): bool => preg_match('~^mapbox://styles/[A-Za-z0-9_-]{1,64}/[A-Za-z0-9_-]{1,128}$~', trim($v)) === 1;

try {
    $bootstrap = __DIR__ . '/bootstrap.php';
    if (is_file($bootstrap)) {
        require_once $bootstrap;
        if (function_exists('mapbox_client_config')) {
            $cfg = mapbox_client_config();
            $candidate = trim((string)($cfg['token'] ?? ''));
            if ($validToken($candidate)) {
                $token = $candidate;
                $enabled = !array_key_exists('enabled', $cfg) || !empty($cfg['enabled']);
                $candidateStyle = trim((string)($cfg['style'] ?? ''));
                if ($validStyle($candidateStyle)) $style = $candidateStyle;
                $source = 'musicRoadConfig';
            }
        }
        if ($token === '' && function_exists('app_setting')) {
            $candidate = trim((string)app_setting('mapbox_public_token', ''));
            if ($validToken($candidate)) {
                $token = $candidate;
                $candidateStyle = trim((string)app_setting('mapbox_style', ''));
                if ($validStyle($candidateStyle)) $style = $candidateStyle;
                $flag = (string)app_setting('mapbox_enabled', '1');
                $enabled = $flag !== '0';
                $source = 'appSetting';
            }
        }
        if ($token === '' && isset($config) && is_array($config)) {
            $candidate = trim((string)($config['mapbox']['public_token'] ?? $config['mapbox']['token'] ?? ''));
            if ($validToken($candidate)) {
                $token = $candidate;
                $candidateStyle = trim((string)($config['mapbox']['style'] ?? ''));
                if ($validStyle($candidateStyle)) $style = $candidateStyle;
                $enabled = !isset($config['mapbox']['enabled']) || !empty($config['mapbox']['enabled']);
                $source = 'phpConfig';
            }
        }
    }
} catch (Throwable $e) {}

if ($token === '') {
    foreach (['MAPBOX_PUBLIC_TOKEN','MAPBOX_ACCESS_TOKEN','MAPBOX_TOKEN'] as $key) {
        $candidate = trim((string)(getenv($key) ?: ''));
        if ($validToken($candidate)) { $token = $candidate; $source = 'environment'; break; }
    }
}
$envStyle = trim((string)(getenv('MAPBOX_STYLE') ?: ''));
if ($validStyle($envStyle)) $style = $envStyle;

if ($token === '') {
    $root = dirname(__DIR__);
    $candidates = [
        $root . '/client.php', $root . '/index.php', $root . '/config/config.php',
        $root . '/assets/js/app.js', $root . '/assets/js/maps.js', $root . '/assets/js/mapbox-base.js'
    ];
    foreach ($candidates as $file) {
        if (!is_file($file) || filesize($file) > 2000000) continue;
        $raw = @file_get_contents($file);
        if (!is_string($raw)) continue;
        if (preg_match('/pk\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/', $raw, $m) && $validToken($m[0])) {
            $token = $m[0]; $source = 'legacyBootstrap';
            if (preg_match('~mapbox://styles/[A-Za-z0-9_-]{1,64}/[A-Za-z0-9_-]{1,128}~', $raw, $sm) && $validStyle($sm[0])) $style = $sm[0];
            break;
        }
    }
}

$ok = $enabled && $validToken($token);
http_response_code($ok ? 200 : 503);
echo json_encode([
    'ok' => $ok,
    'enabled' => $ok,
    'token' => $ok ? $token : '',
    'style' => $style,
    'engine' => 'mapbox-android-native',
    'version' => '1.5.0',
    'source' => $source,
    'fallback' => 'musicroad-native-offline'
], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
