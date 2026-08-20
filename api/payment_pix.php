<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
$user=require_client();
if($_SERVER['REQUEST_METHOD']!=='POST')json_response(['ok'=>false,'error'=>'Método inválido.'],405);
require_csrf();
require_session_rate_limit('payment-pix-create',6,600);
if(!payment_enabled()||app_setting('license_purchase_enabled','0')!=='1')json_response(['ok'=>false,'error'=>'Pagamento PIX está desativado.'],503);
$data=input_json();$planId=(int)($data['plan_id']??0);$cpf=preg_replace('/\D+/','',(string)($data['cpf']??''))??'';
if(!valid_cpf($cpf))json_response(['ok'=>false,'error'=>'Informe um CPF válido para gerar o PIX.'],422);
$stmt=db()->prepare('SELECT * FROM plans WHERE id=? AND active=1 LIMIT 1');$stmt->execute([$planId]);$plan=$stmt->fetch();
if(!$plan)json_response(['ok'=>false,'error'=>'Plano não encontrado.'],404);
if((int)$plan['price_cents']<=0)json_response(['ok'=>false,'error'=>'Plano sem preço configurado.'],422);
global $config;$appUrl=rtrim((string)($config['app_url']??''),'/');
if(!str_starts_with($appUrl,'https://'))json_response(['ok'=>false,'error'=>'Mercado Pago PIX exige HTTPS em produção.'],422);
$external='MRPIX-'.date('YmdHis').'-'.bin2hex(random_bytes(6));
$order=db()->prepare("INSERT INTO payment_orders(user_id,plan_id,provider,external_reference,status,amount_cents,created_at,updated_at) VALUES(?,?,'mercadopago',?,'creating',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
$order->execute([(int)$user['id'],$planId,$external,(int)$plan['price_cents']]);$orderId=(int)db()->lastInsertId();
$parts=preg_split('/\s+/',trim((string)$user['name']))?:[];$first=$parts[0]??'Cliente';$last=count($parts)>1?implode(' ',array_slice($parts,1)):'MusicRoad';
$payload=[
 'transaction_amount'=>round(((int)$plan['price_cents'])/100,2),
 'description'=>'MusicRoad · '.(string)$plan['name'].' · '.(int)$plan['duration_days'].' dias',
 'payment_method_id'=>'pix',
 'external_reference'=>$external,
 'notification_url'=>$appUrl.'/api/mercadopago_webhook.php',
 'payer'=>['email'=>(string)$user['email'],'first_name'=>$first,'last_name'=>$last,'identification'=>['type'=>'CPF','number'=>$cpf]],
];
$idempotency='musicroad-pix-'.$orderId.'-'.bin2hex(random_bytes(8));
session_write_close();
$result=mercadopago_request('POST','/v1/payments',$payload,['X-Idempotency-Key: '.$idempotency]);
if(!$result['ok']||!is_array($result['data'])){
 db()->prepare("UPDATE payment_orders SET status='error',raw_payload=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")->execute([json_encode(['status'=>$result['status'],'error'=>$result['error'],'response'=>is_array($result['data'])?sanitize_payment_payload($result['data']):null],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES),$orderId]);
 audit_log('payment.pix.error',['order_id'=>$orderId,'http_status'=>$result['status']]);
 json_response(['ok'=>false,'error'=>'Não foi possível gerar o PIX. Confira as credenciais do Mercado Pago e tente novamente.','provider_status'=>$result['status']],502);
}
$payment=$result['data'];$safe=sanitize_payment_payload($payment);$status=(string)($payment['status']??'pending');$paymentId=(string)($payment['id']??'');
$tx=$payment['point_of_interaction']['transaction_data']??[];$qr=(string)($tx['qr_code']??'');$qr64=(string)($tx['qr_code_base64']??'');$ticket=(string)($tx['ticket_url']??'');
if($paymentId===''||($qr===''&&$qr64===''&&$ticket==='')){
 db()->prepare("UPDATE payment_orders SET status='error',provider_payment_id=?,raw_payload=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")->execute([$paymentId,json_encode($safe,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES),$orderId]);
 json_response(['ok'=>false,'error'=>'O Mercado Pago não retornou os dados do PIX.'],502);
}
db()->prepare('UPDATE payment_orders SET status=?,provider_payment_id=?,raw_payload=?,updated_at=CURRENT_TIMESTAMP WHERE id=?')->execute([$status,$paymentId,json_encode($safe,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES),$orderId]);
audit_log('payment.pix.created',['order_id'=>$orderId,'payment_id'=>$paymentId,'plan_id'=>$planId]);
json_response(['ok'=>true,'order_id'=>$orderId,'payment_id'=>$paymentId,'status'=>$status,'amount'=>round(((int)$plan['price_cents'])/100,2),'plan'=>(string)$plan['name'],'qr_code'=>$qr,'qr_code_base64'=>$qr64,'ticket_url'=>$ticket,'expires_at'=>(string)($payment['date_of_expiration']??'')]);
