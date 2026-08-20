<?php
require __DIR__ . '/bootstrap.php';
require_login();

$kind=strtolower(trim((string)($_GET['kind']??'radars')));
$stateId=preg_replace('/\D+/','',(string)($_GET['state_id']??''));
$uf=strtoupper(trim((string)($_GET['uf']??'')));
$state=trim((string)($_GET['state']??$uf));
if(!in_array($kind,['radars','map'],true))json_response(['ok'=>false,'error'=>'Tipo de pacote inválido.'],422);
if($stateId===''||!preg_match('/^[A-Z]{2}$/',$uf))json_response(['ok'=>false,'error'=>'Selecione um estado.'],422);
$ufStateIds=['RO'=>'11','AC'=>'12','AM'=>'13','RR'=>'14','PA'=>'15','AP'=>'16','TO'=>'17','MA'=>'21','PI'=>'22','CE'=>'23','RN'=>'24','PB'=>'25','PE'=>'26','AL'=>'27','SE'=>'28','BA'=>'29','MG'=>'31','ES'=>'32','RJ'=>'33','SP'=>'35','PR'=>'41','SC'=>'42','RS'=>'43','MS'=>'50','MT'=>'51','GO'=>'52','DF'=>'53'];
if(($ufStateIds[$uf]??'')!==$stateId)json_response(['ok'=>false,'error'=>'Estado e UF não correspondem.'],422);
$state=mb_substr($state,0,80);
require_session_rate_limit('offline-state-'.$kind,$kind==='map'?8:30,3600);
session_write_close();

$cacheDir=dirname(__DIR__).'/storage/offline-states';if(!is_dir($cacheDir))@mkdir($cacheDir,0775,true);
$cacheFile=$cacheDir.'/'.$uf.'-'.$kind.'-v3.json';$metaFile=$cacheFile.'.meta';$ttl=$kind==='map'?604800:86400;
// O cache técnico reduz consultas repetidas aos provedores; o pacote de uso permanece no dispositivo do motorista.
$dbSignature='unavailable';
if($kind==='radars')try{$sig=db()->query("SELECT COUNT(*) total,COALESCE(MAX(data_importacao),'') last_update,COALESCE(SUM(CRC32(CONCAT_WS('|',id,latitude,longitude,COALESCE(velocidade,''),tipo,fonte,COALESCE(expires_at,'')))),0) content_sum FROM radars WHERE ativo=1 AND (expires_at IS NULL OR expires_at>=CURRENT_TIMESTAMP)")->fetch();$dbSignature=sha1(json_encode($sig));}catch(Throwable $e){}
$cacheFresh=is_file($cacheFile)&&filemtime($cacheFile)>time()-$ttl;
$cacheValid=$cacheFresh&&($kind==='map'||(is_file($metaFile)&&hash_equals($dbSignature,trim((string)@file_get_contents($metaFile)))));
if($cacheValid){$raw=(string)@file_get_contents($cacheFile);if($raw!==''){header('Cache-Control: no-store, max-age=0');header('Content-Type: application/json; charset=utf-8');header('Content-Disposition: attachment; filename="MusicRoad-'.$uf.'-'.$kind.'.json"');header('X-MusicRoad-Offline-State: cache-validado');header('X-MusicRoad-Storage: server-cache+device');echo $raw;exit;}}
$buildLock=null;
if($kind==='map'){
  $buildLock=@fopen($cacheFile.'.lock','c');
  if(is_resource($buildLock)&&!@flock($buildLock,LOCK_EX|LOCK_NB)){@fclose($buildLock);json_response(['ok'=>false,'error'=>'Esse mapa estadual já está sendo preparado. Aguarde um momento e tente novamente.'],409);}
  if(is_file($cacheFile)&&filemtime($cacheFile)>time()-$ttl){$raw=(string)@file_get_contents($cacheFile);if($raw!==''){if(is_resource($buildLock)){@flock($buildLock,LOCK_UN);@fclose($buildLock);}header('Cache-Control: no-store, max-age=0');header('Content-Type: application/json; charset=utf-8');header('Content-Disposition: attachment; filename="MusicRoad-'.$uf.'-'.$kind.'.json"');header('X-MusicRoad-Offline-State: cache-validado');header('X-MusicRoad-Storage: server-cache+device');echo $raw;exit;}}
}

