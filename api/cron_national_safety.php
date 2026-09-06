<?php
declare(strict_types=1);
require_once __DIR__.'/bootstrap.php';
require_once __DIR__.'/road_safety_pack_helpers.php';
require_once __DIR__.'/road_hazard_db.php';

$allowed=PHP_SAPI==='cli';
if(!$allowed){$given=trim((string)($_GET['key']??''));$allowed=$given!==''&&hash_equals(server_cron_key(),$given);}
if(!$allowed)json_response(['ok'=>false,'error'=>'Chave de cron inválida.'],403);

@set_time_limit(240);
$requested=strtoupper(trim((string)($_GET['uf']??($argv[1]??''))));
$onlyUf=ep2_valid_uf($requested)?$requested:null;
$batchRaw=(int)($_GET['batch']??($argv[2]??2));
$batch=max(1,min(4,$batchRaw));
$results=[];$allOk=true;

try{
    for($i=0;$i<$batch;$i++){
        $chunk=road_hazard_next_chunk_for_sync($onlyUf);
        if($chunk===null)break;
        $r=road_hazard_sync_chunk($chunk);$results[]=$r;
        if(empty($r['ok'])){$allOk=false;break;}
    }
    $next=road_hazard_next_chunk_for_sync($onlyUf);
    $payload=[
        'ok'=>$allOk&&!empty($results),
        'mode'=>'chunked-national-sync',
        'processed'=>$results,
        'next_chunk'=>$next===null?null:['key'=>$next['key'],'uf'=>$next['uf']],
        'national'=>['types'=>road_hazard_stats(),'states_total'=>count(ep2_brazil_ufs())]
    ];
    if($onlyUf!==null)$payload['progress']=road_hazard_chunk_progress($onlyUf);
    if(PHP_SAPI==='cli'){echo json_encode($payload,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES).PHP_EOL;exit(!empty($payload['ok'])?0:1);}
    json_response($payload,!empty($payload['ok'])?200:502);
}catch(Throwable $e){
    if(PHP_SAPI==='cli'){fwrite(STDERR,$e->getMessage().PHP_EOL);exit(1);}
    json_response(['ok'=>false,'error'=>'Falha ao atualizar a proteção nacional.'],500);
}
