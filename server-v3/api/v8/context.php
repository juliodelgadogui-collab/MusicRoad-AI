<?php
declare(strict_types=1);
require_once __DIR__ . '/../../bootstrap.php';
require_once dirname(__DIR__,3) . '/api/server_context_v7.php';
$user = v3_require_user_json();
$body = v3_input();
try {
    $context = ep7_build_context($body, '');
    $context['ok'] = true;
    $context['server_v3'] = EPC_V3_VERSION;
    $context['client'] = 'web';
    $context['account'] = v3_account($user);
    v3_json($context);
} catch (InvalidArgumentException $e) {
    v3_json(['ok'=>false,'error'=>$e->getMessage()],422);
} catch (Throwable $e) {
    error_log('EPC V3 CONTEXT: '.$e->getMessage());
    v3_json(['ok'=>false,'error'=>'Não foi possível montar o contexto da viagem agora.'],503);
}