$boundaryUrl='https://servicodados.ibge.gov.br/api/v4/malhas/estados/'.$stateId.'?formato=application/vnd.geo+json&qualidade=minima';
$boundary=http_json($boundaryUrl,null,[],25);
function mr_state_geojson_bbox($node,array &$box): void {if(!is_array($node))return;if(count($node)>=2&&is_numeric($node[0])&&is_numeric($node[1])){$lon=(float)$node[0];$lat=(float)$node[1];$box[0]=min($box[0],$lat);$box[1]=min($box[1],$lon);$box[2]=max($box[2],$lat);$box[3]=max($box[3],$lon);return;}foreach($node as $v)if(is_array($v))mr_state_geojson_bbox($v,$box);}
function mr_state_geojson_polygons($node): array {if(!is_array($node))return[];$type=(string)($node['type']??'');if($type==='Feature')return mr_state_geojson_polygons($node['geometry']??null);if($type==='FeatureCollection'){$out=[];foreach(($node['features']??[])as $feature)$out=array_merge($out,mr_state_geojson_polygons($feature));return$out;}if($type==='Polygon')return[is_array($node['coordinates']??null)?$node['coordinates']:[]];if($type==='MultiPolygon')return is_array($node['coordinates']??null)?$node['coordinates']:[];return[];}
function mr_state_point_in_ring(float $lat,float $lon,array $ring): bool {$inside=false;$count=count($ring);if($count<3)return false;for($i=0,$j=$count-1;$i<$count;$j=$i++){$xi=(float)($ring[$i][0]??0);$yi=(float)($ring[$i][1]??0);$xj=(float)($ring[$j][0]??0);$yj=(float)($ring[$j][1]??0);$cross=(($yi>$lat)!==($yj>$lat))&&($lon<($xj-$xi)*($lat-$yi)/(($yj-$yi)?:1e-12)+$xi);if($cross)$inside=!$inside;}return$inside;}
function mr_state_contains_point(float $lat,float $lon,array $polygons): bool {foreach($polygons as $polygon){if(!is_array($polygon)||!mr_state_point_in_ring($lat,$lon,(array)($polygon[0]??[])))continue;$hole=false;for($i=1;$i<count($polygon);$i++)if(mr_state_point_in_ring($lat,$lon,(array)$polygon[$i])){$hole=true;break;}if(!$hole)return true;}return false;}
$stateBox=[INF,INF,-INF,-INF];if(is_array($boundary))mr_state_geojson_bbox($boundary,$stateBox);$stateBoxValid=is_finite($stateBox[0])&&is_finite($stateBox[1])&&is_finite($stateBox[2])&&is_finite($stateBox[3]);
$statePolygons=is_array($boundary)?mr_state_geojson_polygons($boundary):[];
if($kind==='map'&&!$stateBoxValid){if(is_resource($buildLock)){@flock($buildLock,LOCK_UN);@fclose($buildLock);}json_response(['ok'=>false,'error'=>'Não foi possível obter o limite oficial do estado agora. Tente novamente mais tarde.'],502);}

$points=[];
try{
  $sql="SELECT id,external_id,latitude,longitude,velocidade,tipo,fonte,cidade,uf FROM radars WHERE ativo=1 AND (expires_at IS NULL OR expires_at>=CURRENT_TIMESTAMP) AND (UPPER(COALESCE(uf,''))=?";
  $params=[$uf];if($stateBoxValid){$sql.=" OR ((uf IS NULL OR uf='') AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?)";$params=array_merge($params,[$stateBox[0],$stateBox[2],$stateBox[1],$stateBox[3]]);}$sql.=')';
  $stmt=db()->prepare($sql);$stmt->execute($params);
  $seen=[];foreach($stmt->fetchAll() as $r){$lat=(float)$r['latitude'];$lon=(float)$r['longitude'];$rowUf=strtoupper(trim((string)($r['uf']??'')));if($rowUf===''&&$statePolygons&&!mr_state_contains_point($lat,$lon,$statePolygons))continue;$key=(string)($r['external_id']??'');if($key==='')$key=strtoupper((string)($r['tipo']??'RADAR_FIXO')).'|'.round($lat,5).'|'.round($lon,5);if(isset($seen[$key]))continue;$seen[$key]=true;$points[]=['id'=>'db-'.($r['id']??''),'external_id'=>$r['external_id']??null,'latitude'=>$lat,'longitude'=>$lon,'tipo'=>$r['tipo']??'RADAR_FIXO','velocidade'=>$r['velocidade']!==null?(int)$r['velocidade']:null,'fonte'=>$r['fonte']??'Servidor','cidade'=>$r['cidade']??null,'uf'=>$uf];}
}catch(Throwable $e){}

