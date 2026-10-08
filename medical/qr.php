<?php
require_once __DIR__.'/api/bootstrap.php';
require_once __DIR__.'/lib/qrcode.php';
$codigo=trim($_GET['id']??'');
if($codigo===''){http_response_code(400);exit;}
$base=(isset($_SERVER['HTTPS'])&&$_SERVER['HTTPS']!=='off'?'https':'http').'://'.$_SERVER['HTTP_HOST'];
$data=$base.'/medical/validar.php?id='.rawurlencode($codigo);
$generator=new QRCode($data,['s'=>'qr-m','sf'=>6,'p'=>4]);
$generator->output_image();
