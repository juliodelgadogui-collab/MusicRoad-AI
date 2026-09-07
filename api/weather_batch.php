<?php
declare(strict_types=1);
require_once __DIR__.'/bootstrap.php';
require_once __DIR__.'/native_auth.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: private, max-age=120');
$body=input_json();
$user=native_require_json_user($body);
$points=is_array($body['points']??null)?$body['points']:[];
$hours=max(6,min(24,(int)($body['hours']??18)));
if(!$points||count($points)>6)json_response(['ok'=>false,'error'=>'Informe de 1 a 6 pontos de clima.'],422);

$lats=[];$lons=[];
foreach($points as $p){
 if(!is_array($p))continue;
 $lat=(float)($p['lat']??NAN);$lon=(float)($p['lon']??NAN);
 if(!is_finite($lat)||!is_finite($lon)||$lat<-35.5||$lat>6.5||$lon<-75.5||$lon>-30.0)continue;
 $lats[]=sprintf('%.5F',$lat);$lons[]=sprintf('%.5F',$lon);
}
if(!$lats)json_response(['ok'=>false,'error'=>'Pontos de clima inválidos.'],422);

$pdo=db();$driver=(string)$pdo->getAttribute(PDO::ATTR_DRIVER_NAME);
try{
 if($driver==='mysql')$pdo->exec("CREATE TABLE IF NOT EXISTS weather_batch_cache (cache_key CHAR(64) PRIMARY KEY,payload LONGTEXT NOT NULL,expires_at DATETIME NOT NULL,created_at DATETIME NOT NULL,KEY idx_weather_batch_exp(expires_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
 else{$pdo->exec("CREATE TABLE IF NOT EXISTS weather_batch_cache (cache_key TEXT PRIMARY KEY,payload TEXT NOT NULL,expires_at TEXT NOT NULL,created_at TEXT NOT NULL)");$pdo->exec("CREATE INDEX IF NOT EXISTS idx_weather_batch_exp ON weather_batch_cache(expires_at)");}
}catch(Throwable $ignored){}

$key=hash('sha256',implode(',',$lats).'|'.implode(',',$lons).'|'.$hours.'|'.date('YmdHi',time()-time()%600));
try{$s=$pdo->prepare('SELECT payload FROM weather_batch_cache WHERE cache_key=? AND expires_at>? LIMIT 1');$s->execute([$key,date('Y-m-d H:i:s')]);$raw=$s->fetchColumn();if($raw!==false){$j=json_decode((string)$raw,true);if(is_array($j))json_response(['ok'=>true,'forecasts'=>$j,'cached'=>true,'source'=>'open-meteo']);}}catch(Throwable $ignored){}

$params=[
 'latitude'=>implode(',',$lats),'longitude'=>implode(',',$lons),
 'current'=>'temperature_2m,apparent_temperature,precipitation,rain,showers,weather_code,wind_speed_10m,wind_gusts_10m',
 'hourly'=>'temperature_2m,apparent_temperature,precipitation_probability,precipitation,rain,showers,weather_code,wind_speed_10m,wind_gusts_10m',
 'forecast_hours'=>$hours,'timezone'=>'auto'
];
try{$data=http_json('https://api.open-meteo.com/v1/forecast?'.http_build_query($params,'','&',PHP_QUERY_RFC3986));}
catch(Throwable $e){json_response(['ok'=>false,'error'=>'Provedor de clima indisponível.'],502);}
if(!is_array($data))json_response(['ok'=>false,'error'=>'Previsão de clima vazia.'],502);
$forecasts=array_is_list($data)?$data:[$data];
$forecasts=array_values(array_filter($forecasts,'is_array'));
if(!$forecasts)json_response(['ok'=>false,'error'=>'Previsão de clima inválida.'],502);
try{$raw=json_encode($forecasts,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);$exp=date('Y-m-d H:i:s',time()+600);$now=date('Y-m-d H:i:s');if($driver==='mysql')$sql='INSERT INTO weather_batch_cache(cache_key,payload,expires_at,created_at) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE payload=VALUES(payload),expires_at=VALUES(expires_at),created_at=VALUES(created_at)';else$sql='INSERT INTO weather_batch_cache(cache_key,payload,expires_at,created_at) VALUES(?,?,?,?) ON CONFLICT(cache_key) DO UPDATE SET payload=excluded.payload,expires_at=excluded.expires_at,created_at=excluded.created_at';$pdo->prepare($sql)->execute([$key,$raw,$exp,$now]);$pdo->prepare('DELETE FROM weather_batch_cache WHERE expires_at < ?')->execute([date('Y-m-d H:i:s',time()-3600)]);}catch(Throwable $ignored){}
json_response(['ok'=>true,'forecasts'=>$forecasts,'cached'=>false,'source'=>'open-meteo']);
