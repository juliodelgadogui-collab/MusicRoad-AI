<?php
declare(strict_types=1);

/** National permanent-road-safety helpers. The app UI never exposes source/cache internals. */
function ep2_brazil_ufs(): array {
    return ['AC','AL','AP','AM','BA','CE','DF','ES','GO','MA','MT','MS','MG','PA','PB','PR','PE','PI','RJ','RN','RS','RO','RR','SC','SP','SE','TO'];
}

function ep2_valid_uf(string $uf): bool { return in_array(strtoupper(trim($uf)), ep2_brazil_ufs(), true); }

function ep2_haversine(float $lat1, float $lon1, float $lat2, float $lon2): float {
    $r = 6371000.0;
    $p1 = deg2rad($lat1); $p2 = deg2rad($lat2);
    $dp = deg2rad($lat2 - $lat1); $dl = deg2rad($lon2 - $lon1);
    $a = sin($dp/2)*sin($dp/2) + cos($p1)*cos($p2)*sin($dl/2)*sin($dl/2);
    return $r * 2 * atan2(sqrt($a), sqrt(max(0.0, 1.0-$a)));
}

function ep2_speed($raw): ?int {
    if ($raw === null || $raw === '') return null;
    if (is_numeric($raw)) { $v=(int)round((float)$raw); return ($v>=10 && $v<=180)?$v:null; }
    if (preg_match('/(\d{2,3})/', (string)$raw, $m)) { $v=(int)$m[1]; return ($v>=10 && $v<=180)?$v:null; }
    return null;
}

function ep2_heading($raw): ?float {
    if ($raw === null || $raw === '') return null;
    if (is_numeric($raw)) { $v=fmod((float)$raw,360.0); if($v<0)$v+=360.0; return $v; }
    $v=strtolower(trim((string)$raw));
    $map=['n'=>0,'north'=>0,'norte'=>0,'ne'=>45,'northeast'=>45,'nordeste'=>45,'e'=>90,'east'=>90,'leste'=>90,'se'=>135,'southeast'=>135,'sudeste'=>135,'s'=>180,'south'=>180,'sul'=>180,'sw'=>225,'southwest'=>225,'sudoeste'=>225,'w'=>270,'west'=>270,'oeste'=>270,'nw'=>315,'northwest'=>315,'noroeste'=>315];
    return array_key_exists($v,$map)?(float)$map[$v]:null;
}

function ep2_type(array $tags): ?string {
    $highway = strtolower((string)($tags['highway'] ?? ''));
    $enf = strtolower((string)($tags['enforcement'] ?? ''));
    $calming = strtolower((string)($tags['traffic_calming'] ?? ''));
    $barrier = strtolower((string)($tags['barrier'] ?? ''));
    $rail = strtolower((string)($tags['railway'] ?? ''));
    $manMade = strtolower((string)($tags['man_made'] ?? ''));
    $surveillance = strtolower((string)($tags['surveillance'] ?? ''));
    $surveillanceZone = strtolower((string)($tags['surveillance:zone'] ?? ''));
    $cameraType = strtolower((string)($tags['camera:type'] ?? ''));

    // Red-light/traffic-signal enforcement is more specific than generic speed enforcement.
    if (str_contains($enf,'redlight') || str_contains($enf,'traffic_signals')) return 'SEMAFORO_RADAR';
    if ($highway === 'speed_camera' || str_contains($enf,'maxspeed')) return 'RADAR';
    if ($highway === 'traffic_signals') return 'SEMAFORO';
    if ($highway === 'speed_bump' || in_array($calming, ['bump','hump','table','cushion','yes'], true)) return 'QUEBRA_MOLAS';
    if ($manMade === 'surveillance' && ($surveillance === 'traffic' || $surveillanceZone === 'traffic')) return 'CAMERA_MONITORAMENTO';
    if ($cameraType === 'traffic') return 'CAMERA_MONITORAMENTO';
    if ($barrier === 'toll_booth' || strtolower((string)($tags['toll'] ?? '')) === 'yes') return 'PEDAGIO';
    if (in_array($rail, ['level_crossing','crossing'], true)) return 'PASSAGEM_NIVEL';
    return null;
}

function ep2_destination(float $lat, float $lon, float $headingDeg, float $distanceM): array {
    $r=6371000.0; $br=deg2rad($headingDeg); $d=$distanceM/$r;
    $p1=deg2rad($lat); $l1=deg2rad($lon);
    $p2=asin(sin($p1)*cos($d)+cos($p1)*sin($d)*cos($br));
    $l2=$l1+atan2(sin($br)*sin($d)*cos($p1),cos($d)-sin($p1)*sin($p2));
    $lon2=fmod(rad2deg($l2)+540.0,360.0)-180.0;
    return [rad2deg($p2),$lon2];
}

