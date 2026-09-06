<?php
declare(strict_types=1);
require_once __DIR__.'/bootstrap.php';
require_admin();
require_once __DIR__.'/road_safety_pack_helpers.php';
require_once __DIR__.'/road_hazard_db.php';

road_hazard_ensure_tables();
$method=strtoupper((string)($_SERVER['REQUEST_METHOD']??'GET'));
if($method==='POST'){
    require_csrf();
    $body=input_json();
    $uf=strtoupper(trim((string)($body['uf']??$_POST['uf']??'')));
    if(!ep2_valid_uf($uf))json_response(['ok'=>false,'error'=>'UF inválida.'],422);
    @set_time_limit(240);
    $sync=road_hazard_sync_state($uf);
    audit_log('road_safety.sync_state',['uf'=>$uf,'result'=>$sync]);
    json_response(['ok'=>!empty($sync['ok']),'uf'=>$uf,'sync'=>$sync,'types'=>road_hazard_stats($uf)],!empty($sync['ok'])?200:502);
}

$status=road_hazard_sync_status();$byUf=[];$synced=0;$total=0;
foreach(ep2_brazil_ufs() as $uf){
    $row=null;foreach($status as $r)if(strtoupper((string)$r['uf'])===$uf){$row=$r;break;}
    $count=road_hazard_count_state($uf);$total+=$count;if($count>0)$synced++;
    $byUf[]=['uf'=>$uf,'count'=>$count,'last_success'=>$row['last_success']??null,'last_attempt'=>$row['last_attempt']??null,'last_error'=>$row['last_error']??null];
}
json_response([
    'ok'=>true,'country'=>'BR','states_total'=>count(ep2_brazil_ufs()),'states_with_data'=>$synced,
    'points_total'=>$total,'by_type'=>road_hazard_stats(),'next_uf'=>road_hazard_next_uf_for_sync(),'states'=>$byUf
]);
