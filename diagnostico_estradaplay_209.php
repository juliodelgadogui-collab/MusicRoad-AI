<?php
declare(strict_types=1);
require __DIR__ . '/api/bootstrap.php';
header('Content-Type: application/json; charset=utf-8');
$out=['diagnostic'=>'EstradaPlay-server-2.0.9','generated_at'=>gmdate('c'),'checks'=>[]];
$add=static function(string $name,bool $ok,$detail=null) use (&$out):void{$out['checks'][]=['name'=>$name,'ok'=>$ok,'detail'=>$detail];};
try{$driver=(string)db()->getAttribute(PDO::ATTR_DRIVER_NAME);$add('Banco',true,$driver);}catch(Throwable $e){$add('Banco',false,$e->getMessage());echo json_encode($out,JSON_UNESCAPED_UNICODE|JSON_PRETTY_PRINT);exit;}
try{$v=db()->query('SELECT CURRENT_TIMESTAMP')->fetchColumn();$add('CURRENT_TIMESTAMP',(bool)$v,$v);}catch(Throwable $e){$add('CURRENT_TIMESTAMP',false,$e->getMessage());}
foreach(['users','music_library','drive_folders','client_device_state','audit_logs'] as $t){try{$c=(int)db()->query('SELECT COUNT(*) FROM '.$t)->fetchColumn();$add('Tabela '.$t,true,$c);}catch(Throwable $e){$add('Tabela '.$t,false,$e->getMessage());}}
try{$rows=db()->query('SELECT id,title,artist,album,origin,origin_ref,source_url,mime_type,file_size,folder,duration FROM music_library ORDER BY id ASC LIMIT 500')->fetchAll()?:[];$bytes=strlen(json_encode(['ok'=>true,'tracks'=>$rows],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES));$add('Página de 500 faixas',count($rows)<=500,['tracks'=>count($rows),'json_bytes'=>$bytes]);}catch(Throwable $e){$add('Página de 500 faixas',false,$e->getMessage());}
$out['ok']=!array_filter($out['checks'],fn($c)=>!$c['ok']);echo json_encode($out,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES|JSON_PRETTY_PRINT);
