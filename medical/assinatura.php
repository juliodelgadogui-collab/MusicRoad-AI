<?php
require_once __DIR__.'/api/bootstrap.php';
$id=(int)($_GET['medico']??0);if($id<1){http_response_code(404);exit;}
$campo=(($_GET['tipo']??'')==='logo')?'logo_path':'assinatura_path';
$q=db()->prepare("SELECT $campo AS arquivo FROM medicos WHERE id=?");$q->execute([$id]);$m=$q->fetch();
if(!$m||empty($m['arquivo'])){http_response_code(404);exit;}
$relative=ltrim(str_replace(['..','\\'],['','/'],$m['arquivo']),'/');$file=__DIR__.'/'.$relative;
if(!is_file($file)){http_response_code(404);exit;}
$mime=(new finfo(FILEINFO_MIME_TYPE))->file($file);if(!in_array($mime,['image/png','image/jpeg','image/webp'],true)){http_response_code(404);exit;}
header('Content-Type: '.$mime);header('Cache-Control: private, max-age=300');readfile($file);