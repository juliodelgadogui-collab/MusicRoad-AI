<?php
declare(strict_types=1);
require_once __DIR__.'/bootstrap.php';
require_once __DIR__.'/native_auth.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
$body=input_json();
$user=native_require_json_user($body);
$events=is_array($body['events']??null)?$body['events']:[];
if(count($events)>20)json_response(['ok'=>false,'error'=>'Lote de telemetria muito grande.'],422);

$pdo=db();$driver=(string)$pdo->getAttribute(PDO::ATTR_DRIVER_NAME);
try{
 if($driver==='mysql'){
  $pdo->exec("CREATE TABLE IF NOT EXISTS client_telemetry (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NULL,kind VARCHAR(24) NOT NULL,activity VARCHAR(120) NULL,app_version VARCHAR(40) NULL,sdk INT NULL,manufacturer VARCHAR(100) NULL,model VARCHAR(120) NULL,exception VARCHAR(220) NULL,message VARCHAR(700) NULL,stack MEDIUMTEXT NULL,created_at DATETIME NOT NULL,KEY idx_client_telemetry_created(created_at),KEY idx_client_telemetry_kind(kind,created_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
 }else{
  $pdo->exec("CREATE TABLE IF NOT EXISTS client_telemetry (id INTEGER PRIMARY KEY AUTOINCREMENT,user_id INTEGER,kind TEXT NOT NULL,activity TEXT,app_version TEXT,sdk INTEGER,manufacturer TEXT,model TEXT,exception TEXT,message TEXT,stack TEXT,created_at TEXT NOT NULL)");
  $pdo->exec("CREATE INDEX IF NOT EXISTS idx_client_telemetry_created ON client_telemetry(created_at)");
 }
}catch(Throwable $e){json_response(['ok'=>false,'error'=>'Telemetria temporariamente indisponível.'],503);}

$insert=$pdo->prepare('INSERT INTO client_telemetry(user_id,kind,activity,app_version,sdk,manufacturer,model,exception,message,stack,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)');
$count=0;
foreach($events as $e){
 if(!is_array($e))continue;
 $kind=strtolower(trim((string)($e['kind']??'')));
 if(!in_array($kind,['crash','anr'],true))continue;
 $activity=substr(trim((string)($e['activity']??'')),0,120);
 $version=substr(trim((string)($e['app_version']??'')),0,40);
 $sdk=max(0,min(100,(int)($e['sdk']??0)));
 $manufacturer=substr(trim((string)($e['manufacturer']??'')),0,100);
 $model=substr(trim((string)($e['model']??'')),0,120);
 $exception=substr(trim((string)($e['exception']??'')),0,220);
 $message=substr(trim((string)($e['message']??'')),0,700);
 $stack=substr((string)($e['stack']??''),0,12000);
 try{$insert->execute([(int)($user['id']??0),$kind,$activity,$version,$sdk,$manufacturer,$model,$exception,$message,$stack,date('Y-m-d H:i:s')]);$count++;}catch(Throwable $ignored){}
}

// Keep only a bounded diagnostics window on small servers.
try{$cut=date('Y-m-d H:i:s',time()-30*86400);$pdo->prepare('DELETE FROM client_telemetry WHERE created_at < ?')->execute([$cut]);}catch(Throwable $ignored){}
json_response(['ok'=>true,'stored'=>$count]);
