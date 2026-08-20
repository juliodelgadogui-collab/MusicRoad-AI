<?php
require __DIR__ . '/bootstrap.php';
require_login();

$kind = strtolower(trim((string)($_GET['kind'] ?? 'radars')));
$municipioId = preg_replace('/\D+/', '', (string)($_GET['municipio_id'] ?? ''));
$city = trim((string)($_GET['city'] ?? ''));
$uf = strtoupper(trim((string)($_GET['uf'] ?? '')));
if ($municipioId === '' || $city === '' || !preg_match('/^[A-Z]{2}$/',$uf)) json_response(['ok'=>false,'error'=>'Selecione estado e município.'],422);
if (!in_array($kind,['radars','map'],true)) json_response(['ok'=>false,'error'=>'Tipo de pacote inválido.'],422);
$city=mb_substr($city,0,120);
$ufPrefixes=['RO'=>'11','AC'=>'12','AM'=>'13','RR'=>'14','PA'=>'15','AP'=>'16','TO'=>'17','MA'=>'21','PI'=>'22','CE'=>'23','RN'=>'24','PB'=>'25','PE'=>'26','AL'=>'27','SE'=>'28','BA'=>'29','MG'=>'31','ES'=>'32','RJ'=>'33','SP'=>'35','PR'=>'41','SC'=>'42','RS'=>'43','MS'=>'50','MT'=>'51','GO'=>'52','DF'=>'53'];
if(strlen($municipioId)!==7||($ufPrefixes[$uf]??'')!==substr($municipioId,0,2))json_response(['ok'=>false,'error'=>'Município e UF não correspondem.'],422);
require_session_rate_limit('offline-region-'.$kind,$kind==='map'?12:30,3600);
session_write_close();

$cacheDir = dirname(__DIR__) . '/storage/offline-regions';
if(!is_dir($cacheDir))@mkdir($cacheDir,0775,true);
$slug = preg_replace('/[^A-Za-z0-9_-]+/','-',iconv('UTF-8','ASCII//TRANSLIT//IGNORE',$city) ?: $city);
$cacheFile = $cacheDir . '/' . $municipioId . '-' . $kind . '.json';
$ttl = $kind==='radars' ? 86400 : 2592000;
if(is_file($cacheFile) && filemtime($cacheFile)>time()-$ttl){
    $raw=(string)@file_get_contents($cacheFile);if($raw!==''){header('Content-Type: application/json; charset=utf-8');header('X-MusicRoad-Offline-Pack: cache');echo $raw;exit;}
}

function mr_overpass_call(string $query, int $timeout=55): ?array {
    $endpoints=['https://overpass-api.de/api/interpreter','https://overpass.kumi.systems/api/interpreter'];
    foreach($endpoints as $ep){
        $r=http_json($ep, 'data='.rawurlencode($query), ['Content-Type: application/x-www-form-urlencoded'], $timeout);
        if(is_array($r)&&isset($r['elements']))return $r;
    }
    return null;
}
function mr_tags_type(array $tags): ?string {
    $h=(string)($tags['highway']??'');$tc=(string)($tags['traffic_calming']??'');$e=(string)($tags['enforcement']??'');
    if($h==='traffic_signals'||($tags['crossing']??'')==='traffic_signals')return 'SEMAFORO';
    if(in_array($tc,['bump','hump','table','cushion','rumble_strip'],true)||$h==='speed_bump')return 'QUEBRA_MOLA';
    if($h==='speed_camera'||$e==='maxspeed'||($tags['device']??'')==='red_signal_camera')return 'RADAR_FIXO';
    if(($tags['man_made']??'')==='surveillance'||isset($tags['surveillance']))return 'VIDEO_MONITORAMENTO';
    return null;
}

$boundaryUrl='https://servicodados.ibge.gov.br/api/v4/malhas/municipios/'.$municipioId.'?formato=application/vnd.geo+json&qualidade=minima';
$boundary=http_json($boundaryUrl,null,[],25);
function mr_geojson_bbox($node, array &$box): void {
    if(!is_array($node))return;
    if(count($node)>=2 && is_numeric($node[0]) && is_numeric($node[1])){
        $lon=(float)$node[0];$lat=(float)$node[1];$box[0]=min($box[0],$lat);$box[1]=min($box[1],$lon);$box[2]=max($box[2],$lat);$box[3]=max($box[3],$lon);return;
    }
    foreach($node as $v)if(is_array($v))mr_geojson_bbox($v,$box);
}
$bbox=[INF,INF,-INF,-INF];if(is_array($boundary))mr_geojson_bbox($boundary,$bbox);
$bboxValid=is_finite($bbox[0])&&is_finite($bbox[1])&&is_finite($bbox[2])&&is_finite($bbox[3])&&$bbox[0]<$bbox[2]&&$bbox[1]<$bbox[3];
$bboxText=$bboxValid?implode(',',array_map(fn($v)=>number_format((float)$v,6,'.',''),$bbox)):'';

