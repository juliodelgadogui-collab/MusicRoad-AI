<?php
declare(strict_types=1);
require __DIR__.'/bootstrap.php';
require_login();
header('Cache-Control: no-store');

$driver=(string)db()->getAttribute(PDO::ATTR_DRIVER_NAME);
if($driver==='mysql'){
    db()->exec("CREATE TABLE IF NOT EXISTS road_reports (
      id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
      user_id BIGINT NULL, device_token VARCHAR(190) NULL,
      type VARCHAR(50) NOT NULL, latitude DOUBLE NOT NULL, longitude DOUBLE NOT NULL,
      status VARCHAR(30) NOT NULL DEFAULT 'PENDENTE', created_at DATETIME NOT NULL,
      INDEX idx_rr_status(status), INDEX idx_rr_geo(latitude,longitude)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
}else{
    db()->exec("CREATE TABLE IF NOT EXISTS road_reports (id INTEGER PRIMARY KEY AUTOINCREMENT,user_id INTEGER,device_token TEXT,type TEXT NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL,status TEXT NOT NULL DEFAULT 'PENDENTE',created_at TEXT NOT NULL)");
}
$body=json_decode((string)file_get_contents('php://input'),true)?:[];
$type=strtoupper(trim((string)($body['type']??'')));
$lat=(float)($body['lat']??0);$lon=(float)($body['lon']??0);
$allowed=['RADAR_NOVO','RADAR_REMOVIDO','LIMITE_ERRADO','QUEBRA_MOLAS','CAMERA_MONITORAMENTO'];
if(!in_array($type,$allowed,true)||$lat<-35||$lat>6||$lon<-75||$lon>-30)json_response(['ok'=>false,'error'=>'Reporte inválido.'],422);
$user=$_SESSION['user_id']??null;$device=(string)($_SESSION['device_token']??'');
$stmt=db()->prepare('INSERT INTO road_reports (user_id,device_token,type,latitude,longitude,status,created_at) VALUES (?,?,?,?,?,\'PENDENTE\',?)');
$stmt->execute([$user,$device!==''?$device:null,$type,$lat,$lon,gmdate('Y-m-d H:i:s')]);
json_response(['ok'=>true,'id'=>(int)db()->lastInsertId(),'status'=>'PENDENTE']);
