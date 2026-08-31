<?php
declare(strict_types=1);
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
$configFile=__DIR__.'/../config/config.php';
$lockFile=__DIR__.'/../storage/install.lock';

if(!is_file($configFile)){
    http_response_code(200);
    echo json_encode(['ok'=>false,'installed'=>false,'setup_required'=>true,'server_version'=>'500MB-v6.1'],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);exit;
}

try{
    // Existing EstradaPlay servers already have config.php. If the DB is healthy,
    // adopt the installation automatically instead of forcing install.php again.
    require_once __DIR__.'/bootstrap.php';
    require_once __DIR__.'/server_intelligent.php';
    db()->query('SELECT 1');
    if(!is_file($lockFile)){
        $dir=dirname($lockFile);
        if(!is_dir($dir)) @mkdir($dir,0775,true);
        @file_put_contents($lockFile,json_encode([
            'installed_at'=>date(DATE_ATOM),
            'version'=>'v6.1',
            'mode'=>'existing-installation-adopted'
        ],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES),LOCK_EX);
        @chmod($lockFile,0640);
    }
    echo json_encode([
        'ok'=>true,
        'installed'=>true,
        'setup_required'=>false,
        'server_version'=>defined('ESTRADAPLAY_SERVER_INTELLIGENT_VERSION')?ESTRADAPLAY_SERVER_INTELLIGENT_VERSION:'500MB-v6.1',
        'adopted_existing'=>true,
        'time'=>date(DATE_ATOM)
    ],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);
}catch(Throwable $e){
    http_response_code(503);
    echo json_encode(['ok'=>false,'installed'=>false,'setup_required'=>false,'server_version'=>'500MB-v6.1','error'=>'Configuração encontrada, mas o banco não respondeu.'],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);
}
