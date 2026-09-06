<?php
declare(strict_types=1);
require_once __DIR__.'/bootstrap.php';
require_admin();
require_once __DIR__.'/road_safety_pack_helpers.php';
require_once __DIR__.'/road_hazard_db.php';
require_once __DIR__.'/national_safety_sync_helpers.php';

road_hazard_ensure_tables();
$method=strtoupper((string)($_SERVER['REQUEST_METHOD']??'GET'));
if($method==='POST'){
    require_csrf();$body=input_json();$uf=strtoupper(trim((string)($body['uf']??$_POST['uf']??'')));
    if(!ep2_valid_uf($uf))json_response(['ok'=>false,'error'=>'UF inválida.'],422);
    @set_time_limit(240);$full=!empty($body['full']);
    if($full){$sync=road_hazard_sync_state($uf);audit_log('road_safety.sync_state_full',['uf'=>$uf,'result'=>$sync]);}
    else{$chunk=national_safety_next_balanced_chunk($uf);$sync=$chunk===null?['ok'=>true,'uf'=>$uf,'count'=>0,'message'=>'Nenhum bloco pendente.']:road_hazard_sync_chunk($chunk);audit_log('road_safety.sync_state_chunk',['uf'=>$uf,'chunk'=>$chunk['key']??null,'result'=>$sync]);}
    json_response(['ok'=>!empty($sync['ok']),'uf'=>$uf,'sync'=>$sync,'progress'=>road_hazard_chunk_progress($uf),'types'=>road_hazard_stats($uf)],!empty($sync['ok'])?200:502);
}

$status=road_hazard_sync_status();$byUf=[];$statesWithData=0;$total=0;$chunksDone=0;$chunksTotal=0;
foreach(ep2_brazil_ufs() as $uf){
    $row=null;foreach($status as $r)if(strtoupper((string)$r['uf'])===$uf){$row=$r;break;}
    $count=road_hazard_count_state($uf);$total+=$count;if($count>0)$statesWithData++;
    $progress=road_hazard_chunk_progress($uf);$chunksDone+=(int)$progress['done'];$chunksTotal+=(int)$progress['total'];
    $byUf[]=['uf'=>$uf,'count'=>$count,'progress'=>$progress,'last_success'=>$row['last_success']??null,'last_attempt'=>$row['last_attempt']??null,'last_error'=>$row['last_error']??null];
}
$next=national_safety_next_balanced_chunk();
json_response([
    'ok'=>true,'country'=>'BR','states_total'=>count(ep2_brazil_ufs()),'states_with_data'=>$statesWithData,
    'points_total'=>$total,'by_type'=>road_hazard_stats(),
    'chunk_progress'=>['done'=>$chunksDone,'total'=>$chunksTotal,'percent'=>$chunksTotal>0?round($chunksDone*100/$chunksTotal,1):0],
    'next_chunk'=>$next===null?null:['key'=>$next['key'],'uf'=>$next['uf']],
    'states'=>$byUf
]);
