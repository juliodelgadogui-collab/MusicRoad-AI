<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';
require_once __DIR__ . '/server_context_v7.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

$body=input_json();
$user=native_require_json_user($body);
$device=native_device_token_from_request($body);

try {
    $context=ep7_build_context($body,$device);
    $context['ok']=true;
    $context['account_user_id']=(int)($user['id']??0);
    json_response($context);
} catch (InvalidArgumentException $e) {
    json_response(['ok'=>false,'error'=>$e->getMessage()],422);
} catch (Throwable $e) {
    error_log('ROUTE_CONTEXT_V7: '.$e->getMessage());
    json_response(['ok'=>false,'error'=>'Não foi possível montar o contexto da viagem agora.'],503);
}
