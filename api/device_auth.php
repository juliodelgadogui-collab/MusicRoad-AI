<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
if($_SERVER['REQUEST_METHOD']!=='POST')json_response(['ok'=>false,'error'=>'Método inválido.'],405);
$action=trim((string)($_POST['action']??''));
$token=trim((string)($_POST['device_token']??''));
$appVersion=trim((string)($_POST['app_version']??''));
$deviceLabel=trim((string)($_POST['device_label']??''));
try{
  if($action==='ping')json_response(['ok'=>true,'server'=>'MusicRoad','version'=>MUSICROAD_VERSION,'device_auth'=>true]);
  if($action==='login'){
    if(auth_rate_limited('device-login'))json_response(['ok'=>false,'registered'=>false,'error'=>'Muitas tentativas. Aguarde alguns minutos.'],429);
    $result=registered_device_login($token,$appVersion);auth_record_attempt('device-login',!empty($result['ok']));json_response($result);
  }
  if($action==='bind'){
    $u=current_user();if(!$u)json_response(['ok'=>false,'error'=>'Faça login primeiro.'],401);
    require_csrf();require_session_rate_limit('device-bind',10,600);session_write_close();
    $r=registered_device_bind((int)$u['id'],$token,$deviceLabel,$appVersion);json_response($r);
  }
  if($action==='status'){
    $u=current_user();if(!$u)json_response(['ok'=>false,'registered'=>false],401);require_session_rate_limit('device-status',120,60);session_write_close();json_response(['ok'=>true]+registered_device_status((int)$u['id'],$token));
  }
  if($action==='remove'){
    $u=current_user();if(!$u)json_response(['ok'=>false,'error'=>'Sessão expirada.'],401);
    require_csrf();require_session_rate_limit('device-remove',5,600);session_write_close();
    $ok=registered_device_remove((int)$u['id'],$token);json_response(['ok'=>$ok,'removed'=>$ok]);
  }
  json_response(['ok'=>false,'error'=>'Ação inválida.'],422);
}catch(Throwable $e){
  $message=$e->getMessage();
  if($e instanceof RuntimeException && !($e instanceof PDOException) && str_starts_with($message,'Este aparelho já está vinculado'))json_response(['ok'=>false,'error'=>$message],409);
  $incident=report_runtime_exception('device_auth',$e);json_response(['ok'=>false,'error'=>'Não foi possível concluir a autenticação do dispositivo.','incident'=>$incident],500);
}
