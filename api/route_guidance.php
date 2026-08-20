<?php
require __DIR__ . '/bootstrap.php';
if (!current_user()) json_response(['ok'=>false,'error'=>'Sua sessão expirou. Entre novamente no MusicRoad.','session_expired'=>true],401);
require_login();
require_session_rate_limit('route-guidance',40,60);
session_write_close();
require_once dirname(__DIR__) . '/lib/RouteEngine.php';

$olat=(float)($_GET['olat']??999);$olon=(float)($_GET['olon']??999);$dlat=(float)($_GET['dlat']??999);$dlon=(float)($_GET['dlon']??999);
if(!mr_route_coord($olat,$olon)||!mr_route_coord($dlat,$dlon))json_response(['ok'=>false,'error'=>'Coordenadas inválidas para orientação.'],422);
$started=microtime(true);$calc=mr_route_compute($olat,$olon,$dlat,$dlon,true,false);
if(empty($calc['ok']))json_response(['ok'=>false,'error'=>'Orientações indisponíveis agora.','routing'=>['attempt_count'=>count($calc['attempts']??[])]],503);
$route=$calc['route'];$out=[];
foreach(($route['legs']??[]) as $leg){foreach(($leg['steps']??[]) as $step){$m=$step['maneuver']??[];$loc=$m['location']??null;if(!is_array($loc)||count($loc)<2)continue;$out[]=['distance'=>(float)($step['distance']??0),'name'=>(string)($step['name']??''),'destinations'=>(string)($step['destinations']??''),'maneuver'=>['location'=>[(float)$loc[0],(float)$loc[1]],'type'=>(string)($m['type']??'turn'),'modifier'=>(string)($m['modifier']??''),'exit'=>isset($m['exit'])?(int)$m['exit']:null]];}}
json_response(['ok'=>true,'steps'=>$out,'routing'=>['engine'=>'osrm','attempt_count'=>count($calc['attempts']??[])],'timing'=>['guidance_ms'=>(int)round((microtime(true)-$started)*1000)]]);
