<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
require_login();
require_once dirname(__DIR__) . '/lib/LightMap.php';

$action = strtolower(trim((string)($_GET['action'] ?? 'status')));
$method = strtoupper((string)($_SERVER['REQUEST_METHOD'] ?? 'GET'));
$code = preg_replace('/\D+/', '', (string)($_GET['city_id'] ?? ''));
$uf = strtoupper(trim((string)($_GET['uf'] ?? '')));
$city = trim((string)($_GET['city'] ?? ''));

if ($action === 'stats') {
    require_session_rate_limit('map-light-read',120,60);session_write_close();
    json_response(['ok'=>true,'map_light'=>lightmap_stats(),'mode'=>'on-demand','version'=>MUSICROAD_VERSION]);
}
if (!preg_match('/^\d{7}$/',$code)) json_response(['ok'=>false,'error'=>'Município inválido.'],422);

if ($action === 'status') {
    require_session_rate_limit('map-light-read',120,60);session_write_close();
    json_response(['ok'=>true] + lightmap_status($code));
}
if ($action === 'prepare') {
    if($method!=='POST')json_response(['ok'=>false,'error'=>'Use POST.'],405);
    require_csrf();require_session_rate_limit('map-light-prepare',8,3600);session_write_close();
    if (!preg_match('/^[A-Z]{2}$/',$uf) || $city === '') json_response(['ok'=>false,'error'=>'Informe município e UF.'],422);
    @set_time_limit(110);
    try { json_response(['ok'=>true] + lightmap_prepare($code,$uf,$city,!empty($_GET['force']))); }
    catch(Throwable $e){$incident=report_runtime_exception('map_light',$e);json_response(['ok'=>false,'error'=>'Não foi possível preparar o mapa local agora.','incident'=>$incident,'status'=>'failed'],503);}
}
if ($action === 'data') {
    require_session_rate_limit('map-light-read',120,60);session_write_close();
    $paths=lightmap_paths($code);
    if(!is_file($paths['data']) || filesize($paths['data'])<20)json_response(['ok'=>false,'error'=>'Mapa local ainda não foi preparado.','needs_prepare'=>true],404);
    @touch($paths['data']);
    header('Content-Type: application/geo+json; charset=utf-8');
    header('Cache-Control: private, max-age=86400');
    header('Vary: Accept-Encoding');
    header('X-MusicRoad-Map: city-light');
    header('X-MusicRoad-City: '.$code);
    $accept=(string)($_SERVER['HTTP_ACCEPT_ENCODING']??'');
    if(stripos($accept,'gzip')!==false){
        @ini_set('zlib.output_compression','0');
        if(function_exists('apache_setenv'))@apache_setenv('no-gzip','1');
        header('Content-Encoding: gzip');readfile($paths['data']);
    }else{
        $raw=@file_get_contents($paths['data']);$decoded=is_string($raw)?@gzdecode($raw):false;
        if(!is_string($decoded))json_response(['ok'=>false,'error'=>'Falha ao ler o mapa local.'],500);
        echo $decoded;
    }
    exit;
}
json_response(['ok'=>false,'error'=>'Ação inválida.'],422);
