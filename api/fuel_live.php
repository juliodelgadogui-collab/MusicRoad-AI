<?php
declare(strict_types=1);
require_once __DIR__.'/bootstrap.php';
require_once __DIR__.'/native_auth.php';
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

$body=input_json();
$user=native_require_json_user($body);
$pdo=db();
$pdo->exec("CREATE TABLE IF NOT EXISTS fuel_price_reports (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NULL,
 device_token VARCHAR(160) NULL,
 station VARCHAR(160) NOT NULL,
 fuel VARCHAR(48) NOT NULL,
 price DECIMAL(6,3) NOT NULL,
 latitude DECIMAL(10,7) NOT NULL,
 longitude DECIMAL(10,7) NOT NULL,
 reported_at BIGINT NULL,
 created_at DATETIME NOT NULL,
 INDEX idx_fuel_geo_time (latitude,longitude,created_at),
 INDEX idx_fuel_type_time (fuel,created_at),
 INDEX idx_fuel_device (device_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

$action=strtolower(trim((string)($_GET['action']??'')));
if($action==='report'){
 $lat=(float)($body['lat']??0);$lon=(float)($body['lon']??0);$price=(float)($body['price']??0);
 $station=trim((string)($body['station']??''));$fuel=trim((string)($body['fuel']??''));
 if(!is_finite($lat)||!is_finite($lon)||$lat < -35||$lat > 6||$lon < -75||$lon > -30)json_response(['ok'=>false,'error'=>'Coordenada inválida.'],422);
 if($price<1||$price>20||mb_strlen($station)<2||mb_strlen($station)>160||mb_strlen($fuel)<2||mb_strlen($fuel)>48)json_response(['ok'=>false,'error'=>'Relato de preço inválido.'],422);
 $device=native_device_token_from_request($body);$userId=(int)($user['id']??0);$reported=(int)($body['at']??round(microtime(true)*1000));
 try{
  $q=$pdo->prepare("SELECT id FROM fuel_price_reports WHERE COALESCE(device_token,'')=? AND fuel=? AND LOWER(station)=LOWER(?) AND ABS(latitude-?)<0.001 AND ABS(longitude-?)<0.001 AND created_at>=DATE_SUB(NOW(),INTERVAL 15 MINUTE) ORDER BY id DESC LIMIT 1");
  $q->execute([$device,$fuel,$station,$lat,$lon]);$existing=(int)($q->fetchColumn()?:0);
  if($existing>0){$u=$pdo->prepare('UPDATE fuel_price_reports SET price=?,latitude=?,longitude=?,reported_at=?,created_at=NOW() WHERE id=?');$u->execute([$price,$lat,$lon,$reported,$existing]);json_response(['ok'=>true,'id'=>$existing,'updated'=>true]);}
 }catch(Throwable $e){}
 try{$q=$pdo->prepare('INSERT INTO fuel_price_reports (user_id,device_token,station,fuel,price,latitude,longitude,reported_at,created_at) VALUES (?,?,?,?,?,?,?,?,NOW())');$q->execute([$userId?:null,$device!==''?$device:null,$station,$fuel,$price,$lat,$lon,$reported]);json_response(['ok'=>true,'id'=>(int)$pdo->lastInsertId(),'stored'=>true]);}catch(Throwable $e){error_log('FUEL_LIVE report: '.$e->getMessage());json_response(['ok'=>false,'error'=>'Não foi possível salvar o preço agora.'],503);}
}

if($action==='near'){
 $lat=(float)($_GET['lat']??0);$lon=(float)($_GET['lon']??0);$radius=max(1000,min(60000,(int)($_GET['radius']??30000)));
 if(!is_finite($lat)||!is_finite($lon)||$lat < -35||$lat > 6||$lon < -75||$lon > -30)json_response(['ok'=>false,'error'=>'Coordenada inválida.'],422);
 $deg=max(0.02,$radius/95000.0);$rows=[];
 try{$q=$pdo->prepare("SELECT id,station,fuel,price,latitude,longitude,reported_at,UNIX_TIMESTAMP(created_at)*1000 AS created_ms FROM fuel_price_reports WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? AND created_at>=DATE_SUB(NOW(),INTERVAL 36 HOUR) ORDER BY created_at DESC LIMIT 180");$q->execute([$lat-$deg,$lat+$deg,$lon-$deg,$lon+$deg]);while($r=$q->fetch(PDO::FETCH_ASSOC)){$la=(float)$r['latitude'];$lo=(float)$r['longitude'];$x=sin(deg2rad(($la-$lat)/2));$y=sin(deg2rad(($lo-$lon)/2));$a=$x*$x+cos(deg2rad($lat))*cos(deg2rad($la))*$y*$y;$d=6371000*2*atan2(sqrt($a),sqrt(max(0,1-$a)));if($d>$radius)continue;$rows[]=['id'=>(int)$r['id'],'station'=>(string)$r['station'],'fuel'=>(string)$r['fuel'],'price'=>(float)$r['price'],'lat'=>$la,'lon'=>$lo,'at'=>(int)($r['reported_at']?:$r['created_ms']),'distance_m'=>round($d)];}}
 catch(Throwable $e){error_log('FUEL_LIVE near: '.$e->getMessage());json_response(['ok'=>false,'error'=>'Não foi possível consultar preços agora.'],503);}
 usort($rows,fn($a,$b)=>$a['price']<=>$b['price']);json_response(['ok'=>true,'prices'=>array_slice($rows,0,60),'max_age_hours'=>36]);
}
json_response(['ok'=>false,'error'=>'Ação inválida.'],404);
