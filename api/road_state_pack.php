<?php
declare(strict_types=1);
require __DIR__.'/bootstrap.php';
require_login();
require __DIR__.'/road_safety_pack_helpers.php';
require __DIR__.'/road_hazard_db.php';
@set_time_limit(160);
header('Cache-Control: private, max-age=1800');

$uf=strtoupper(trim((string)($_GET['uf']??'')));
$allowed=['SP','RJ','MG','ES'];
if(!in_array($uf,$allowed,true))json_response(['ok'=>false,'error'=>'Estado ainda não disponível para pacote offline.'],422);

road_hazard_ensure_tables();
$items=[];$seen=[];$localCount=0;$storedCount=0;$syncAttempted=false;$syncOk=false;$message='';
[$minLat,$minLon,$maxLat,$maxLon]=ep2_state_bounds($uf);
$normalizeLocalType=static function($raw): string {
    $v=strtoupper(trim((string)$raw));
    if(stripos($v,'QUEBRA')!==false||stripos($v,'LOMB')!==false)return 'QUEBRA_MOLAS';
    if(stripos($v,'SEM')===0)return 'SEMAFORO';
    if(stripos($v,'PED')===0)return 'PEDAGIO';
    if(stripos($v,'PASS')===0)return 'PASSAGEM_NIVEL';
    return 'RADAR';
};

try{
    $stmt=db()->prepare('SELECT id, external_id, latitude, longitude, uf, rodovia, heading, sentido, velocidade, tipo, fonte FROM radars WHERE ativo = 1 AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT 30000');
    $stmt->execute([$minLat,$maxLat,$minLon,$maxLon]);
    foreach(($stmt->fetchAll()?:[]) as $r){
        $rlat=(float)$r['latitude'];$rlon=(float)$r['longitude'];
        $storedUf=strtoupper(trim((string)($r['uf']??'')));
        if($storedUf!=='' && $storedUf!==$uf)continue;
        if($storedUf==='' && ep2_guess_uf($rlat,$rlon)!==$uf)continue;
        ep2_add($items,$seen,[
            'id'=>!empty($r['external_id'])?(string)$r['external_id']:'db-radar-'.(string)$r['id'],
            'type'=>$normalizeLocalType($r['tipo']??'RADAR'),'lat'=>$rlat,'lon'=>$rlon,
            'road'=>$r['rodovia']??'','speed'=>ep2_speed($r['velocidade']??null),
            'heading'=>is_numeric($r['heading']??null)?(float)$r['heading']:ep2_heading($r['sentido']??null),
            'source'=>$r['fonte']??'BASE_LOCAL'
        ]);
        $localCount++;
    }
}catch(Throwable $e){$message='Base local de radares indisponível: '.$e->getMessage();}

try{
    $stored=road_hazard_state_rows($uf);
    if(count($stored)===0){
        $syncAttempted=true;
        $sync=road_hazard_sync_state($uf);
        $syncOk=!empty($sync['ok']);
        if($syncOk){
            $stored=road_hazard_state_rows($uf);
        }else{
            $message=$message!==''?$message:(string)($sync['error']??'Não foi possível atualizar a base rodoviária.');
        }
    }else{
        $syncOk=true;
    }
    foreach($stored as $h){
        if(count($items)>=65000)break;
        ep2_add($items,$seen,[
            'id'=>(string)$h['hazard_key'],
            'type'=>(string)$h['type'],
            'lat'=>(float)$h['latitude'],
            'lon'=>(float)$h['longitude'],
            'road'=>$h['road']??'',
            'speed'=>$h['speed']??null,
            'heading'=>$h['heading']??null,
            'source'=>$h['source']??'OPENSTREETMAP'
        ]);
        $storedCount++;
    }
}catch(Throwable $e){$message=$message!==''?$message:'Falha ao consultar a base unificada de alertas.';}

$typeCounts=[];
foreach($items as $h){$t=(string)($h['type']??'OUTRO');$typeCounts[$t]=($typeCounts[$t]??0)+1;}
$ok=count($items)>0;
json_response([
    'ok'=>$ok,
    'version'=>'1.2',
    'kind'=>'state',
    'uf'=>$uf,
    'generated_at'=>gmdate('c'),
    'expires_in_s'=>$storedCount>0?2592000:21600,
    'hazards'=>$items,
    'coverage'=>[
        'uf'=>$uf,
        'local_radars'=>$localCount,
        'stored_hazards'=>$storedCount,
        'sync_attempted'=>$syncAttempted,
        'osm_ok'=>$syncOk,
        'total'=>count($items),
        'by_type'=>$typeCounts,
        'truncated'=>count($items)>=65000,
        'bounds'=>['south'=>$minLat,'west'=>$minLon,'north'=>$maxLat,'east'=>$maxLon]
    ],
    'message'=>$message
],$ok?200:502);
