<?php
require __DIR__ . '/bootstrap.php';
require_login();

$cityId=preg_replace('/\D+/','',(string)($_GET['city_id']??''));
$uf=strtoupper(trim((string)($_GET['uf']??'')));
$city=mb_substr(trim((string)($_GET['city']??'')),0,120);
if(strlen($cityId)!==7||!preg_match('/^[A-Z]{2}$/',$uf))json_response(['ok'=>false,'error'=>'Município inválido.'],422);
$ufPrefixes=['RO'=>'11','AC'=>'12','AM'=>'13','RR'=>'14','PA'=>'15','AP'=>'16','TO'=>'17','MA'=>'21','PI'=>'22','CE'=>'23','RN'=>'24','PB'=>'25','PE'=>'26','AL'=>'27','SE'=>'28','BA'=>'29','MG'=>'31','ES'=>'32','RJ'=>'33','SP'=>'35','PR'=>'41','SC'=>'42','RS'=>'43','MS'=>'50','MT'=>'51','GO'=>'52','DF'=>'53'];
if(($ufPrefixes[$uf]??'')!==substr($cityId,0,2))json_response(['ok'=>false,'error'=>'Município e UF não correspondem.'],422);
require_session_rate_limit('offline-city-roads',20,3600);
session_write_close();

$cacheDir=dirname(__DIR__).'/storage/offline-cities';if(!is_dir($cacheDir))@mkdir($cacheDir,0775,true);
$cacheFile=$cacheDir.'/'.$uf.'-'.$cityId.'.json';$cacheTtl=604800;
if(is_file($cacheFile)&&filemtime($cacheFile)>time()-$cacheTtl){$raw=(string)@file_get_contents($cacheFile);if($raw!==''){header('Cache-Control: no-store, max-age=0');header('Content-Type: application/json; charset=utf-8');header('Content-Disposition: attachment; filename="MusicRoad-'.$uf.'-'.$cityId.'-city.json"');header('X-MusicRoad-Storage: server-cache+device');echo $raw;exit;}}
$buildLock=@fopen($cacheFile.'.lock','c');
if(is_resource($buildLock)&&!@flock($buildLock,LOCK_EX|LOCK_NB)){@fclose($buildLock);json_response(['ok'=>false,'error'=>'Esse mapa municipal já está sendo preparado. Aguarde um momento e tente novamente.'],409);}
if(is_file($cacheFile)&&filemtime($cacheFile)>time()-$cacheTtl){$raw=(string)@file_get_contents($cacheFile);if($raw!==''){if(is_resource($buildLock)){@flock($buildLock,LOCK_UN);@fclose($buildLock);}header('Cache-Control: no-store, max-age=0');header('Content-Type: application/json; charset=utf-8');header('Content-Disposition: attachment; filename="MusicRoad-'.$uf.'-'.$cityId.'-city.json"');header('X-MusicRoad-Storage: server-cache+device');echo $raw;exit;}}

function mr_polyline_encode(array $points): string {
    $out=''; $lastLat=0; $lastLon=0;
    foreach($points as $p){
        if(!is_array($p)||count($p)<2)continue;
        $lat=(int)round(((float)$p[1])*100000); $lon=(int)round(((float)$p[0])*100000);
        foreach([$lat-$lastLat,$lon-$lastLon] as $delta){
            $v=$delta<0?~($delta<<1):($delta<<1);
            while($v>=0x20){$out.=chr((0x20|($v&0x1f))+63);$v>>=5;}
            $out.=chr($v+63);
        }
        $lastLat=$lat;$lastLon=$lon;
    }
    return $out;
}
function mr_dist_approx(float $lat1,float $lon1,float $lat2,float $lon2): float {
    $dy=($lat2-$lat1)*110540.0;
    $dx=($lon2-$lon1)*111320.0*max(.2,cos(deg2rad(($lat1+$lat2)/2)));
    return sqrt($dx*$dx+$dy*$dy);
}
function mr_compact_geom(array $geom,float $minStep): array {
    $out=[];$lastLat=null;$lastLon=null;$n=count($geom);
    foreach($geom as $i=>$g){
        if(!isset($g['lat'],$g['lon']))continue;
        $lat=(float)$g['lat'];$lon=(float)$g['lon'];
        if($lastLat===null||$i===$n-1||mr_dist_approx($lastLat,$lastLon,$lat,$lon)>=$minStep){
            $out[]=[round($lon,5),round($lat,5)];$lastLat=$lat;$lastLon=$lon;
        }
    }
    if(count($out)<2&&count($geom)>=2){
        $a=$geom[0];$b=$geom[count($geom)-1];
        if(isset($a['lat'],$a['lon'],$b['lat'],$b['lon']))$out=[[round((float)$a['lon'],5),round((float)$a['lat'],5)],[round((float)$b['lon'],5),round((float)$b['lat'],5)]];
    }
    return $out;
}
function mr_class_code(string $h): int {
    static $m=['motorway'=>1,'motorway_link'=>1,'trunk'=>2,'trunk_link'=>2,'primary'=>3,'primary_link'=>3,'secondary'=>4,'secondary_link'=>4,'tertiary'=>5,'tertiary_link'=>5,'unclassified'=>6,'residential'=>7,'living_street'=>7,'service'=>8,'road'=>8];
    return $m[$h]??8;
}
function mr_step_for_class(int $c): float {return $c<=2?5.0:($c<=4?7.0:($c<=6?10.0:13.0));}

