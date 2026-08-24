<?php
declare(strict_types=1);
require __DIR__.'/bootstrap.php';
$user=require_login();
$action=(string)($_GET['action']??'list');

if($action==='list'){
    $s=db()->query('SELECT * FROM music_library ORDER BY title ASC LIMIT 2000');
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
    json_response(['ok'=>true,'tracks'=>$rows,'csrf'=>csrf_token()]);
}
json_response(['ok'=>false,'error'=>'Ação inválida.'],400);
