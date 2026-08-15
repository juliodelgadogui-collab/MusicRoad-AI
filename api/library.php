<?php
require __DIR__ . '/bootstrap.php';
ensure_schema();
$user = require_login();
$method = $_SERVER['REQUEST_METHOD'];
$action = (string)($_GET['action'] ?? 'list');
if ($method !== 'GET') require_csrf();

if ($action === 'list') {
    $q = trim((string)($_GET['q'] ?? ''));
    $params = [(int)$user['id']];
    $where = '';
    if ($q !== '') {
        $where = 'WHERE (m.title LIKE ? OR m.artist LIKE ? OR m.album LIKE ?)';
        $like = "%$q%";
        $params[] = $like; $params[] = $like; $params[] = $like;
    }
    $sql = "SELECT m.*, COALESCE(s.is_favorite,0) is_favorite, COALESCE(s.play_count,0) play_count,
                   COALESCE(s.skip_count,0) skip_count, s.last_played_at
            FROM music_library m
            LEFT JOIN user_music_state s ON s.music_id=m.id AND s.user_id=?
            $where
            ORDER BY CASE WHEN s.last_played_at IS NULL THEN 1 ELSE 0 END, s.last_played_at DESC, m.title ASC
            LIMIT 2000";
    $stmt = db()->prepare($sql);
    $stmt->execute($params);
    json_response(['ok'=>true,'tracks'=>$stmt->fetchAll(),'csrf'=>csrf_token()]);
}

if ($action === 'played') {
    $data = input_json();
    $musicId = (int)($data['id'] ?? 0);
    if ($musicId <= 0) json_response(['ok'=>false,'error'=>'Música inválida.'],422);
    $stmt = db()->prepare("INSERT INTO user_music_state (user_id,music_id,is_favorite,play_count,skip_count,last_played_at)
                           VALUES (?,?,0,1,0,datetime('now'))
                           ON CONFLICT(user_id,music_id) DO UPDATE SET play_count=play_count+1,last_played_at=datetime('now')");
    $stmt->execute([(int)$user['id'],$musicId]);
    json_response(['ok'=>true]);
}

if ($action === 'favorite') {
    $data = input_json();
    $musicId = (int)($data['id'] ?? 0);
    if ($musicId <= 0) json_response(['ok'=>false,'error'=>'Música inválida.'],422);
    $stmt = db()->prepare('SELECT is_favorite FROM user_music_state WHERE user_id=? AND music_id=?');
    $stmt->execute([(int)$user['id'],$musicId]);
    $current = $stmt->fetchColumn();
    $new = ((int)$current === 1) ? 0 : 1;
    $up = db()->prepare("INSERT INTO user_music_state (user_id,music_id,is_favorite,play_count,skip_count,last_played_at)
                         VALUES (?,?,?,0,0,NULL)
                         ON CONFLICT(user_id,music_id) DO UPDATE SET is_favorite=excluded.is_favorite");
    $up->execute([(int)$user['id'],$musicId,$new]);
    json_response(['ok'=>true,'is_favorite'=>$new]);
}

if ($action === 'add' && ($user['role'] ?? '') === 'admin') {
    $data = input_json();
    $stmt = db()->prepare("INSERT INTO music_library (title,artist,album,genre,year,duration,cover_url,origin,origin_ref,mime_type,file_size,created_at)
                           VALUES (?,?,?,?,?,?,?,?,?,?,?,datetime('now'))");
    $stmt->execute([
        trim((string)($data['title'] ?? 'Sem título')), $data['artist'] ?? null, $data['album'] ?? null,
        $data['genre'] ?? null, $data['year'] ?? null, $data['duration'] ?? null, $data['cover_url'] ?? null,
        $data['origin'] ?? 'Servidor', $data['origin_ref'] ?? null, $data['mime_type'] ?? null, $data['file_size'] ?? null
    ]);
    json_response(['ok'=>true,'id'=>(int)db()->lastInsertId()]);
}

json_response(['ok'=>false,'error'=>'Ação inválida.'],400);