$highways='motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|unclassified|residential|living_street|service|road';
$q='[out:json][timeout:95];(rel["boundary"="administrative"]["IBGE:GEOCODIGO"="'.$cityId.'"];rel["boundary"="administrative"]["ref:IBGE"="'.$cityId.'"];);map_to_area->.a;way(area.a)[highway~"^('.$highways.')$"];out geom tags;';
$eps=['https://overpass-api.de/api/interpreter','https://overpass.kumi.systems/api/interpreter','https://overpass.private.coffee/api/interpreter'];
$data=null;$lastError='';
foreach($eps as $ep){
    try{$x=http_json($ep,'data='.rawurlencode($q),['Content-Type: application/x-www-form-urlencoded'],110,67108864);if(is_array($x)&&isset($x['elements'])){$data=$x;break;}}catch(Throwable $e){$lastError=$e->getMessage();}
}
if(!is_array($data)){if(is_resource($buildLock)){@flock($buildLock,LOCK_UN);@fclose($buildLock);}error_log('[MusicRoad offline_city_roads] provedores indisponíveis; '.substr(hash('sha256',$lastError),0,12));json_response(['ok'=>false,'error'=>'Não foi possível baixar as vias desse município agora. Tente novamente mais tarde.'],502);}

$roads=[];$minLon=180;$minLat=90;$maxLon=-180;$maxLat=-90;$pointCount=0;
foreach(($data['elements']??[]) as $e){
    if(($e['type']??'')!=='way'||empty($e['geometry'])||!is_array($e['geometry']))continue;
    $h=strtolower((string)($e['tags']['highway']??'road'));$class=mr_class_code($h);
    $pts=mr_compact_geom($e['geometry'],mr_step_for_class($class));
    if(count($pts)<2)continue;
    foreach($pts as $p){$minLon=min($minLon,$p[0]);$maxLon=max($maxLon,$p[0]);$minLat=min($minLat,$p[1]);$maxLat=max($maxLat,$p[1]);$pointCount++;}
    $name=trim((string)($e['tags']['name']??''));$ref=trim((string)($e['tags']['ref']??''));
    $roads[]=[$class,$name,$ref,mr_polyline_encode($pts)];
}
if($minLon>=$maxLon||$minLat>=$maxLat)$bbox=null;else $bbox=[round($minLon,5),round($minLat,5),round($maxLon,5),round($maxLat,5)];
if(!$roads||$bbox===null){if(is_resource($buildLock)){@flock($buildLock,LOCK_UN);@fclose($buildLock);}json_response(['ok'=>false,'error'=>'A fonte cartográfica não retornou vias válidas para esse município.'],502);}

$pack=[
    'ok'=>true,'version'=>2,'city'=>['id'=>$cityId,'name'=>$city,'uf'=>$uf],
    'bbox'=>$bbox,'roads'=>$roads,'road_count'=>count($roads),'point_count'=>$pointCount,
    'generated_at'=>gmdate('c'),'storage'=>'device','attribution'=>'OpenStreetMap contributors'
];
$raw=json_encode($pack,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);if(!is_string($raw)||strlen($raw)>67108864){if(is_resource($buildLock)){@flock($buildLock,LOCK_UN);@fclose($buildLock);}json_response(['ok'=>false,'error'=>'O mapa municipal ficou maior que o limite seguro do dispositivo.'],507);}
if(!write_runtime_file($cacheFile,$raw)){if(is_resource($buildLock)){@flock($buildLock,LOCK_UN);@fclose($buildLock);}json_response(['ok'=>false,'error'=>'Não foi possível armazenar o mapa municipal no cache técnico.'],507);}if(is_resource($buildLock)){@flock($buildLock,LOCK_UN);@fclose($buildLock);}
header('Cache-Control: no-store, max-age=0');header('Content-Type: application/json; charset=utf-8');header('Content-Disposition: attachment; filename="MusicRoad-'.$uf.'-'.$cityId.'-city.json"');header('X-MusicRoad-Storage: server-cache+device');if(extension_loaded('zlib')&&!headers_sent()&&!ob_get_level())@ob_start('ob_gzhandler');echo $raw;