function ep2_point_segment_distance(float $plat,float $plon,float $alat,float $alon,float $blat,float $blon): float {
    $lat0=deg2rad(($plat+$alat+$blat)/3.0);
    $mx=111320.0*max(0.2,cos($lat0)); $my=110540.0;
    $ax=($alon-$plon)*$mx; $ay=($alat-$plat)*$my;
    $bx=($blon-$plon)*$mx; $by=($blat-$plat)*$my;
    $vx=$bx-$ax; $vy=$by-$ay; $den=$vx*$vx+$vy*$vy;
    $t=$den>0 ? -($ax*$vx+$ay*$vy)/$den : 0.0; $t=max(0.0,min(1.0,$t));
    $x=$ax+$t*$vx; $y=$ay+$t*$vy;
    return sqrt($x*$x+$y*$y);
}

function ep2_distance_to_line(float $lat,float $lon,array $line): float {
    if(count($line)<2)return INF; $best=INF;
    for($i=1,$n=count($line);$i<$n;$i++){
        $a=$line[$i-1];$b=$line[$i];
        $d=ep2_point_segment_distance($lat,$lon,(float)$a[0],(float)$a[1],(float)$b[0],(float)$b[1]);
        if($d<$best)$best=$d;
    }
    return $best;
}

function ep2_add(array &$items,array &$seen,array $h): void {
    if(!isset($h['lat'],$h['lon'])||!is_numeric($h['lat'])||!is_numeric($h['lon']))return;
    $lat=(float)$h['lat'];$lon=(float)$h['lon'];$type=(string)($h['type']??'RADAR');
    $id=(string)($h['id']??'');if($id==='')$id='geo-'.$type.'-'.round($lat,5).'-'.round($lon,5);
    $geo=$type.'|'.round($lat,5).'|'.round($lon,5);
    if(isset($seen[$id])||isset($seen[$geo]))return;
    $seen[$id]=true;$seen[$geo]=true;
    $items[]=['id'=>$id,'type'=>$type,'lat'=>$lat,'lon'=>$lon,'road'=>trim((string)($h['road']??'')),'speed'=>isset($h['speed'])&&is_numeric($h['speed'])?(int)$h['speed']:null,'heading'=>isset($h['heading'])&&is_numeric($h['heading'])?round((float)$h['heading'],1):null,'source'=>(string)($h['source']??'BASE_LOCAL')];
}

/** Approximate bounding boxes are only a resilience fallback; normal sync uses exact OSM administrative areas. */
function ep2_state_bounds(string $uf): array {
    $map = [
        'AC'=>[-11.20,-74.05,-7.05,-66.55], 'AL'=>[-10.55,-38.25,-8.75,-35.10],
        'AP'=>[-1.30,-54.95,4.50,-49.80], 'AM'=>[-9.90,-73.85,2.30,-56.05],
        'BA'=>[-18.40,-46.75,-8.45,-37.25], 'CE'=>[-7.95,-41.50,-2.70,-37.20],
        'DF'=>[-16.10,-48.30,-15.45,-47.30], 'ES'=>[-21.35,-41.95,-17.75,-39.55],
        'GO'=>[-19.55,-53.30,-12.35,-45.85], 'MA'=>[-10.35,-48.80,-0.95,-41.70],
        'MT'=>[-18.10,-61.70,-7.30,-50.15], 'MS'=>[-24.15,-58.25,-17.10,-50.85],
        'MG'=>[-23.00,-51.15,-14.10,-39.75], 'PA'=>[-9.90,-58.95,2.65,-45.95],
        'PB'=>[-8.35,-38.85,-6.00,-34.75], 'PR'=>[-26.75,-54.70,-22.50,-48.00],
        'PE'=>[-9.55,-41.40,-7.10,-34.75], 'PI'=>[-10.95,-45.95,-2.70,-40.30],
        'RJ'=>[-23.45,-44.95,-20.65,-40.75], 'RN'=>[-7.00,-38.65,-4.80,-34.90],
        'RS'=>[-33.80,-57.70,-27.00,-49.60], 'RO'=>[-13.75,-66.05,-7.90,-59.60],
        'RR'=>[0.75,-64.85,5.35,-58.80], 'SC'=>[-29.40,-53.90,-25.90,-48.30],
        'SP'=>[-25.45,-53.25,-19.65,-44.00], 'SE'=>[-11.60,-38.30,-9.45,-36.35],
        'TO'=>[-13.55,-50.80,-5.05,-45.65],
    ];
    $uf=strtoupper(trim($uf));
    return $map[$uf] ?? [-90.0,-180.0,90.0,180.0];
}

function ep2_is_es(float $lat,float $lon): bool {
    if($lat < -21.35 || $lat > -17.75 || $lon < -41.95 || $lon > -39.55)return false;
    if($lat > -19.0 && $lon < -40.98)return false;
    if($lat > -20.0 && $lat <= -19.0 && $lon < -41.32)return false;
    if($lat > -21.0 && $lat <= -20.0 && $lon < -41.90)return false;
    if($lat <= -21.0){
        if($lon > -40.96)return $lat >= -21.33;
        if($lon >= -41.75){$border=-21.30 - 0.25*($lon+40.96);return $lat >= $border;}
        return $lat >= -20.92;
    }
    return true;
}

