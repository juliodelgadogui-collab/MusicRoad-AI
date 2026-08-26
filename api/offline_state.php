<?php
declare(strict_types=1);
require __DIR__.'/bootstrap.php';
require_login();
require_once __DIR__.'/road_safety_pack_helpers.php';
require_once __DIR__.'/radar_db.php';
require_once __DIR__.'/road_hazard_db.php';

header('Cache-Control: private, max-age=1800');

$kind=strtolower(trim((string)($_GET['kind']??'radars')));
$uf=strtoupper(trim((string)($_GET['uf']??'')));
if($uf===''){
    $stateId=(string)($_GET['state_id']??'');
    $uf=match($stateId){'35'=>'SP','31'=>'MG','32'=>'ES','33'=>'RJ',default=>''};
}
if($kind!=='radars')json_response(['ok'=>false,'error'=>'Tipo de pacote não suportado.'],422);
if(!in_array($uf,['SP','MG','ES','RJ'],true))json_response(['ok'=>false,'error'=>'UF inválida.'],422);

radar_ensure_tables();
road_hazard_ensure_tables();
[$minLat,$minLon,$maxLat,$maxLon]=ep2_state_bounds($uf);
$items=[];$seen=[];

$add=static function(array $r) use (&$items,&$seen,$uf): void {
    $lat=(float)($r['latitude']??0);$lon=(float)($r['longitude']??0);
    if(!$lat||!$lon)return;
    $rowUf=strtoupper(trim((string)($r['uf']??'')));
    if($rowUf!==''&&$rowUf!==$uf)return;
    if($rowUf===''&&ep2_guess_uf($lat,$lon)!==$uf)return;
    $type=strtoupper(trim((string)($r['tipo']??$r['type']??'RADAR')));
    if(stripos($type,'QUEBRA')!==false||stripos($type,'LOMB')!==false)$type='QUEBRA_MOLAS';
    elseif(stripos($type,'SEM')===0)$type='SEMAFORO';
    elseif(stripos($type,'PED')===0)$type='PEDAGIO';
    elseif(stripos($type,'PASS')===0)$type='PASSAGEM_NIVEL';
    else $type='RADAR';
    $id=trim((string)($r['external_id']??$r['hazard_key']??$r['id']??''));
    if($id==='')$id='geo-'.$type.'-'.round($lat,5).'-'.round($lon,5);
    $geo=$type.'|'.round($lat,5).'|'.round($lon,5);
    if(isset($seen[$id])||isset($seen[$geo]))return;
    $seen[$id]=true;$seen[$geo]=true;
    $items[]=[
        'external_id'=>$id,
        'latitude'=>$lat,
        'longitude'=>$lon,
        'rodovia'=>(string)($r['rodovia']??$r['road']??''),
        'heading'=>isset($r['heading'])&&is_numeric($r['heading'])?(float)$r['heading']:ep2_heading($r['sentido']??null),
        'sentido'=>(string)($r['sentido']??''),
        'velocidade'=>ep2_speed($r['velocidade']??$r['speed']??null),
        'tipo'=>$type,
        'fonte'=>(string)($r['fonte']??$r['source']??'BASE_LOCAL'),
        'uf'=>$uf,
    ];
};

try{
    $stmt=db()->prepare('SELECT id,external_id,latitude,longitude,uf,rodovia,heading,sentido,velocidade,tipo,fonte FROM radars WHERE ativo=1 AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? ORDER BY id ASC LIMIT 40000');
    $stmt->execute([$minLat,$maxLat,$minLon,$maxLon]);
    foreach(($stmt->fetchAll()?:[]) as $r)$add($r);
}catch(Throwable $e){}

try{
    foreach(road_hazard_state_rows($uf,65000) as $r)$add($r);
}catch(Throwable $e){}

json_response([
    'ok'=>count($items)>0,
    'kind'=>'radars',
    'uf'=>$uf,
    'state_id'=>$_GET['state_id']??null,
    'generated_at'=>gmdate('c'),
    'radars'=>$items,
    'total'=>count($items),
],count($items)>0?200:502);
