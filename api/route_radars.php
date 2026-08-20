<?php
require __DIR__ . '/bootstrap.php';
require_login();
if(($_SERVER['REQUEST_METHOD']??'GET')!=='POST')json_response(['ok'=>false,'error'=>'Use POST.'],405);
require_csrf();
require_session_rate_limit('route-radars',60,60);
session_write_close();
require_once __DIR__ . '/radar_route_engine.php';
$body=json_decode((string)file_get_contents('php://input'),true);if(!is_array($body))$body=[];
$coords=mr_route_clean_coords(is_array($body['coords']??null)?$body['coords']:[]);$mode=strtolower(trim((string)($body['mode']??'local')));
if(count($coords)<2)json_response(['ok'=>false,'error'=>'Rota inválida para consulta de radares.'],422);
try{
    if($mode==='local'){$r=mr_local_radars_for_route($coords);$r['mode']='local';json_response($r);}
    if($mode==='antt'){$r=mr_antt_radars_filtered_for_route($coords);$r['mode']='antt';json_response($r);}
    if($mode==='regional'){$r=mr_regional_radars_for_route($coords);$r['mode']='regional';json_response($r);}
    if($mode==='official'){$r=mr_official_radars_for_route_direct($coords);$r['mode']='official';json_response($r);}
    if($mode==='osm'){$r=mr_osm_radars_filtered_for_route($coords);$r['mode']='osm';json_response($r);}
}catch(Throwable $e){$incident=report_runtime_exception('route_radars_'.$mode,$e);json_response(['ok'=>false,'mode'=>$mode,'error'=>'Falha temporária no módulo de radares.','incident'=>$incident],500);}
json_response(['ok'=>false,'error'=>'Modo de consulta de radares inválido.'],422);
