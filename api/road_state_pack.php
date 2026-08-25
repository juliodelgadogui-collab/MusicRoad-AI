<?php
declare(strict_types=1);
require __DIR__.'/bootstrap.php';
require_login();
require __DIR__.'/road_safety_pack_helpers.php';
@set_time_limit(120);
header('Cache-Control: private, max-age=3600');

$uf=strtoupper(trim((string)($_GET['uf']??'')));
$allowed=['SP','RJ','MG','ES'];
if(!in_array($uf,$allowed,true))json_response(['ok'=>false,'error'=>'Estado ainda não disponível para pacote offline.'],422);

$items=[];$seen=[];$localCount=0;$osmOk=false;
try{
    $stmt=db()->prepare('SELECT id, external_id, latitude, longitude, rodovia, heading, sentido, velocidade, tipo, fonte FROM radars WHERE ativo = 1 AND UPPER(COALESCE(uf,\'\')) = ? LIMIT 20000');
    $stmt->execute([$uf]);
    foreach(($stmt->fetchAll()?:[]) as $r){
        ep2_add($items,$seen,[
            'id'=>!empty($r['external_id'])?(string)$r['external_id']:'db-radar-'.(string)$r['id'],
            'type'=>'RADAR','lat'=>(float)$r['latitude'],'lon'=>(float)$r['longitude'],
            'road'=>$r['rodovia']??'','speed'=>ep2_speed($r['velocidade']??null),
            'heading'=>is_numeric($r['heading']??null)?(float)$r['heading']:ep2_heading($r['sentido']??null),
            'source'=>$r['fonte']??'BASE_LOCAL'
        ]);
        $localCount++;
    }
}catch(Throwable $e){}

try{
    global $config;
    $selector='area["ISO3166-2"="BR-'.$uf.'"][boundary="administrative"]->.eparea;';
    $query=ep2_osm_query_for_area($selector);
    $osm=http_json($config['routing']['overpass_url'],'data='.urlencode($query),['Content-Type: application/x-www-form-urlencoded']);
    if(is_array($osm)){
        $osmOk=true;
        foreach(($osm['elements']??[]) as $el){
            if(count($items)>=65000)break;
            $h=ep2_osm_to_hazard($el);if($h!==null)ep2_add($items,$seen,$h);
        }
    }
}catch(Throwable $e){}

json_response([
    'ok'=>true,
    'version'=>'1.0',
    'kind'=>'state',
    'uf'=>$uf,
    'generated_at'=>gmdate('c'),
    'expires_in_s'=>$osmOk?2592000:43200,
    'hazards'=>$items,
    'coverage'=>[
        'uf'=>$uf,
        'local_radars'=>$localCount,
        'osm_ok'=>$osmOk,
        'total'=>count($items),
        'truncated'=>count($items)>=65000
    ]
]);
