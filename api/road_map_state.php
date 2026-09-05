<?php
declare(strict_types=1);
require __DIR__.'/bootstrap.php';
require_login();
require __DIR__.'/road_map_helpers.php';
@set_time_limit(140);
header('Cache-Control: private, max-age=21600');

$uf=strtoupper(trim((string)($_GET['uf']??'')));
if(!in_array($uf,['SP','RJ','MG','ES'],true))json_response(['ok'=>false,'error'=>'Estado ainda não disponível para mapa offline.'],422);

$features=[];$coordinateCount=0;$osmOk=false;$message='';
try{
    global $config;
    $query=epm_road_query_for_state($uf);
    $osm=http_json($config['routing']['overpass_url'],'data='.urlencode($query),['Content-Type: application/x-www-form-urlencoded']);
    if(is_array($osm)){
        [$features,$coordinateCount]=epm_features($osm['elements']??[],15000,220000);
        $osmOk=count($features)>0;
    }
}catch(Throwable $e){$message='Não foi possível atualizar a malha estadual agora.';}

json_response([
    'ok'=>$osmOk,
    'version'=>'1.0',
    'kind'=>'state_map',
    'uf'=>$uf,
    'generated_at'=>gmdate('c'),
    'expires_in_s'=>$osmOk?2592000:3600,
    'roads'=>['type'=>'FeatureCollection','features'=>$features],
    'coverage'=>['osm_ok'=>$osmOk,'features'=>count($features),'coordinates'=>$coordinateCount],
    'message'=>$message
],$osmOk?200:502);
