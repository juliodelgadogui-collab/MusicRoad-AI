<?php
declare(strict_types=1);
require_once __DIR__.'/bootstrap.php';
require_once __DIR__.'/road_hazard_db.php';
require_once __DIR__.'/road_safety_pack_helpers.php';

require_admin();
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

$checks=[];
$add=static function(string $key,bool $ok,string $detail)use(&$checks){$checks[$key]=['ok'=>$ok,'detail'=>$detail];};

$driver=(string)db()->getAttribute(PDO::ATTR_DRIVER_NAME);
$add('database',in_array($driver,['mysql','sqlite'],true),'driver '.$driver);

$routing=trim((string)app_setting('routing_osrm_url',''));
$add('routing_private',$routing!=='',$routing!==''?$routing:'routing_osrm_url não configurado');
$turn=trim((string)app_setting('radio_turn_url',''));
$add('ptt_turn',$turn!=='',$turn!==''?$turn:'radio_turn_url não configurado');

try{
 road_hazard_ensure_tables();
 $total=0;$states=0;
 foreach(ep2_brazil_ufs() as $uf){$n=road_hazard_count_state($uf);$total+=$n;if($n>0)$states++;}
 $add('national_safety',$states>=20&&$total>1000,"{$states}/27 UFs · {$total} pontos");
}catch(Throwable $e){$add('national_safety',false,'erro: '.$e->getMessage());}

try{
 $cols=schema_columns('client_telemetry');
 $add('telemetry',!empty($cols),!empty($cols)?'tabela pronta':'será criada no primeiro lote autenticado');
}catch(Throwable $e){$add('telemetry',false,'indisponível');}

$weatherOk=function_exists('http_json');
$add('weather_proxy',$weatherOk,'weather_batch.php usa cache central do Estrada Play');
$add('https',(!empty($_SERVER['HTTPS'])&&$_SERVER['HTTPS']!=='off'),'servidor deve operar somente por HTTPS');

$passed=0;foreach($checks as $c)if(!empty($c['ok']))$passed++;
$score=(int)round(10*$passed/max(1,count($checks)));
json_response(['ok'=>true,'production_score'=>$score,'checks'=>$checks,'ready'=>$score>=9]);
