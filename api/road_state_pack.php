<?php
declare(strict_types=1);
require __DIR__.'/bootstrap.php';
require_once __DIR__.'/native_auth.php';
if (!native_restore_user_from_request(null)) json_response(['ok'=>false,'error'=>'Sessão expirada. Entre novamente neste aparelho.'],401);
require_once __DIR__.'/road_safety_pack_helpers.php';
require_once __DIR__.'/road_hazard_db.php';
@set_time_limit(220);
header('Cache-Control: private, max-age=1800');

$uf=strtoupper(trim((string)($_GET['uf']??'')));
if(!ep2_valid_uf($uf))json_response(['ok'=>false,'error'=>'Estado inválido.'],422);
$forceRefresh=!empty($_GET['refresh']);
road_hazard_ensure_tables();

// Normal app requests only receive a state-wide file after every geographic block
// has been synchronized. Until then the Android client uses national nearby/corridor packs.
$progress=road_hazard_chunk_progress($uf);$manualComplete=false;$syncAttempted=false;$syncOk=false;
if($forceRefresh){
    $syncAttempted=true;$sync=road_hazard_sync_state($uf);$syncOk=!empty($sync['ok']);$manualComplete=$syncOk;
    if(!$syncOk)json_response(['ok'=>false,'error'=>'Não foi possível atualizar esta proteção agora.','coverage'=>['uf'=>$uf,'complete'=>false,'progress'=>$progress]],502);
}
$complete=$manualComplete||((int)$progress['total']>0&&(int)$progress['done']>=(int)$progress['total']);
if(!$complete){
    json_response(['ok'=>false,'error'=>'Proteção estadual ainda está sendo preparada.','coverage'=>['uf'=>$uf,'complete'=>false,'progress'=>$progress]],409);
}

$items=[];$seen=[];$localCount=0;$storedCount=0;$message='';
[$minLat,$minLon,$maxLat,$maxLon]=ep2_state_bounds($uf);
$normalizeLocalType=static function($raw): string {
    $v=strtoupper(trim((string)$raw));$v=strtr($v,['Á'=>'A','À'=>'A','Ã'=>'A','Â'=>'A','É'=>'E','Ê'=>'E','Í'=>'I','Ó'=>'O','Ô'=>'O','Õ'=>'O','Ú'=>'U','Ç'=>'C']);
    if(str_contains($v,'QUEBRA')||str_contains($v,'LOMB'))return 'QUEBRA_MOLAS';
    if((str_contains($v,'SEMAFOR')||str_contains($v,'REDLIGHT'))&&(str_contains($v,'RAD')||str_contains($v,'CAMERA')||str_contains($v,'REDLIGHT')))return 'SEMAFORO_RADAR';
    if(str_contains($v,'SEM')||str_contains($v,'TRAFFIC_SIGNAL'))return 'SEMAFORO';
    if(str_contains($v,'MONITOR')||str_contains($v,'CCTV')||str_contains($v,'CAMERA')||str_contains($v,'VIDEO'))return 'CAMERA_MONITORAMENTO';
    if(str_contains($v,'PED')||str_contains($v,'TOLL'))return 'PEDAGIO';
    if(str_contains($v,'PASS')||str_contains($v,'LEVEL_CROSSING'))return 'PASSAGEM_NIVEL';
    return 'RADAR';
};

try{
    $stmt=db()->prepare('SELECT id,external_id,latitude,longitude,uf,rodovia,heading,sentido,velocidade,tipo,fonte FROM radars WHERE ativo=1 AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT 60000');$stmt->execute([$minLat,$maxLat,$minLon,$maxLon]);
    foreach(($stmt->fetchAll()?:[])as$r){$rlat=(float)$r['latitude'];$rlon=(float)$r['longitude'];$storedUf=strtoupper(trim((string)($r['uf']??'')));if($storedUf!==''&&$storedUf!==$uf)continue;if($storedUf===''&&ep2_guess_uf($rlat,$rlon)!==$uf)continue;ep2_add($items,$seen,['id'=>!empty($r['external_id'])?(string)$r['external_id']:'db-point-'.(string)$r['id'],'type'=>$normalizeLocalType($r['tipo']??'RADAR'),'lat'=>$rlat,'lon'=>$rlon,'road'=>$r['rodovia']??'','speed'=>ep2_speed($r['velocidade']??null),'heading'=>is_numeric($r['heading']??null)?(float)$r['heading']:ep2_heading($r['sentido']??null),'source'=>$r['fonte']??'BASE_LOCAL']);$localCount++;}
}catch(Throwable $e){$message='Uma das fontes locais não pôde ser lida.';}

try{
    foreach(road_hazard_state_rows($uf,120000)as$h){if(count($items)>=150000)break;ep2_add($items,$seen,['id'=>(string)$h['hazard_key'],'type'=>(string)$h['type'],'lat'=>(float)$h['latitude'],'lon'=>(float)$h['longitude'],'road'=>$h['road']??'','speed'=>$h['speed']??null,'heading'=>$h['heading']??null,'source'=>$h['source']??'OPENSTREETMAP']);$storedCount++;}
}catch(Throwable $e){$message=$message!==''?$message:'Proteção offline temporariamente indisponível.';}

$typeCounts=[];foreach($items as$h){$t=(string)($h['type']??'OUTRO');$typeCounts[$t]=($typeCounts[$t]??0)+1;}$ok=count($items)>0;
json_response([
    'ok'=>$ok,'version'=>'2.1','kind'=>'state','uf'=>$uf,'generated_at'=>gmdate('c'),'expires_in_s'=>2592000,'hazards'=>$items,
    'coverage'=>['uf'=>$uf,'complete'=>true,'progress'=>$progress,'local_points'=>$localCount,'stored_points'=>$storedCount,'sync_attempted'=>$syncAttempted,'source_ok'=>true,'total'=>count($items),'by_type'=>$typeCounts,'truncated'=>count($items)>=150000,'bounds'=>['south'=>$minLat,'west'=>$minLon,'north'=>$maxLat,'east'=>$maxLon]],
    'message'=>$message
],$ok?200:502);
