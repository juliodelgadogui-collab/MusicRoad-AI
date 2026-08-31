<?php
declare(strict_types=1);
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
$configFile=__DIR__.'/../config/config.php';
$lockFile=__DIR__.'/../storage/install.lock';
if(!is_file($configFile)||!is_file($lockFile)){
    http_response_code(200);
    echo json_encode(['ok'=>false,'installed'=>false,'setup_required'=>true,'server_version'=>'500MB-v6'],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);exit;
}
try{
    require_once __DIR__.'/bootstrap.php';
    require_once __DIR__.'/server_intelligent.php';
    db()->query('SELECT 1');
    echo json_encode(['ok'=>true,'installed'=>true,'setup_required'=>false,'server_version'=>ESTRADAPLAY_SERVER_INTELLIGENT_VERSION,'time'=>date(DATE_ATOM)],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);
}catch(Throwable $e){http_response_code(503);echo json_encode(['ok'=>false,'installed'=>false,'setup_required'=>false,'server_version'=>'500MB-v6','error'=>'Banco ou configuração indisponível.'],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);}
