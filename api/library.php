<?php
declare(strict_types=1);
require_once __DIR__.'/bootstrap.php';
require_once __DIR__.'/native_auth.php';
require_once __DIR__.'/native_library_sync.php';

$native = trim((string)($_SERVER['HTTP_X_MUSICROAD_NATIVE'] ?? '')) === '1';
$user = $native ? native_restore_user_from_request() : current_user();
if (!$user) {
    if ($native) json_response(['ok'=>false,'error'=>'Sessão expirada. O EstradaPlay tentará reconhecer este aparelho novamente.'],401);
    $user = require_login();
}

$action=(string)($_GET['action']??'list');
if($action==='list'){
    $sync=null;
    if($native || (string)($_GET['refresh']??'')==='1'){
        try{ $sync=native_library_sync_active_drive_folders(); }
        catch(Throwable $e){ $sync=['errors'=>[$e->getMessage()]]; }
    }
    $s=db()->query('SELECT * FROM music_library ORDER BY title ASC LIMIT 5000');
    $rows=$s->fetchAll()?:[];
    foreach($rows as &$r){
        $r['id']=(string)$r['id'];
        $origin=strtolower((string)($r['origin']??''));
        $ref=trim((string)($r['origin_ref']??''));
        $source=trim((string)($r['source_url']??''));
        if($source===''){
            if($ref!=='' && (str_contains($origin,'drive') || preg_match('/^[A-Za-z0-9_-]{20,}$/',$ref))){
                $source='api/drive_stream.php?id='.rawurlencode($ref);
            } elseif($ref!=='' && preg_match('~^https?://~i',$ref)){
                $source=$ref;
            }
        }
        $r['source']=$source;
        $r['content_uri']=$source;
        $r['duration_ms']=((int)($r['duration']??0))*1000;
        $r['file_size']=(int)($r['file_size']??0);
        $folder=trim((string)($r['folder']??''));
        if($folder==='' && str_contains($origin,'drive')) $folder=trim((string)($r['artist']??''));
        $r['folder']=$folder;
        $r['folder_path']=$folder;
    }
    unset($r);
    json_response(['ok'=>true,'tracks'=>$rows,'sync'=>$sync,'csrf'=>csrf_token()]);
}
if($action==='sync'){
    if(!$native) json_response(['ok'=>false,'error'=>'A sincronização pelo aplicativo exige cliente nativo.'],403);
    try{ $sync=native_library_sync_active_drive_folders(); json_response(['ok'=>true,'sync'=>$sync,'csrf'=>csrf_token()]); }
    catch(Throwable $e){ json_response(['ok'=>false,'error'=>'Falha ao sincronizar o Google Drive.','detail'=>$e->getMessage()],502); }
}
json_response(['ok'=>false,'error'=>'Ação inválida.'],400);
