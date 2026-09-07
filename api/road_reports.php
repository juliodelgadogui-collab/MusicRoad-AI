<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';
require_once __DIR__ . '/server_intelligent.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

$body = input_json();
$user = native_require_json_user($body);
intelligent_server_ensure_schema();

$type = strtoupper(trim((string)($body['type'] ?? '')));
$lat = (float)($body['lat'] ?? 0);
$lon = (float)($body['lon'] ?? 0);
$speed = isset($body['speed_kmh']) ? (int)$body['speed_kmh'] : null;
$limit = isset($body['limit_kmh']) ? (int)$body['limit_kmh'] : null;
$note = trim((string)($body['note'] ?? ''));
$source = strtoupper(trim((string)($body['source'] ?? 'APP')));
$allowed = [
    'RADAR_NOVO','RADAR_REMOVIDO','RADAR_CONFIRMADO','LIMITE_ERRADO',
    'QUEBRA_MOLAS','QUEBRA_MOLAS_REMOVIDO',
    'SEMAFORO_RADAR_NOVO','SEMAFORO_RADAR_REMOVIDO',
    'CAMERA_MONITORAMENTO','CAMERA_REMOVIDA'
];

if (!in_array($type,$allowed,true)) json_response(['ok'=>false,'error'=>'Tipo de reporte inválido.'],422);
if (!is_finite($lat) || !is_finite($lon) || $lat < -35 || $lat > 6 || $lon < -75 || $lon > -30) json_response(['ok'=>false,'error'=>'Coordenada inválida.'],422);
if ($speed !== null && ($speed < 0 || $speed > 250)) $speed = null;
if ($limit !== null && ($limit < 10 || $limit > 180)) $limit = null;
if ($note !== '') $note = function_exists('mb_substr') ? mb_substr($note,0,500) : substr($note,0,500);
$source = preg_replace('/[^A-Z0-9_.-]/','',$source) ?: 'APP';
$source = substr($source,0,32);

$userId = (int)($user['id'] ?? 0);
$device = native_device_token_from_request($body);
$duplicate = intelligent_server_find_duplicate($userId,$device,$type,$lat,$lon);
if ($duplicate > 0) {
    json_response(['ok'=>true,'id'=>$duplicate,'status'=>'PENDENTE','duplicate'=>true]);
}

try {
    $stmt=db()->prepare('INSERT INTO road_reports (user_id,device_token,type,latitude,longitude,speed_kmh,limit_kmh,note,source,status,created_at) VALUES (?,?,?,?,?,?,?,?,?,\'PENDENTE\',?)');
    $stmt->execute([$userId ?: null,$device !== '' ? $device : null,$type,$lat,$lon,$speed,$limit,$note !== '' ? $note : null,$source,date('Y-m-d H:i:s')]);
    $id=(int)db()->lastInsertId();
    audit_log('road_report.submit',['report_id'=>$id,'type'=>$type]);
    json_response(['ok'=>true,'id'=>$id,'status'=>'PENDENTE']);
} catch (Throwable $e) {
    error_log('ROAD_REPORT: '.$e->getMessage());
    json_response(['ok'=>false,'error'=>'Não foi possível registrar o reporte agora.'],503);
}
