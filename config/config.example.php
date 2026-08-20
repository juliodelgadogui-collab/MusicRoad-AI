<?php
// Este arquivo é apenas referência. O instalador /install/ gera config/config.php automaticamente.
return [
    'app_name' => 'MusicRoad AI',
    'app_url' => 'https://seu-dominio.com',
    'env' => 'production',
    'force_https' => true,
    'db' => [
        'driver' => 'mysql',
        'host' => '127.0.0.1',
        'port' => 3306,
        'name' => 'musicroad',
        'user' => 'musicroad_app',
        'password' => '',
        // Preencha ssl_ca somente se o banco for remoto e seu provedor exigir TLS.
        'ssl_ca' => '',
    ],
    'google' => [
        'api_key' => '',
        'client_id' => '',
        'client_secret' => '',
        'redirect_uri' => 'https://seu-dominio.com/api/google_drive.php?action=callback',
        'scopes' => ['https://www.googleapis.com/auth/drive.readonly'],
    ],
    'routing' => [
        'osrm_base_url' => 'https://router.project-osrm.org',
        'osrm_fallback_urls' => ['https://routing.openstreetmap.de/routed-car'],
        'overpass_url' => 'https://overpass-api.de/api/interpreter',
        'nominatim_url' => 'https://nominatim.openstreetmap.org/search',
        'user_agent' => 'MusicRoadAI/1.2.0 (+https://seu-dominio.com)',
    ],
    'security' => [
        'session_name' => 'MUSICROADAISESSID',
        'rate_limit_per_minute' => 90,
        // Gere com: php -r "echo bin2hex(random_bytes(32)), PHP_EOL;"
        'app_key' => '',
    ],
];
