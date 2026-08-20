<?php
require __DIR__ . '/bootstrap.php';
require_admin();
require_session_rate_limit('radar-health',3,600);
session_write_close();
require_once __DIR__ . '/radar_route_engine.php';

$checks=[];
$checks['php']=['ok'=>true,'version'=>PHP_VERSION,'curl'=>function_exists('curl_init'),'dom'=>class_exists('DOMDocument'),'zip'=>class_exists('ZipArchive'),'mysql'=>extension_loaded('pdo_mysql')];
try{
    $db=db();
    $total=(int)$db->query('SELECT COUNT(*) FROM radars')->fetchColumn();
    $active=(int)$db->query('SELECT COUNT(*) FROM radars WHERE ativo=1')->fetchColumn();
    $invalid=(int)$db->query('SELECT COUNT(*) FROM radars WHERE ativo=1 AND (latitude IS NULL OR longitude IS NULL OR latitude < -34.5 OR latitude > 6.5 OR longitude < -74.5 OR longitude > -33.0)')->fetchColumn();
    $src=[];$q=$db->query('SELECT COALESCE(NULLIF(fonte,""),"SEM_FONTE") fonte, COUNT(*) qtd FROM radars WHERE ativo=1 GROUP BY COALESCE(NULLIF(fonte,""),"SEM_FONTE") ORDER BY qtd DESC LIMIT 20');
    foreach($q?:[] as $row)$src[(string)$row['fonte']]=(int)$row['qtd'];
    $checks['database']=['ok'=>true,'total'=>$total,'active'=>$active,'invalid_coordinates'=>$invalid,'sources'=>$src];
}catch(Throwable $e){$checks['database']=['ok'=>false,'error'=>'check_failed','incident'=>report_runtime_exception('radar_health_database',$e)];}

try{
    $r=mr_antt_radars_for_route([[-73.99,-33.75],[-34.79,5.28]]);
    $checks['antt']=['ok'=>(bool)($r['ok']??false)&&count($r['items']??[])>0,'records'=>$r['rows']??0,'valid_active_coordinates'=>count($r['items']??[]),'resource'=>$r['resource']??null,'error'=>$r['error']??null];
}catch(Throwable $e){$checks['antt']=['ok'=>false,'error'=>mr_radar_provider_exception('health_antt',$e)];}

try{
    $raw=mr_cached_text('health_v11_der_es','https://servicos.der.es.gov.br/Radares.aspx',1800);
    $rows=$raw?mr_parse_der_es_table($raw,'FIXO'):[];
    $known=null;
    foreach($rows as $row){if(($row['codigo']??'')==='EMEVD-396'||((string)($row['rodovia']??'')==='ES-297'&&abs((float)($row['km']??0)-10.5)<.05)){$known=$row;break;}}
    $position=null;
    try{$position=mr_der_es_position('ES-297',10.5);}catch(Throwable $e){}
    $checks['der_es']=['ok'=>count($rows)>0,'records'=>count($rows),'known_es297_km10_5'=>(bool)$known,'known_speed'=>$known['velocidade']??null,'geobases_position_ok'=>is_array($position),'position'=>$position,'bytes'=>$raw?strlen($raw):0];
}catch(Throwable $e){$checks['der_es']=['ok'=>false,'error'=>mr_radar_provider_exception('health_der_es',$e)];}

try{
    $fixed=mr_der_rj_fixed_rows_for_refs(['RJ-186']);
    $portable=mr_der_rj_portable_rows_for_refs(['RJ-186']);
    $known=null;foreach($portable as $row){if(($row['rodovia']??'')==='RJ-186'&&abs((float)($row['km']??0)-86.0)<.05){$known=$row;break;}}
    $checks['der_rj']=['ok'=>count($fixed)+count($portable)>0,'fixed_active'=>count($fixed),'portable_active'=>count($portable),'known_rj186_km86'=>(bool)$known,'known_city'=>$known['cidade']??null,'known_speed'=>$known['velocidade']??null,'known_type'=>$known['tipo']??null];
}catch(Throwable $e){$checks['der_rj']=['ok'=>false,'error'=>mr_radar_provider_exception('health_der_rj',$e)];}

try{
    $sample=[[-41.70,-21.14],[-41.64,-21.10],[-41.58,-21.08]];
    $r=mr_osm_radars_for_route($sample,1200);
    $checks['osm']=['ok'=>$r['ok']??false,'elements'=>$r['elements']??0,'endpoint'=>$r['endpoint']??null,'attempts'=>$r['attempts']??[],'error'=>$r['error']??null];
}catch(Throwable $e){$checks['osm']=['ok'=>false,'error'=>mr_radar_provider_exception('health_osm',$e)];}

try{
    $route=[[-41.70,-21.14],[-41.65,-21.10],[-41.60,-21.08]];
    $synthetic=[['external_id'=>'selftest','latitude'=>-21.10,'longitude'=>-41.65,'velocidade'=>60,'fonte'=>'SELFTEST','ativo'=>1]];
    $filtered=mr_filter_radars_on_route($synthetic,$route);
    $checks['route_engine']=['ok'=>count($filtered)===1,'matched'=>count($filtered),'distance_to_route_m'=>$filtered[0]['distance_to_route_m']??null,'route_m'=>$filtered[0]['route_m']??null];
}catch(Throwable $e){$checks['route_engine']=['ok'=>false,'error'=>mr_radar_provider_exception('health_engine',$e)];}

$ok=($checks['database']['ok']??false)&&($checks['antt']['ok']??false)&&($checks['route_engine']['ok']??false)&&($checks['osm']['ok']??false)&&($checks['der_rj']['ok']??false);
json_response(['ok'=>$ok,'version'=>MUSICROAD_VERSION,'checks'=>$checks,'note'=>'Rota usa banco local + ANTT/DER/DNIT + OSM. No RJ, pontos por rodovia+km usam marcos OSM e fallback por localidades oficiais para evitar zero falso.']);
