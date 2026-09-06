<?php
declare(strict_types=1);
require_once __DIR__.'/bootstrap.php';
require_once __DIR__.'/road_safety_pack_helpers.php';
require_once __DIR__.'/road_hazard_db.php';

// Run from CLI or with the same protected cron key already used by the project.
$allowed=PHP_SAPI==='cli';
if(!$allowed){$given=trim((string)($_GET['key']??''));$allowed=$given!==''&&hash_equals(server_cron_key(),$given);}
if(!$allowed)json_response(['ok'=>false,'error'=>'Chave de cron inválida.'],403);

@set_time_limit(240);
$requested=strtoupper(trim((string)($_GET['uf']??($argv[1]??''))));
$uf=ep2_valid_uf($requested)?$requested:road_hazard_next_uf_for_sync();

try{
    $result=road_hazard_sync_state($uf);
    $payload=[
        'ok'=>!empty($result['ok']),
        'uf'=>$uf,
        'result'=>$result,
        'national'=>[
            'types'=>road_hazard_stats(),
            'next_uf'=>road_hazard_next_uf_for_sync(),
            'states_total'=>count(ep2_brazil_ufs())
        ]
    ];
    if(PHP_SAPI==='cli'){echo json_encode($payload,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES).PHP_EOL;exit(!empty($result['ok'])?0:1);}
    json_response($payload,!empty($result['ok'])?200:502);
}catch(Throwable $e){
    if(PHP_SAPI==='cli'){fwrite(STDERR,$e->getMessage().PHP_EOL);exit(1);}
    json_response(['ok'=>false,'uf'=>$uf,'error'=>'Falha ao atualizar a proteção nacional.'],500);
}
