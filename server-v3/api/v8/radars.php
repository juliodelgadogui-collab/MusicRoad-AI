<?php
declare(strict_types=1);
require_once __DIR__ . '/../../bootstrap.php';
require_once dirname(__DIR__,3) . '/api/radar_db.php';
require_once dirname(__DIR__,3) . '/api/official_data_policy.php';
$user = v3_require_user_json();
radar_ensure_tables();
$lat=(float)($_GET['lat']??NAN);$lon=(float)($_GET['lon']??NAN);
if(!v3_coord($lat,$lon))v3_json(['ok'=>false,'error'=>'Coordenadas inválidas.'],422);
$radius=max(1000,min(50000,(int)($_GET['radius']??15000)));
$latBox=$radius/110540.0;$lonBox=$radius/(111320.0*max(.25,cos(deg2rad($lat))));
$officialOnly=epc_official_data_only();
$where='r.ativo=1 AND r.latitude BETWEEN ? AND ? AND r.longitude BETWEEN ? AND ?';
if($officialOnly)$where.=' AND '.epc_official_radar_sql('r');
$sql='SELECT r.id,r.latitude,r.longitude,r.uf,r.cidade,r.rodovia,r.km,r.sentido,r.heading,r.velocidade,r.tipo,r.situacao,r.fonte,r.confiabilidade,r.quantidade_fontes FROM radars r WHERE '.$where.' LIMIT 1800';
try{
    $s=db()->prepare($sql);$s->execute([$lat-$latBox,$lat+$latBox,$lon-$lonBox,$lon+$lonBox]);$rows=$s->fetchAll()?:[];
    $out=[];
    foreach($rows as $r){
        $d=v3_haversine($lat,$lon,(float)$r['latitude'],(float)$r['longitude']);
        if($d>$radius)continue;
        $r['distance_m']=(int)round($d);$out[]=$r;
    }
    usort($out,fn($a,$b)=>(int)$a['distance_m']<=>(int)$b['distance_m']);
    v3_json(['ok'=>true,'version'=>EPC_V3_VERSION,'road_data_mode'=>$officialOnly?'OFFICIAL':'COMMUNITY','radars'=>array_slice($out,0,300),'account'=>v3_account($user)]);
}catch(Throwable $e){
    error_log('EPC V3 RADARS: '.$e->getMessage());
    v3_json(['ok'=>false,'error'=>'Não foi possível consultar radares agora.'],503);
}
