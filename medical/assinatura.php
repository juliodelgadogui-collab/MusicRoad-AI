<?php
require_once __DIR__.'/api/bootstrap.php';
$id=(int)($_GET['medico']??0);
if($id<1){http_response_code(404);exit;}
$q=db()->prepare('SELECT assinatura_path FROM medicos WHERE id=?');
$q->execute([$id]);
$m=$q->fetch();
if(!$m||empty($m['assinatura_path'])){http_response_code(404);exit;}
$file=__DIR__.'/'.$m['assinatura_path'];
if(!is_file($file)){http_response_code(404);exit;}
$mime=(new finfo(FILEINFO_MIME_TYPE))->file($file);
if(!in_array($mime,['image/png','image/jpeg','image/webp'],true)){http_response_code(404);exit;}
header('Content-Type: '.$mime);
header('Cache-Control: private, max-age=300');
readfile($file);
