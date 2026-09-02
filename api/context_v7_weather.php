<?php
declare(strict_types=1);
require_once __DIR__ . '/context_v7_core.php';

function ep7_weather_code_label(int $code): string
{
    if($code===0)return 'CÉU LIMPO';if(in_array($code,[1,2,3],true))return 'NUBLADO';if(in_array($code,[45,48],true))return 'NEBLINA';if(in_array($code,[51,53,55,56,57],true))return 'GAROA';if(in_array($code,[61,63,65,66,67,80,81,82],true))return 'CHUVA';if(in_array($code,[71,73,75,77,85,86],true))return 'NEVE';if(in_array($code,[95,96,99],true))return 'TEMPESTADE';return 'VARIÁVEL';
}

function ep7_weather_point(float $lat,float $lon,int $etaSeconds=0): ?array
{
    ep7_ensure_schema();$target=time()+max(0,$etaSeconds);$key=sprintf('%.2f:%.2f:%d',$lat,$lon,(int)floor($target/3600));$now=date('Y-m-d H:i:s');
    try{$s=db()->prepare('SELECT payload FROM road_weather_cache WHERE cache_key=? AND expires_at>? LIMIT 1');$s->execute([$key,$now]);$raw=$s->fetchColumn();if($raw!==false){$j=json_decode((string)$raw,true);if(is_array($j)){$j['cached']=true;return $j;}}}catch(Throwable $e){}
    $params=['latitude'=>round($lat,4),'longitude'=>round($lon,4),'current'=>'temperature_2m,precipitation,weather_code,wind_speed_10m','hourly'=>'precipitation_probability,precipitation,weather_code,visibility,wind_speed_10m,temperature_2m','forecast_days'=>2,'timezone'=>'America/Sao_Paulo'];
    $data=http_json('https://api.open-meteo.com/v1/forecast?'.http_build_query($params,'','&',PHP_QUERY_RFC3986));if(!is_array($data))return null;
    $h=is_array($data['hourly']??null)?$data['hourly']:[];$times=is_array($h['time']??null)?$h['time']:[];$idx=0;$best=PHP_INT_MAX;foreach($times as $i=>$t){$ts=strtotime((string)$t);if($ts===false)continue;$d=abs($ts-$target);if($d<$best){$best=$d;$idx=(int)$i;}}
    $val=static fn(string $name,$default=null)=>isset($h[$name][$idx])?$h[$name][$idx]:$default;$code=(int)$val('weather_code',(int)($data['current']['weather_code']??-1));$precip=(float)$val('precipitation',(float)($data['current']['precipitation']??0));$prob=(int)$val('precipitation_probability',0);$vis=(int)$val('visibility',10000);$wind=(float)$val('wind_speed_10m',(float)($data['current']['wind_speed_10m']??0));$temp=(float)$val('temperature_2m',(float)($data['current']['temperature_2m']??0));$severity='OK';$alert='';if(in_array($code,[95,96,99],true)||$precip>=5){$severity='ALTA';$alert='TEMPESTADE/CHUVA FORTE';}elseif($vis>0&&$vis<1500){$severity='ALTA';$alert='VISIBILIDADE BAIXA';}elseif($prob>=65||$precip>=2){$severity='MÉDIA';$alert='CHUVA PROVÁVEL';}elseif($wind>=50){$severity='MÉDIA';$alert='VENTO FORTE';}
    $out=['lat'=>$lat,'lon'=>$lon,'eta_s'=>$etaSeconds,'forecast_at'=>isset($times[$idx])?(string)$times[$idx]:date(DATE_ATOM,$target),'temperature_c'=>round($temp,1),'precipitation_mm'=>round($precip,1),'precipitation_probability'=>$prob,'visibility_m'=>$vis,'wind_kmh'=>round($wind,1),'weather_code'=>$code,'condition'=>ep7_weather_code_label($code),'severity'=>$severity,'alert'=>$alert,'source'=>'open-meteo','cached'=>false];
    try{$payload=json_encode($out,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);$exp=date('Y-m-d H:i:s',time()+900);if(ep7_is_mysql())$sql='INSERT INTO road_weather_cache (cache_key,latitude,longitude,payload,fetched_at,expires_at) VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE latitude=VALUES(latitude),longitude=VALUES(longitude),payload=VALUES(payload),fetched_at=VALUES(fetched_at),expires_at=VALUES(expires_at)';else$sql='INSERT INTO road_weather_cache (cache_key,latitude,longitude,payload,fetched_at,expires_at) VALUES (?,?,?,?,?,?) ON CONFLICT(cache_key) DO UPDATE SET latitude=excluded.latitude,longitude=excluded.longitude,payload=excluded.payload,fetched_at=excluded.fetched_at,expires_at=excluded.expires_at';$s=db()->prepare($sql);$s->execute([$key,$lat,$lon,$payload,$now,$exp]);}catch(Throwable $e){}
    return $out;
}

