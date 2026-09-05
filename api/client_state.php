<?php
require __DIR__ . '/bootstrap.php';
ensure_schema();
$user = require_client();

if ($_SERVER['REQUEST_METHOD'] === 'GET') {
    $deviceId = trim((string)($_GET['device_id'] ?? ''));
    if ($deviceId === '') json_response(['ok'=>true,'state'=>null,'csrf'=>csrf_token()]);
    $stmt = db()->prepare('SELECT * FROM client_device_state WHERE user_id=? AND device_id=? LIMIT 1');
    $stmt->execute([(int)$user['id'],$deviceId]);
    json_response(['ok'=>true,'state'=>$stmt->fetch() ?: null,'csrf'=>csrf_token()]);
}

require_csrf();
$data = input_json();
$deviceId = trim((string)($data['device_id'] ?? ''));
if ($deviceId === '') json_response(['ok'=>false,'error'=>'Dispositivo inválido.'],422);
$deviceName = mb_substr(trim((string)($data['device_name'] ?? '')),0,120);
$musicPermission = mb_substr(trim((string)($data['music_permission'] ?? 'unknown')),0,30);
$locationPermission = mb_substr(trim((string)($data['location_permission'] ?? 'unknown')),0,30);
$folderCount = max(0,(int)($data['music_folder_count'] ?? 0));
$trackCount = max(0,(int)($data['music_track_count'] ?? 0));
$lat = isset($data['last_latitude']) && is_numeric($data['last_latitude']) ? (float)$data['last_latitude'] : null;
$lon = isset($data['last_longitude']) && is_numeric($data['last_longitude']) ? (float)$data['last_longitude'] : null;

// SERVER_DB_COMPAT_V209: portable upsert for SQLite and MySQL/MariaDB.
$now = date('Y-m-d H:i:s');
$check = db()->prepare('SELECT id FROM client_device_state WHERE user_id=? AND device_id=? LIMIT 1');
$check->execute([(int)$user['id'],$deviceId]);
$existingId = (int)($check->fetchColumn() ?: 0);
if ($existingId > 0) {
    $stmt = db()->prepare('UPDATE client_device_state SET device_name=?,music_permission=?,location_permission=?,music_folder_count=?,music_track_count=?,last_latitude=COALESCE(?,last_latitude),last_longitude=COALESCE(?,last_longitude),last_seen_at=? WHERE id=?');
    $stmt->execute([$deviceName,$musicPermission,$locationPermission,$folderCount,$trackCount,$lat,$lon,$now,$existingId]);
} else {
    $stmt = db()->prepare('INSERT INTO client_device_state (user_id,device_id,device_name,music_permission,location_permission,music_folder_count,music_track_count,last_latitude,last_longitude,last_seen_at) VALUES (?,?,?,?,?,?,?,?,?,?)');
    $stmt->execute([(int)$user['id'],$deviceId,$deviceName,$musicPermission,$locationPermission,$folderCount,$trackCount,$lat,$lon,$now]);
}
json_response(['ok'=>true]);
