<?php
declare(strict_types=1);
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
require_once __DIR__.'/../api/bootstrap.php';

ensure_schema();
$pdo=db();$driver=(string)$pdo->getAttribute(PDO::ATTR_DRIVER_NAME);
$ddl=[];
if($driver==='mysql'){
 $ddl[]="CREATE TABLE IF NOT EXISTS client_telemetry (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NULL,kind VARCHAR(24) NOT NULL,activity VARCHAR(120) NULL,app_version VARCHAR(40) NULL,sdk INT NULL,manufacturer VARCHAR(100) NULL,model VARCHAR(120) NULL,exception VARCHAR(220) NULL,message VARCHAR(700) NULL,stack MEDIUMTEXT NULL,created_at DATETIME NOT NULL,KEY idx_client_telemetry_created(created_at),KEY idx_client_telemetry_kind(kind,created_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
 $ddl[]="CREATE TABLE IF NOT EXISTS weather_batch_cache (cache_key CHAR(64) PRIMARY KEY,payload LONGTEXT NOT NULL,expires_at DATETIME NOT NULL,created_at DATETIME NOT NULL,KEY idx_weather_batch_exp(expires_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
 $ddl[]="CREATE TABLE IF NOT EXISTS radio_presence (device_token VARCHAR(128) PRIMARY KEY,user_id BIGINT NULL,room_key VARCHAR(190) NOT NULL,road VARCHAR(32) NOT NULL,direction VARCHAR(16) NOT NULL,segment VARCHAR(40) NOT NULL,nickname VARCHAR(40) NOT NULL,joined_at DATETIME NOT NULL,last_seen_at DATETIME NOT NULL,KEY idx_radio_room_seen(room_key,last_seen_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
 $ddl[]="CREATE TABLE IF NOT EXISTS radio_signals (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,room_key VARCHAR(190) NOT NULL,from_device VARCHAR(128) NOT NULL,to_device VARCHAR(128) NOT NULL,signal_type VARCHAR(12) NOT NULL,payload MEDIUMTEXT NOT NULL,created_at DATETIME NOT NULL,KEY idx_radio_signal_to(to_device,id),KEY idx_radio_signal_room(room_key,created_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
 $ddl[]="CREATE TABLE IF NOT EXISTS radio_alerts (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,room_key VARCHAR(190) NOT NULL,device_token VARCHAR(128) NOT NULL,alert_type VARCHAR(24) NOT NULL,created_at DATETIME NOT NULL,expires_at DATETIME NOT NULL,KEY idx_radio_alert_room(room_key,expires_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
}else{
 $ddl[]="CREATE TABLE IF NOT EXISTS client_telemetry (id INTEGER PRIMARY KEY AUTOINCREMENT,user_id INTEGER,kind TEXT NOT NULL,activity TEXT,app_version TEXT,sdk INTEGER,manufacturer TEXT,model TEXT,exception TEXT,message TEXT,stack TEXT,created_at TEXT NOT NULL)";
 $ddl[]="CREATE TABLE IF NOT EXISTS weather_batch_cache (cache_key TEXT PRIMARY KEY,payload TEXT NOT NULL,expires_at TEXT NOT NULL,created_at TEXT NOT NULL)";
 $ddl[]="CREATE TABLE IF NOT EXISTS radio_presence (device_token TEXT PRIMARY KEY,user_id INTEGER,room_key TEXT NOT NULL,road TEXT NOT NULL,direction TEXT NOT NULL,segment TEXT NOT NULL,nickname TEXT NOT NULL,joined_at TEXT NOT NULL,last_seen_at TEXT NOT NULL)";
 $ddl[]="CREATE TABLE IF NOT EXISTS radio_signals (id INTEGER PRIMARY KEY AUTOINCREMENT,room_key TEXT NOT NULL,from_device TEXT NOT NULL,to_device TEXT NOT NULL,signal_type TEXT NOT NULL,payload TEXT NOT NULL,created_at TEXT NOT NULL)";
 $ddl[]="CREATE TABLE IF NOT EXISTS radio_alerts (id INTEGER PRIMARY KEY AUTOINCREMENT,room_key TEXT NOT NULL,device_token TEXT NOT NULL,alert_type TEXT NOT NULL,created_at TEXT NOT NULL,expires_at TEXT NOT NULL)";
}
foreach($ddl as $sql)$pdo->exec($sql);
echo "Estrada Play production schema ready using {$driver}.\n";