function ep7_route_weather(array $route,float $speedKmh=80.0,int $maxPoints=5): array
{
    $n=count($route);if($n===0)return [];$maxPoints=max(1,min(6,$maxPoints));$distance=ep7_route_distance($route);$speed=max(35.0,min(120.0,$speedKmh>5?$speedKmh:80.0));$out=[];$used=[];
    for($i=0;$i<$maxPoints;$i++){$idx=$maxPoints===1?0:(int)round(($i/($maxPoints-1))*($n-1));if(isset($used[$idx]))continue;$used[$idx]=1;$ahead=0.0;for($j=1;$j<=$idx;$j++)$ahead+=haversine_m((float)$route[$j-1]['lat'],(float)$route[$j-1]['lon'],(float)$route[$j]['lat'],(float)$route[$j]['lon']);$eta=(int)round(($ahead/1000.0)/$speed*3600.0);$w=ep7_weather_point((float)$route[$idx]['lat'],(float)$route[$idx]['lon'],$eta);if($w){$w['ahead_m']=(int)round($ahead);$out[]=$w;}}
    return $out;
}

function ep7_fuel_context(array $route,string $fuel='',float $consumptionKmL=10.0,float $liters=30.0,int $limit=12): array
{
    $cols=schema_columns('fuel_price_reports');if(!$cols)return [];[$minLat,$maxLat,$minLon,$maxLon]=ep7_bbox($route,16000.0);$cut=date('Y-m-d H:i:s',time()-36*3600);$fuel=trim($fuel);$consumptionKmL=max(3.0,min(30.0,$consumptionKmL));$liters=max(5.0,min(120.0,$liters));
    try{$sql='SELECT id,station,fuel,price,latitude,longitude,reported_at,created_at FROM fuel_price_reports WHERE created_at>=? AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?';$params=[$cut,$minLat,$maxLat,$minLon,$maxLon];if($fuel!==''){$sql.=' AND LOWER(fuel)=LOWER(?)';$params[]=$fuel;}$sql.=' ORDER BY created_at DESC LIMIT 500';$s=db()->prepare($sql);$s->execute($params);$out=[];$seen=[];foreach($s->fetchAll()?:[] as $r){$m=ep7_route_metrics((float)$r['latitude'],(float)$r['longitude'],$route);if($m['lateral_m']>16000)continue;$station=(string)$r['station'];$key=strtolower($station.'|'.(string)$r['fuel'].'|'.round((float)$r['latitude'],3).'|'.round((float)$r['longitude'],3));if(isset($seen[$key]))continue;$seen[$key]=1;$age=max(0,(time()-(strtotime((string)$r['created_at'])?:time()))/3600);$detourKm=2.0*$m['lateral_m']/1000.0;$price=(float)$r['price'];$detourCost=($detourKm/$consumptionKmL)*$price;$tripCost=$liters*$price+$detourCost;$confidence=max(0.15,min(1.0,1.0-$age/48.0));$out[]=['id'=>(int)$r['id'],'station'=>$station,'fuel'=>(string)$r['fuel'],'price'=>round($price,3),'lat'=>(float)$r['latitude'],'lon'=>(float)$r['longitude'],'ahead_m'=>(int)round($m['ahead_m']),'detour_roundtrip_km'=>round($detourKm,1),'detour_cost_brl'=>round($detourCost,2),'estimated_total_brl'=>round($tripCost,2),'age_h'=>round($age,1),'confidence'=>round($confidence,2),'reported_at'=>(int)($r['reported_at']??0)];}usort($out,fn($a,$b)=>($a['estimated_total_brl']<=>$b['estimated_total_brl'])?:($b['confidence']<=>$a['confidence']));return array_slice($out,0,max(1,min(30,$limit)));}catch(Throwable $e){error_log('EP7 fuel: '.$e->getMessage());return [];}
}
