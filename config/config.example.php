<?php
return [
    'app_name' => 'MusicRoad AI',
    'app_version' => '1.0.0',
    'app_url' => '',
    'env' => 'production',
    'force_https' => true,
    'db' => [
        'driver' => 'sqlite',
        'sqlite_path' => __DIR__ . '/../storage/db/musicroad.sqlite',
        'mysql_dsn' => 'mysql:host=localhost;dbname=musicroad;charset=utf8mb4',
        'mysql_user' => '',
        'mysql_pass' => '',
    ],
    'google' => [
        'api_key' => '',
    ],
    'routing' => [
        'osrm_base_url' => 'https://router.project-osrm.org',
        'overpass_url' => 'https://overpass-api.de/api/interpreter',
        'nominatim_url' => 'https://nominatim.openstreetmap.org/search',
        'user_agent' => 'MusicRoadAI/1.0',
    ],
    'security' => [
        'session_name' => 'MUSICROADAISESSID',
        'rate_limit_per_minute' => 90,
    ],
];
