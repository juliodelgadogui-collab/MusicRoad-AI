<?php
declare(strict_types=1);

// Public compatibility endpoint for hosts that block direct HTTP access to /api.
// The actual configuration logic remains in api/mapbox_config.php.
$endpoint = __DIR__ . '/api/mapbox_config.php';

if (!is_file($endpoint)) {
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    header('X-Content-Type-Options: nosniff');
    http_response_code(503);
    echo json_encode([
        'ok' => false,
        'enabled' => false,
        'token' => '',
        'style' => 'mapbox://styles/mapbox/navigation-night-v1',
        'engine' => 'mapbox-android-native',
        'version' => '1.5.1',
        'error' => 'mapbox_config_backend_missing',
        'fallback' => 'musicroad-native-offline'
    ], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    exit;
}

require $endpoint;
