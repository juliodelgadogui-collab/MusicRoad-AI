<?php
declare(strict_types=1);
require_once __DIR__ . '/../../bootstrap.php';
$user = v3_require_user_json();
try{
    $rows=db()->query('SELECT id,title,artist,album,genre,year,duration,cover_url,origin,origin_ref,mime_type,file_size FROM music_library ORDER BY title ASC LIMIT 3000')->fetchAll()?:[];
    $tracks=[];
    foreach($rows as $r){
        $origin=strtolower((string)($r['origin']??''));$ref=trim((string)($r['origin_ref']??''));$source='';
        if($ref!==''&&(str_contains($origin,'drive')||preg_match('/^[A-Za-z0-9_-]{20,}$/',$ref)))$source='/api/drive_stream.php?id='.rawurlencode($ref);
        elseif($ref!==''&&preg_match('~^https?://~i',$ref))$source=$ref;
        $tracks[]=[
            'id'=>(string)$r['id'],'title'=>(string)($r['title']??''),'artist'=>(string)($r['artist']??''),'album'=>(string)($r['album']??''),
            'genre'=>(string)($r['genre']??''),'year'=>$r['year']!==null?(int)$r['year']:null,'duration_s'=>(int)($r['duration']??0),
            'cover_url'=>(string)($r['cover_url']??''),'source'=>$source,'mime_type'=>(string)($r['mime_type']??''),'file_size'=>(int)($r['file_size']??0)
        ];
    }
    v3_json(['ok'=>true,'version'=>EPC_V3_VERSION,'tracks'=>$tracks,'count'=>count($tracks),'account'=>v3_account($user)]);
}catch(Throwable $e){
    error_log('EPC V3 LIBRARY: '.$e->getMessage());
    v3_json(['ok'=>false,'error'=>'Não foi possível carregar a biblioteca agora.'],503);
}
