<?php
require __DIR__ . '/bootstrap.php';
ensure_schema();
$user = require_client();
if ($_SERVER['REQUEST_METHOD'] !== 'POST') json_response(['ok'=>false,'error'=>'Use POST.'],405);
require_csrf();
$data = input_json();
$prompt = mb_strtolower(trim((string)($data['prompt'] ?? '')));
$minutes = preg_match('/(\d+)\s*h/', $prompt, $m) ? ((int)$m[1]*60) : 180;

$base = "SELECT m.*,COALESCE(s.is_favorite,0) is_favorite,COALESCE(s.play_count,0) play_count,s.last_played_at
         FROM music_library m LEFT JOIN user_music_state s ON s.music_id=m.id AND s.user_id=?";

if (str_contains($prompt,'repetida') || str_contains($prompt,'duplicada')) {
    $stmt=db()->prepare("SELECT lower(m.title) titulo,lower(COALESCE(m.artist,'')) artista,COUNT(*) total FROM music_library m GROUP BY lower(m.title),lower(COALESCE(m.artist,'')) HAVING COUNT(*)>1 ORDER BY total DESC");
    $stmt->execute();
    json_response(['ok'=>true,'type'=>'duplicates','items'=>$stmt->fetchAll(),'message'=>'Possíveis duplicadas na biblioteca do servidor.']);
}

$params=[(int)$user['id']];
$where=[];
foreach (['sertanejo','forró','forro','romântica','romantica','viagem','festa','relaxar','gospel','funk','pagode','rock'] as $word) {
    if (str_contains($prompt,$word)) {
        $where[]="(lower(m.title) LIKE ? OR lower(m.artist) LIKE ? OR lower(m.album) LIKE ? OR lower(m.genre) LIKE ?)";
        $k='%'.str_replace('forro','forró',$word).'%';
        array_push($params,$k,$k,$k,$k);
    }
}
$sql=$base;
if($where) $sql.=' WHERE '.implode(' OR ',$where);
if(str_contains($prompt,'esquecida')||str_contains($prompt,'nao escuto')||str_contains($prompt,'não escuto')) $sql.=' ORDER BY CASE WHEN s.last_played_at IS NULL THEN 0 ELSE 1 END,s.last_played_at ASC';
else $sql.=' ORDER BY COALESCE(s.is_favorite,0) DESC,COALESCE(s.play_count,0) DESC,m.title ASC';
$sql.=' LIMIT 150';
$stmt=db()->prepare($sql);$stmt->execute($params);
json_response(['ok'=>true,'type'=>'playlist','name'=>'Smart Mix','target_minutes'=>$minutes,'message'=>'Mix criado com a biblioteca do servidor e o histórico do cliente.','tracks'=>$stmt->fetchAll()]);
