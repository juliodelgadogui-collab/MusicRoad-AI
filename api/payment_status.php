<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
$user=require_client();
require_session_rate_limit('payment-status',30,60);
$orderId=(int)($_GET['order_id']??0);if($orderId<=0)json_response(['ok'=>false,'error'=>'Pedido inválido.'],422);
$st=db()->prepare('SELECT * FROM payment_orders WHERE id=? AND user_id=? LIMIT 1');$st->execute([$orderId,(int)$user['id']]);$order=$st->fetch();
if(!$order)json_response(['ok'=>false,'error'=>'Pedido não encontrado.'],404);
session_write_close();
$status=(string)$order['status'];
if(in_array($status,['pending','in_process','created','creating'],true)&&!empty($order['provider_payment_id'])&&payment_enabled()){
    $r=mercadopago_request('GET','/v1/payments/'.rawurlencode((string)$order['provider_payment_id']));
    if($r['ok']&&is_array($r['data'])){try{$applied=apply_mercadopago_payment($r['data']);$status=(string)($applied['status']??$status);}catch(Throwable $e){}}
}
json_response(['ok'=>true,'status'=>$status,'approved'=>$status==='approved','has_access'=>user_has_access($user)]);
