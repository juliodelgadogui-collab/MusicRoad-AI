<?php
declare(strict_types=1);
require_once __DIR__ . '/context_v7_core.php';
require_once __DIR__ . '/context_v7_live.php';
require_once __DIR__ . '/context_v7_weather.php';

function ep7_context_stats(): array
{
    ep7_ensure_schema();$out=['active_traffic_samples'=>0,'weather_cache_entries'=>0,'active_live_events'=>0,'live_votes'=>0];$now=date('Y-m-d H:i:s');
    foreach([['active_traffic_samples','road_traffic_samples','expires_at'],['weather_cache_entries','road_weather_cache','expires_at'],['active_live_events','road_live_events','expires_at']] as $x){try{$s=db()->prepare("SELECT COUNT(*) FROM {$x[1]} WHERE {$x[2]}>?");$s->execute([$now]);$out[$x[0]]=(int)$s->fetchColumn();}catch(Throwable $e){}}
    try{$out['live_votes']=(int)db()->query('SELECT COUNT(*) FROM road_live_votes')->fetchColumn();}catch(Throwable $e){}
    return $out;
}

function ep7_build_context(array $body,string $deviceToken=''): array
{
    ep7_ensure_schema();ep7_housekeeping(false);$lat=(float)($body['lat']??0);$lon=(float)($body['lon']??0);if(!ep7_valid_coord($lat,$lon))throw new InvalidArgumentException('Coordenada inválida.');$route=ep7_route_points($body['route_points']??[],$lat,$lon);$speed=(float)($body['speed_kmh']??80);$include=is_array($body['include']??null)?array_map('strtolower',$body['include']):['hazards','live','traffic','weather','fuel'];$want=static fn(string $x):bool=>in_array($x,$include,true);
    $trafficSample=['stored'=>false,'reason'=>'not_requested'];if(ep7_parse_bool($body['traffic_opt_in']??false,false)&&$deviceToken!=='')$trafficSample=ep7_store_traffic_sample($body,$deviceToken);
    $result=['version'=>ESTRADAPLAY_CONTEXT_VERSION,'generated_at'=>date(DATE_ATOM),'route'=>['points'=>count($route),'distance_m'=>(int)round(ep7_route_distance($route))],'privacy'=>['traffic_opt_in'=>ep7_parse_bool($body['traffic_opt_in']??false,false),'device_id_stored'=>false,'traffic_retention_h'=>2],'traffic_sample'=>$trafficSample];
    if($want('hazards'))$result['hazards']=ep7_hazards($route,(float)max(800,min(3000,(int)($body['hazard_corridor_m']??1800))),240);
    if($want('live'))$result['live_events']=ep7_live_events($route,(float)max(1500,min(12000,(int)($body['live_corridor_m']??6000))),100);
    if($want('traffic'))$result['traffic']=ep7_traffic_context($route,(float)max(1500,min(8000,(int)($body['traffic_corridor_m']??3500))),50);
    if($want('weather'))$result['weather']=ep7_route_weather($route,$speed,5);
    if($want('fuel'))$result['fuel']=ep7_fuel_context($route,(string)($body['fuel']??''),(float)($body['consumption_km_l']??10),(float)($body['liters_to_buy']??30),12);
    $priorities=[];foreach(($result['weather']??[]) as $w)if(($w['severity']??'OK')!=='OK')$priorities[]=['kind'=>'WEATHER','severity'=>$w['severity'],'ahead_m'=>$w['ahead_m']??0,'message'=>$w['alert']??$w['condition']];foreach(($result['live_events']??[]) as $e)if(($e['confidence']['score']??0)>=0.58)$priorities[]=['kind'=>'LIVE','severity'=>($e['confidence']['score']??0)>=0.82?'ALTA':'MÉDIA','ahead_m'=>$e['ahead_m']??0,'message'=>strtoupper((string)$e['type'])];foreach(($result['traffic']??[]) as $t)if(in_array($t['level']??'',['PARADO','MUITO_LENTO','LENTO'],true))$priorities[]=['kind'=>'TRAFFIC','severity'=>in_array($t['level'],['PARADO','MUITO_LENTO'],true)?'ALTA':'MÉDIA','ahead_m'=>$t['ahead_m']??0,'message'=>$t['level']];usort($priorities,fn($a,$b)=>($a['ahead_m']<=>$b['ahead_m']));$result['priorities']=array_slice($priorities,0,12);
    return $result;
}
