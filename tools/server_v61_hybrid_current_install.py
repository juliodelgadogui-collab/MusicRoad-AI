#!/usr/bin/env python3
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

p=ROOT/'api/server_intelligent.php'
s=p.read_text(encoding='utf-8')
s=s.replace("const ESTRADAPLAY_SERVER_INTELLIGENT_VERSION = '500MB-v6';","const ESTRADAPLAY_SERVER_INTELLIGENT_VERSION = '500MB-v6.1';",1)
p.write_text(s,encoding='utf-8')

p=ROOT/'install.php'
s=p.read_text(encoding='utf-8')
old="$root=__DIR__;$configFile=$root.'/config/config.php';$lockFile=$root.'/storage/install.lock';\nif(is_file($lockFile)&&is_file($configFile)){header('Location: login.php');exit;}\n"
new=r'''$root=__DIR__;$configFile=$root.'/config/config.php';$lockFile=$root.'/storage/install.lock';
// SERVER_V61_HYBRID: extracting over an existing EstradaPlay installation must never
// ask for a reinstall or overwrite its credentials. A healthy existing DB is adopted.
if(is_file($configFile)){
 try{
  $existing=require $configFile;
  $driver=(string)($existing['db']['driver']??'sqlite');
  if($driver==='mysql'){
   $pdo=new PDO((string)($existing['db']['mysql_dsn']??''),(string)($existing['db']['mysql_user']??''),(string)($existing['db']['mysql_pass']??''),[PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION]);
  }else{
   $path=(string)($existing['db']['sqlite_path']??($root.'/storage/db/estradaplay.sqlite'));
   $pdo=new PDO('sqlite:'.$path,null,null,[PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION]);
  }
  $pdo->query('SELECT 1');$pdo=null;
  if(!is_dir(dirname($lockFile)))@mkdir(dirname($lockFile),0775,true);
  if(!is_file($lockFile)){
   if(file_put_contents($lockFile,json_encode(['installed_at'=>date(DATE_ATOM),'version'=>'v6.1','mode'=>'existing-installation-adopted'],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES),LOCK_EX)===false)throw new RuntimeException('Não foi possível registrar a instalação existente em storage/.');
   @chmod($lockFile,0640);
  }
  header('Location: login.php');exit;
 }catch(Throwable $e){
  http_response_code(503);
  ?><!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>EstradaPlay · servidor existente</title><style>body{background:#080507;color:#f6eee0;font:16px system-ui;margin:0}.c{max-width:720px;margin:60px auto;padding:24px}.b{background:#160b0e;border:1px solid #8b2d39;border-radius:16px;padding:22px}.r{color:#ff8d98}</style></head><body><main class="c"><div class="b"><h1>Configuração existente preservada</h1><p>O EstradaPlay encontrou o config.php deste servidor e, por segurança, não vai reinstalar nem sobrescrever o banco.</p><p class="r">O banco configurado não respondeu. Corrija a conexão atual antes de continuar.</p></div></main></body></html><?php exit;
 }
}
'''
if 'SERVER_V61_HYBRID' not in s:
    if old not in s: raise SystemExit('install guard marker missing')
    s=s.replace(old,new,1)
p.write_text(s,encoding='utf-8')
print('Server v6.1 hybrid current-install protection applied')
