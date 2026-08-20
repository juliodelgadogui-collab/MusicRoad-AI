<?php
require __DIR__ . '/bootstrap.php';
require_admin();
if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'POST') json_response(['ok'=>false,'error'=>'Use POST.'],405);
require_csrf();
require_session_rate_limit('radar-sync-brazil',3,600);
session_write_close();
require_once __DIR__ . '/radar_route_helpers.php';
require_once __DIR__ . '/radar_official_es.php';
require_once __DIR__ . '/radar_brazil_sources.php';
$started=microtime(true);
$antt=mr_sync_antt_national_to_db();
$derEs=mr_sync_der_es_to_db();
$ok=(bool)($antt['ok']??false)||(bool)($derEs['ok']??false);
json_response(['ok'=>$ok,'version'=>MUSICROAD_VERSION,'antt'=>$antt,'der_es'=>$derEs,'timing_ms'=>(int)round((microtime(true)-$started)*1000),'message'=>'Cache nacional atualizado. A rota também consulta fontes oficiais diretamente; esta sincronização é uma otimização.']);
