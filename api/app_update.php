<?php
require __DIR__ . '/bootstrap.php';
require_login();
require_session_rate_limit('app-update',12,60);
session_write_close();
$version=MUSICROAD_VERSION;
$versionCode=MUSICROAD_VERSION_CODE;
$relative='downloads/MusicRoad-1.2.0.apk';
$file=dirname(__DIR__).'/'.$relative;
$available=is_file($file)&&is_readable($file);
json_response([
    'ok'=>true,
    'version'=>$version,
    'version_code'=>$versionCode,
    'url'=>$relative,
    'size'=>$available?filesize($file):null,
    'sha256'=>$available?hash_file('sha256',$file):null,
    'available'=>$available,
    'notes'=>"MusicRoad 1.2: segurança reforçada, moderação dos alertas comunitários, motor de radares mais preciso, mapas estaduais atualizáveis e retomada integral da última viagem no Offline Core."
]);
