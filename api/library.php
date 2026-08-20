<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';

$user = require_login();
$userId = (int)$user['id'];
$method = strtoupper((string)($_SERVER['REQUEST_METHOD'] ?? 'GET'));
$action = (string)($_GET['action'] ?? 'list');
if ($method !== 'GET') require_csrf();

if ($action === 'list') {
    if ($method !== 'GET') json_response(['ok'=>false,'error'=>'Use GET.'],405);
    require_session_rate_limit('library-read',120,60);$csrf=csrf_token();session_write_close();
    $q = mb_substr(trim((string)($_GET['q'] ?? '')),0,100);
    $sql = "SELECT m.id,m.user_id,m.title,m.artist,m.album,m.genre,m.year,m.duration,m.cover_url,m.origin,m.origin_ref,m.mime_type,m.file_size,
            COALESCE(s.is_favorite,0) AS is_favorite,COALESCE(s.play_count,m.play_count,0) AS play_count,
            COALESCE(s.skip_count,m.skip_count,0) AS skip_count,COALESCE(s.last_played_at,m.last_played_at) AS last_played_at,m.created_at
        FROM music_library m
        LEFT JOIN user_music_state s ON s.music_id=m.id AND s.user_id=:user_id
        WHERE (m.user_id IS NULL OR m.user_id=:owner_id)";
    if ($q !== '') $sql .= ' AND (m.title LIKE :q OR m.artist LIKE :q OR m.album LIKE :q)';
    $sql .= ' ORDER BY COALESCE(s.last_played_at,m.last_played_at) DESC,m.title ASC LIMIT 500';
    $stmt = db()->prepare($sql);
    $stmt->bindValue(':user_id',$userId,PDO::PARAM_INT);
    $stmt->bindValue(':owner_id',$userId,PDO::PARAM_INT);
    if ($q !== '') $stmt->bindValue(':q','%'.$q.'%');
    $stmt->execute();
    json_response(['ok'=>true,'tracks'=>$stmt->fetchAll(),'csrf'=>$csrf]);
}

if ($action === 'add') {
    if ($method !== 'POST') json_response(['ok'=>false,'error'=>'Use POST.'],405);
    require_session_rate_limit('library-add',30,3600);session_write_close();
    $data = input_json();
    $title = mb_substr(trim((string)($data['title'] ?? '')),0,300);
    if ($title === '') json_response(['ok'=>false,'error'=>'Informe o título.'],422);
    $origin = strtolower(trim((string)($data['origin'] ?? 'device')));
    if (!in_array($origin,['device','upload','manual'],true)) json_response(['ok'=>false,'error'=>'Origem inválida.'],422);
    $originRef = mb_substr(trim((string)($data['origin_ref'] ?? '')),0,255);
    $originRef = $originRef !== '' ? 'user-'.$userId.'-'.$originRef : null;
    $stmt = db()->prepare('INSERT INTO music_library (user_id,title,artist,album,genre,year,duration,cover_url,origin,origin_ref,mime_type,file_size,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)');
    try {
        $stmt->execute([
            $userId,$title,mb_substr(trim((string)($data['artist'] ?? '')),0,255) ?: null,
            mb_substr(trim((string)($data['album'] ?? '')),0,255) ?: null,mb_substr(trim((string)($data['genre'] ?? '')),0,120) ?: null,
            isset($data['year']) && is_numeric($data['year']) ? max(0,min(2200,(int)$data['year'])) : null,
            isset($data['duration']) && is_numeric($data['duration']) ? max(0,(int)$data['duration']) : null,
            null,$origin,$originRef,mb_substr(trim((string)($data['mime_type'] ?? '')),0,120) ?: null,
            isset($data['file_size']) && is_numeric($data['file_size']) ? max(0,(int)$data['file_size']) : null,
        ]);
    } catch (PDOException $e) {
        if ((string)$e->getCode() === '23000') json_response(['ok'=>false,'error'=>'Esta faixa já está cadastrada.'],409);
        throw $e;
    }
    $id = (int)db()->lastInsertId();
    audit_log('music.add',['id'=>$id]);
    json_response(['ok'=>true,'id'=>$id],201);
}

if ($action === 'played' || $action === 'favorite') {
    if ($method !== 'POST') json_response(['ok'=>false,'error'=>'Use POST.'],405);
    require_session_rate_limit($action==='played'?'library-played':'library-favorite',$action==='played'?240:60,60);session_write_close();
    $data = input_json();
    $musicId = (int)($data['id'] ?? 0);
    if ($musicId <= 0) json_response(['ok'=>false,'error'=>'Faixa inválida.'],422);
    $accessible = db()->prepare('SELECT id FROM music_library WHERE id=? AND (user_id IS NULL OR user_id=?) LIMIT 1');
    $accessible->execute([$musicId,$userId]);
    if (!$accessible->fetchColumn()) json_response(['ok'=>false,'error'=>'Faixa não encontrada.'],404);
    if ($action === 'played') {
        $stmt = db()->prepare('INSERT INTO user_music_state(user_id,music_id,is_favorite,play_count,skip_count,last_played_at) VALUES (?,?,0,1,0,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE play_count=play_count+1,last_played_at=CURRENT_TIMESTAMP');
        $stmt->execute([$userId,$musicId]);
        json_response(['ok'=>true]);
    }
    $stmt = db()->prepare('INSERT INTO user_music_state(user_id,music_id,is_favorite,play_count,skip_count,last_played_at) VALUES (?,?,1,0,0,NULL) ON DUPLICATE KEY UPDATE is_favorite=IF(is_favorite=1,0,1)');
    $stmt->execute([$userId,$musicId]);
    $read = db()->prepare('SELECT is_favorite FROM user_music_state WHERE user_id=? AND music_id=?');
    $read->execute([$userId,$musicId]);
    json_response(['ok'=>true,'is_favorite'=>(int)$read->fetchColumn()]);
}

json_response(['ok'=>false,'error'=>'Ação inválida.'],400);
