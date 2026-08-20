<?php
declare(strict_types=1);
const MUSICROAD_STATELESS_REQUEST = true;
require __DIR__ . '/bootstrap.php';
header('Content-Type: application/json; charset=utf-8');
if(!payment_enabled()){http_response_code(200);echo '{"ok":true,"disabled":true}';exit;}
$contentLength=(int)($_SERVER['CONTENT_LENGTH']??0);if($contentLength>262144){http_response_code(413);echo '{"ok":false,"error":"payload_too_large"}';exit;}
$raw=file_get_contents('php://input',false,null,0,262145)?:'';if(strlen($raw)>262144){http_response_code(413);echo '{"ok":false,"error":"payload_too_large"}';exit;}$payload=json_decode($raw,true);if(!is_array($payload))$payload=[];
$type=(string)($payload['type']??($_GET['type']??''));
$dataId=(string)($payload['data']['id']??($_GET['data_id']??$_GET['id']??''));
$eventId=mb_substr((string)($payload['id']??''),0,190);$type=mb_substr($type,0,100);$dataId=trim($dataId);if($dataId!==''&&!preg_match('/^\d{1,40}$/',$dataId)){http_response_code(400);echo '{"ok":false,"error":"invalid_resource_id"}';exit;}$signature=(string)($_SERVER['HTTP_X_SIGNATURE']??'');$requestId=mb_substr((string)($_SERVER['HTTP_X_REQUEST_ID']??''),0,190);
$secret=trim((string)app_setting('mercadopago_webhook_secret',''));$signatureValid=0;
if($secret!==''){
    $parts=[];foreach(explode(',',$signature) as $piece){$kv=explode('=',$piece,2);if(count($kv)===2)$parts[trim($kv[0])]=trim($kv[1]);}
    $ts=(string)($parts['ts']??'');$v1=(string)($parts['v1']??'');$manifest='';
    if($dataId!=='')$manifest.='id:'.strtolower($dataId).';';if($requestId!=='')$manifest.='request-id:'.$requestId.';';if($ts!=='')$manifest.='ts:'.$ts.';';
    $expected=hash_hmac('sha256',$manifest,$secret);$signatureValid=($v1!==''&&$manifest!==''&&hash_equals($expected,$v1))?1:0;
    if(!$signatureValid){http_response_code(401);echo '{"ok":false,"error":"invalid_signature"}';exit;}
    if(ctype_digit($ts)){ $stamp=(int)$ts; if($stamp>100000000000)$stamp=(int)floor($stamp/1000); if(abs(time()-$stamp)>900){http_response_code(401);echo '{"ok":false,"error":"stale_signature"}';exit;} }
}
$safePayload=json_encode(sanitize_payment_payload($payload),JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);
try{db()->prepare('INSERT IGNORE INTO payment_events(provider,event_id,event_type,resource_id,signature_valid,payload,processed_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)')->execute(['mercadopago',$eventId?:null,$type,$dataId?:null,$signatureValid,$safePayload]);}catch(Throwable $e){}
if($dataId===''){http_response_code(200);echo '{"ok":true,"ignored":true}';exit;}
if($type!==''&&$type!=='payment'&&!str_contains(strtolower((string)($payload['action']??'')),'payment')){http_response_code(200);echo '{"ok":true,"ignored":true}';exit;}
$result=mercadopago_request('GET','/v1/payments/'.rawurlencode($dataId));
if(!$result['ok']||!is_array($result['data'])){http_response_code(200);echo '{"ok":true,"deferred":true}';exit;}
try{$applied=apply_mercadopago_payment($result['data']);http_response_code(200);echo json_encode(['ok'=>true,'status'=>$applied['status']??'processed'],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);}catch(Throwable $e){http_response_code(500);echo '{"ok":false}';}