$areaSelector='(rel["boundary"="administrative"]["IBGE:GEOCODIGO"="'.$municipioId.'"];rel["boundary"="administrative"]["ref:IBGE"="'.$municipioId.'"];);map_to_area->.a;';
$radarQuery='[out:json][timeout:50];'.$areaSelector.'(node(area.a)[highway=traffic_signals];node(area.a)[crossing=traffic_signals];node(area.a)[highway=speed_camera];node(area.a)[traffic_calming];node(area.a)[man_made=surveillance];way(area.a)[traffic_calming];);out center tags;';
$radarData=mr_overpass_call($radarQuery,55);
if((!$radarData||empty($radarData['elements']))&&$bboxValid){$radarQuery='[out:json][timeout:50];(node('.$bboxText.')[highway=traffic_signals];node('.$bboxText.')[crossing=traffic_signals];node('.$bboxText.')[highway=speed_camera];node('.$bboxText.')[traffic_calming];node('.$bboxText.')[man_made=surveillance];way('.$bboxText.')[traffic_calming];);out center tags;';$radarData=mr_overpass_call($radarQuery,55);}
$points=[];
if($radarData){foreach(($radarData['elements']??[]) as $e){$tags=is_array($e['tags']??null)?$e['tags']:[];$type=mr_tags_type($tags);if(!$type)continue;$lat=$e['lat']??($e['center']['lat']??null);$lon=$e['lon']??($e['center']['lon']??null);if(!is_numeric($lat)||!is_numeric($lon))continue;$points[]=['id'=>'osm-'.($e['type']??'n').'-'.($e['id']??''),'latitude'=>(float)$lat,'longitude'=>(float)$lon,'tipo'=>$type,'velocidade'=>isset($tags['maxspeed'])?(int)preg_replace('/\D+.*/','',(string)$tags['maxspeed']):null,'fonte'=>'OpenStreetMap','cidade'=>$city,'uf'=>$uf];}}

// Complementa com a base local do servidor.
try{
    $stmt=db()->prepare("SELECT id,external_id,latitude,longitude,velocidade,tipo,fonte,cidade,uf FROM radars WHERE ativo=1 AND (expires_at IS NULL OR expires_at>=CURRENT_TIMESTAMP) AND UPPER(COALESCE(uf,''))=? AND LOWER(COALESCE(cidade,''))=LOWER(?)");
    $stmt->execute([$uf,$city]);
    foreach($stmt->fetchAll() as $r){$points[]=['id'=>'db-'.($r['id']??''),'external_id'=>$r['external_id']??null,'latitude'=>(float)$r['latitude'],'longitude'=>(float)$r['longitude'],'tipo'=>$r['tipo']??'RADAR_FIXO','velocidade'=>$r['velocidade']!==null?(int)$r['velocidade']:null,'fonte'=>$r['fonte']??'Servidor','cidade'=>$city,'uf'=>$uf];}
}catch(Throwable $e){}
$uniq=[];$dedup=[];foreach($points as $p){$k=round($p['latitude'],5).','.round($p['longitude'],5).','.strtoupper((string)$p['tipo']);if(isset($uniq[$k]))continue;$uniq[$k]=1;$dedup[]=$p;}$points=$dedup;

$pack=['ok'=>true,'version'=>1,'kind'=>$kind,'municipality'=>['id'=>$municipioId,'name'=>$city,'uf'=>$uf],'generated_at'=>gmdate('c'),'radars'=>$points,'map'=>null,'attribution'=>'Dados de fiscalização: OpenStreetMap contributors e base local MusicRoad'];

if($kind==='map'){
    // Mapa vetorial leve: vias principais e coletoras. Não baixa tiles raster do OSM.
    $roadsQuery='[out:json][timeout:55];'.$areaSelector.'way(area.a)[highway~"^(motorway|trunk|primary|secondary|tertiary)$"];out geom tags;';
    $roadsData=mr_overpass_call($roadsQuery,60);if((!$roadsData||empty($roadsData['elements']))&&$bboxValid){$roadsQuery='[out:json][timeout:55];way('.$bboxText.')[highway~"^(motorway|trunk|primary|secondary|tertiary)$"];out geom tags;';$roadsData=mr_overpass_call($roadsQuery,60);}$roads=[];
    if($roadsData){foreach(($roadsData['elements']??[]) as $e){if(($e['type']??'')!=='way'||empty($e['geometry']))continue;$coords=[];foreach($e['geometry'] as $g){if(isset($g['lat'],$g['lon']))$coords[]=[(float)$g['lon'],(float)$g['lat']];}if(count($coords)>1)$roads[]=['type'=>'Feature','properties'=>['id'=>$e['id']??null,'name'=>$e['tags']['name']??null,'highway'=>$e['tags']['highway']??null],'geometry'=>['type'=>'LineString','coordinates'=>$coords]];}}
    $pack['map']=['boundary'=>$boundary,'roads'=>['type'=>'FeatureCollection','features'=>$roads],'mode'=>'vector-light','note'=>'Mapa offline leve com limite municipal e vias principais; tiles do OpenStreetMap não são pré-baixados.'];
    $pack['attribution'].='; limite municipal: IBGE';
}

$raw=json_encode($pack,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);
if(is_string($raw))write_runtime_file($cacheFile,$raw);
header('Content-Type: application/json; charset=utf-8');
header('Content-Disposition: attachment; filename="MusicRoad-'.$slug.'-'.$uf.'-'.$kind.'.json"');
header('X-MusicRoad-Offline-Pack: fresh');
echo $raw;