/** Best-effort fallback for rows without UF. Exact imports should always provide UF. */
function ep2_guess_uf(float $lat,float $lon): string {
    if(ep2_is_es($lat,$lon))return 'ES';
    $best='';$bestArea=INF;
    foreach(ep2_brazil_ufs() as $uf){
        [$s,$w,$n,$e]=ep2_state_bounds($uf);
        if($lat<$s||$lat>$n||$lon<$w||$lon>$e)continue;
        $area=max(0.001,($n-$s)*($e-$w));
        if($area<$bestArea){$best=$uf;$bestArea=$area;}
    }
    return $best;
}

function ep2_overpass_json(string $query,int $timeout=90): ?array {
    global $config;$url=(string)($config['routing']['overpass_url']??'');if($url==='')return null;$timeout=max(20,min(140,$timeout));$body='data='.urlencode($query);$headers=['User-Agent: '.($config['routing']['user_agent']??'EstradaPlay/1.5'),'Accept: application/json','Content-Type: application/x-www-form-urlencoded'];
    if(!function_exists('curl_init')){$ctx=stream_context_create(['http'=>['method'=>'POST','header'=>implode("\r\n",$headers),'content'=>$body,'timeout'=>$timeout,'ignore_errors'=>true]]);$raw=@file_get_contents($url,false,$ctx);$data=$raw?json_decode($raw,true):null;return is_array($data)?$data:null;}
    $ch=curl_init($url);curl_setopt_array($ch,[CURLOPT_RETURNTRANSFER=>true,CURLOPT_FOLLOWLOCATION=>true,CURLOPT_CONNECTTIMEOUT=>12,CURLOPT_TIMEOUT=>$timeout,CURLOPT_HTTPHEADER=>$headers,CURLOPT_POST=>true,CURLOPT_POSTFIELDS=>$body,CURLOPT_ENCODING=>'']);$raw=curl_exec($ch);$status=(int)curl_getinfo($ch,CURLINFO_RESPONSE_CODE);curl_close($ch);if(!$raw||$status>=400)return null;$data=json_decode((string)$raw,true);return is_array($data)?$data:null;
}

function ep2_safety_query_body(string $area): string {
    return 'node["highway"="speed_camera"]'.$area.';'
        .'node["enforcement"~"maxspeed|traffic_signals|redlight"]'.$area.';'
        .'node["highway"="traffic_signals"]'.$area.';'
        .'node["highway"="speed_bump"]'.$area.';'
        .'node["traffic_calming"~"^(bump|hump|table|cushion|yes)$"]'.$area.';'
        .'node["man_made"="surveillance"]["surveillance"="traffic"]'.$area.';'
        .'node["man_made"="surveillance"]["surveillance:zone"="traffic"]'.$area.';'
        .'node["camera:type"="traffic"]'.$area.';'
        .'node["barrier"="toll_booth"]'.$area.';'
        .'node["railway"~"^(level_crossing|crossing)$"]'.$area.';';
}

function ep2_osm_query_for_area(string $selector): string {return '[out:json][timeout:90];'.$selector.'('.ep2_safety_query_body('(area.eparea)').');out body qt;';}
function ep2_osm_query_for_bbox(float $south,float $west,float $north,float $east,int $timeout=55): string {$bbox='('.$south.','.$west.','.$north.','.$east.')';return '[out:json][timeout:'.max(20,min(90,$timeout)).'];('.ep2_safety_query_body($bbox).');out body qt;';}
function ep2_osm_query_for_line(array $line,int $widthM): string {$parts=[];foreach($line as $p)$parts[]=rtrim(rtrim(number_format((float)$p[0],6,'.',''),'0'),'.').','.rtrim(rtrim(number_format((float)$p[1],6,'.',''),'0'),'.');$around='(around:'.max(8000,min(30000,$widthM)).','.implode(',',$parts).')';return '[out:json][timeout:55];('.ep2_safety_query_body($around).');out body qt;';}

function ep2_osm_to_hazard(array $el): ?array {
    $tags=is_array($el['tags']??null)?$el['tags']:[];$type=ep2_type($tags);if($type===null)return null;
    $lat=$el['lat']??null;$lon=$el['lon']??null;if(!is_numeric($lat)||!is_numeric($lon))return null;
    return ['id'=>'osm-'.($el['type']??'node').'-'.($el['id']??md5((string)$lat.':'.(string)$lon)),'type'=>$type,'lat'=>(float)$lat,'lon'=>(float)$lon,'road'=>$tags['road_ref']??($tags['ref']??($tags['name']??'')),'speed'=>ep2_speed($tags['maxspeed']??null),'heading'=>ep2_heading($tags['direction']??($tags['camera:direction']??null)),'source'=>'OPENSTREETMAP'];
}
