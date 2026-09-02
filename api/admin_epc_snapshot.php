<?php
declare(strict_types=1);
require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/server_intelligent.php';
header('Cache-Control: no-store');
$user=current_user();
if(!$user) json_response(['ok'=>false,'error'=>'Sessão expirada.'],401);
if(($user['role']??'')!=='admin') json_response(['ok'=>false,'error'=>'Acesso negado.'],403);
intelligent_server_ensure_schema();
function epc_admin_scalar(string $sql,array $params=[]): int|float|string {
    try{$s=db()->prepare($sql);$s->execute($params);$v=$s->fetchColumn();return $v===false?0:$v;}catch(Throwable $e){return 0;}
}
$now=date('Y-m-d H:i:s');$onlineCut=date('Y-m-d H:i:s',time()-900);$fuelCut=date('Y-m-d H:i:s',time()-36*3600);
$health=server_health_snapshot(false);$reports=intelligent_server_report_stats();
$metrics=[
 'clients'=>(int)epc_admin_scalar("SELECT COUNT(*) FROM users WHERE role='client'"),
 'active_clients'=>(int)epc_admin_scalar("SELECT COUNT(*) FROM users WHERE role='client' AND status='active'"),
 'online_devices'=>(int)epc_admin_scalar('SELECT COUNT(*) FROM client_device_state WHERE last_seen_at>=?',[$onlineCut]),
 'pending_reports'=>(int)($reports['pending']??0),
 'live_events'=>(int)epc_admin_scalar('SELECT COUNT(*) FROM road_live_events WHERE expires_at>?',[$now]),
 'traffic_samples'=>(int)epc_admin_scalar('SELECT COUNT(*) FROM road_traffic_samples WHERE expires_at>?',[$now]),
 'weather_cache'=>(int)epc_admin_scalar('SELECT COUNT(*) FROM road_weather_cache WHERE expires_at>?',[$now]),
 'fuel_reports'=>(int)epc_admin_scalar('SELECT COUNT(*) FROM fuel_price_reports WHERE created_at>=?',[$fuelCut]),
 'hazards'=>(int)epc_admin_scalar('SELECT COUNT(*) FROM road_hazards WHERE active=1'),
 'convoys'=>(int)epc_admin_scalar('SELECT COUNT(*) FROM estrada_convoys WHERE expires_at>?',[$now]),
];
json_response(['ok'=>true,'metrics'=>$metrics,'quota_percent'=>(float)($health['quota_percent']??0),'server_version'=>ESTRADAPLAY_SERVER_INTELLIGENT_VERSION,'generated_at'=>date(DATE_ATOM)]);
