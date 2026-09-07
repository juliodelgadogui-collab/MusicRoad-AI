<?php
declare(strict_types=1);
require_once __DIR__.'/bootstrap.php';
require_once __DIR__.'/native_auth.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: private, max-age=300');
if(!native_restore_user_from_request(null))json_response(['ok'=>false,'error'=>'Sessão expirada.'],401);

$style=trim((string)app_setting('map_style_url',''));
$own=$style!=='';
if($style===''||!preg_match('~^https://~i',$style))$style='https://tiles.openfreemap.org/styles/dark';
json_response(['ok'=>true,'style_url'=>$style,'own_style'=>$own,'version'=>'4.0.0']);
