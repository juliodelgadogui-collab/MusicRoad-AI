<?php
declare(strict_types=1);
if(session_status()!==PHP_SESSION_ACTIVE){session_name('MEDICALSESSID');session_start(['cookie_httponly'=>true,'cookie_samesite'=>'Lax','cookie_secure'=>!empty($_SERVER['HTTPS'])&&$_SERVER['HTTPS']!=='off']);}
$config=file_exists(__DIR__.'/config.php')?require __DIR__.'/config.php':require __DIR__.'/config.example.php';
@mkdir(dirname($config['db_path']),0700,true); @mkdir($config['storage_path'],0700,true);
@mkdir($config['storage_path'].'/documents',0700,true); @mkdir($config['storage_path'].'/signatures',0700,true); @mkdir($config['storage_path'].'/exams',0700,true);
$db=new PDO('sqlite:'.$config['db_path']);$db->setAttribute(PDO::ATTR_ERRMODE,PDO::ERRMODE_EXCEPTION);$db->exec('PRAGMA foreign_keys=ON');$db->exec('PRAGMA journal_mode=WAL');
$schema=file_get_contents(__DIR__.'/database/schema.sql');$db->exec($schema);
function h($v):string{return htmlspecialchars((string)$v,ENT_QUOTES,'UTF-8');}
function csrf():string{if(empty($_SESSION['csrf']))$_SESSION['csrf']=bin2hex(random_bytes(24));return $_SESSION['csrf'];}
function verify_csrf():void{if(!hash_equals($_SESSION['csrf']??'',$_POST['csrf']??'')){http_response_code(419);exit('Sessão expirada.');}}
function auth():?array{return $_SESSION['medical_user']??null;}
function require_auth():array{if(!auth()){header('Location: index.php');exit;}return auth();}
function audit(PDO $db,?int $uid,string $acao,?string $ent=null,?int $eid=null,?int $pid=null,?string $det=null):void{$s=$db->prepare('INSERT INTO auditoria(usuario_id,acao,entidade,entidade_id,paciente_id,detalhes,ip) VALUES(?,?,?,?,?,?,?)');$s->execute([$uid,$acao,$ent,$eid,$pid,$det,$_SERVER['REMOTE_ADDR']??null]);}
function token():string{return bin2hex(random_bytes(20));}
function next_doc_number(PDO $db):string{$y=date('Y');$n=(int)$db->query("SELECT COUNT(*) FROM documentos WHERE numero LIKE 'MED-$y-%'")->fetchColumn()+1;return sprintf('MED-%s-%06d',$y,$n);}
function redirect(string $u):never{header('Location: '.$u);exit;}
