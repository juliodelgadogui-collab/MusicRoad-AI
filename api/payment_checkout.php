<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
$user = require_client();
if ($_SERVER['REQUEST_METHOD'] !== 'POST') json_response(['ok'=>false,'error'=>'Método inválido.'],405);
require_csrf();
require_session_rate_limit('payment-checkout-create',6,600);
if (!payment_enabled() || app_setting('license_purchase_enabled','0') !== '1') {
    json_response(['ok'=>false,'error'=>'Compra de licença está desativada no momento.'],503);
}
$data = input_json();
$planId = (int)($data['plan_id'] ?? 0);
$stmt = db()->prepare('SELECT * FROM plans WHERE id=? AND active=1 LIMIT 1');
$stmt->execute([$planId]);
$plan = $stmt->fetch();
if (!$plan) json_response(['ok'=>false,'error'=>'Plano não encontrado.'],404);
if ((int)$plan['price_cents'] <= 0) json_response(['ok'=>false,'error'=>'Plano sem preço configurado.'],422);

global $config;
$appUrl = rtrim((string)($config['app_url'] ?? ''),'/');
if (!str_starts_with($appUrl,'https://')) json_response(['ok'=>false,'error'=>'Mercado Pago exige URL HTTPS em produção.'],422);
$external = 'MR-' . date('YmdHis') . '-' . bin2hex(random_bytes(6));
$order = db()->prepare("INSERT INTO payment_orders(user_id,plan_id,provider,external_reference,status,amount_cents,created_at,updated_at) VALUES(?,?,'mercadopago',?,'creating',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
$order->execute([(int)$user['id'],$planId,$external,(int)$plan['price_cents']]);
$orderId=(int)db()->lastInsertId();
$payload=[
  'items'=>[[
    'id'=>(string)$plan['code'],
    'title'=>'Licença MusicRoad - '.(string)$plan['name'],
    'description'=>(int)$plan['duration_days'].' dias de acesso ao MusicRoad',
    'quantity'=>1,
    'currency_id'=>'BRL',
    'unit_price'=>round(((int)$plan['price_cents'])/100,2),
  ]],
  'payer'=>['email'=>(string)$user['email']],
  'external_reference'=>$external,
  'back_urls'=>[
    'success'=>$appUrl.'/license.php?payment=success',
    'pending'=>$appUrl.'/license.php?payment=pending',
    'failure'=>$appUrl.'/license.php?payment=failure',
  ],
  'auto_return'=>'approved',
  'notification_url'=>$appUrl.'/api/mercadopago_webhook.php',
];
session_write_close();
$result=mercadopago_request('POST','/checkout/preferences',$payload);
if(!$result['ok'] || !is_array($result['data'])){
    $failure=['status'=>$result['status']??0,'error'=>$result['error']??null,'response'=>is_array($result['data']??null)?sanitize_payment_payload($result['data']):null];
    db()->prepare("UPDATE payment_orders SET status='error',raw_payload=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")->execute([json_encode($failure,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES),$orderId]);
    audit_log('payment.preference.error',['order_id'=>$orderId,'status'=>$result['status']]);
    json_response(['ok'=>false,'error'=>'Não foi possível iniciar o pagamento.'],502);
}
$pref=$result['data'];
$checkout=(string)($pref['init_point'] ?? '');
if($checkout==='') json_response(['ok'=>false,'error'=>'Mercado Pago não retornou a URL de checkout.'],502);
db()->prepare("UPDATE payment_orders SET status='pending',provider_preference_id=?,checkout_url=?,raw_payload=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")
  ->execute([(string)($pref['id']??''),$checkout,json_encode(sanitize_payment_payload($pref),JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES),$orderId]);
audit_log('payment.preference.created',['order_id'=>$orderId,'plan_id'=>$planId]);
json_response(['ok'=>true,'checkout_url'=>$checkout,'order_id'=>$orderId]);