$roads=[];
if($kind==='map'){
  // Consulta apenas eixos principais para manter o pacote estadual leve.
  $q='[out:json][timeout:70];(rel["boundary"="administrative"]["IBGE:GEOCODIGO"="'.$stateId.'"];rel["boundary"="administrative"]["ref:IBGE"="'.$stateId.'"];);map_to_area->.a;way(area.a)[highway~"^(motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link)$"];out geom tags;';
  $eps=['https://overpass-api.de/api/interpreter','https://overpass.kumi.systems/api/interpreter'];$data=null;
  foreach($eps as $ep){$x=http_json($ep,'data='.rawurlencode($q),['Content-Type: application/x-www-form-urlencoded'],78,67108864);if(is_array($x)&&isset($x['elements'])){$data=$x;break;}}
  if(is_array($data))foreach(($data['elements']??[]) as $e){if(($e['type']??'')!=='way'||empty($e['geometry']))continue;$coords=[];foreach($e['geometry'] as $g)if(isset($g['lat'],$g['lon']))$coords[]=[(float)$g['lon'],(float)$g['lat']];if(count($coords)>1)$roads[]=['type'=>'Feature','properties'=>['id'=>$e['id']??null,'name'=>$e['tags']['name']??null,'ref'=>$e['tags']['ref']??null,'highway'=>$e['tags']['highway']??null],'geometry'=>['type'=>'LineString','coordinates'=>$coords]];}
  if(!$roads){if(is_resource($buildLock)){@flock($buildLock,LOCK_UN);@fclose($buildLock);}error_log('[MusicRoad offline_state] provedor viário indisponível para '.$uf);json_response(['ok'=>false,'error'=>'Não foi possível obter as principais rodovias desse estado agora. Tente novamente mais tarde.'],502);}
}
$pack=['ok'=>true,'version'=>2,'data_version'=>$dbSignature,'kind'=>$kind,'state'=>['id'=>$stateId,'name'=>$state,'uf'=>$uf],'generated_at'=>gmdate('c'),'radars'=>$points,'map'=>$kind==='map'?['boundary'=>$boundary,'roads'=>['type'=>'FeatureCollection','features'=>$roads],'mode'=>'vector-state-light','note'=>'Mapa estadual leve: limite do IBGE e principais rodovias disponíveis no OpenStreetMap.']:null,'attribution'=>'Radares: base MusicRoad; mapa: IBGE e OpenStreetMap contributors'];
$raw=json_encode($pack,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);if(!is_string($raw)||strlen($raw)>67108864){if(is_resource($buildLock)){@flock($buildLock,LOCK_UN);@fclose($buildLock);}json_response(['ok'=>false,'error'=>'O pacote estadual ficou maior que o limite seguro do dispositivo.'],507);}
if(!write_runtime_file($cacheFile,$raw)){if(is_resource($buildLock)){@flock($buildLock,LOCK_UN);@fclose($buildLock);}json_response(['ok'=>false,'error'=>'Não foi possível armazenar o pacote estadual no cache técnico.'],507);}if($kind==='radars')write_runtime_file($metaFile,$dbSignature);if(is_resource($buildLock)){@flock($buildLock,LOCK_UN);@fclose($buildLock);}
header('Cache-Control: no-store, max-age=0');header('Content-Type: application/json; charset=utf-8');header('Content-Disposition: attachment; filename="MusicRoad-'.$uf.'-'.$kind.'.json"');header('X-MusicRoad-Offline-State: fresh');header('X-MusicRoad-Storage: server-cache+device');echo $raw;
