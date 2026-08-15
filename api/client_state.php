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

$stmt = db()->prepare("INSERT INTO client_device_state
  (user_id,device_id,device_name,music_permission,location_permission,music_folder_count,music_track_count,last_latitude,last_longitude,last_seen_at)
  VALUES (?,?,?,?,?,?,?,?,?,datetime('now'))
  ON CONFLICT(user_id,device_id) DO UPDATE SET
    device_name=excluded.device_name,music_permission=excluded.music_permission,location_permission=excluded.location_permission,
    music_folder_count=excluded.music_folder_count,music_track_count=excluded.music_track_count,
    last_latitude=COALESCE(excluded.last_latitude,last_latitude),last_longitude=COALESCE(excluded.last_longitude,last_longitude),last_seen_at=datetime('now')");
$stmt->execute([(int)$user['id'],$deviceId,$deviceName,$musicPermission,$locationPermission,$folderCount,$trackCount,$lat,$lon]);
json_response(['ok'=>true]);
