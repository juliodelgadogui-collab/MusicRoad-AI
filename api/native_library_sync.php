<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';

function native_library_sync_active_drive_folders(bool $force = false, array $onlyIds = []): array
{
    return server_sync_active_drive_folders($force, $onlyIds);
}

function native_library_scan_folder(string $folderId, array $path, string $apiKey, array &$visited, array &$stats, int $depth): void
{
    if ($folderId === '' || $depth > 12 || isset($visited[$folderId])) return;
    $visited[$folderId] = true;
    $stats['folders']++;
    $entries = $apiKey !== '' ? native_library_list_api($folderId, $apiKey) : [];
    if (!$entries) $entries = native_library_list_public($folderId);
    if (!$entries) throw new RuntimeException('não foi possível listar '.implode(' / ', $path));

    foreach ($entries as $entry) {
        if (($entry['kind'] ?? '') === 'folder') {
            $childPath = array_merge($path, [(string)($entry['name'] ?: 'Subpasta')]);
            try {
                native_library_scan_folder((string)$entry['id'], $childPath, $apiKey, $visited, $stats, $depth + 1);
            } catch (Throwable $e) {
                $stats['errors'][] = implode(' / ', $childPath).': '.$e->getMessage();
            }
            continue;
        }
        $name = trim((string)($entry['name'] ?? ''));
        $mime = trim((string)($entry['mime_type'] ?? ''));
        if (!native_library_is_audio($name, $mime)) continue;
        native_library_save_track($entry, $path, $stats);
        if ($stats['tracks'] >= 20000) return;
    }
}

function native_library_list_api(string $folderId, string $apiKey): array
{
    $out = [];
    $pageToken = null;
    do {
        $q = "'".str_replace("'", "\\'", $folderId)."' in parents and trashed = false";
        $params = [
            'key'=>$apiKey,
            'q'=>$q,
            'pageSize'=>1000,
            'fields'=>'nextPageToken,files(id,name,mimeType,size,thumbnailLink)',
            'supportsAllDrives'=>'true',
            'includeItemsFromAllDrives'=>'true',
        ];
        if ($pageToken) $params['pageToken'] = $pageToken;
        $j = http_json('https://www.googleapis.com/drive/v3/files?'.http_build_query($params));
        if (!$j) return [];
        foreach (($j['files'] ?? []) as $f) {
            $mime = (string)($f['mimeType'] ?? '');
            $out[] = [
                'kind'=>$mime === 'application/vnd.google-apps.folder' ? 'folder' : 'file',
                'id'=>(string)($f['id'] ?? ''),
                'name'=>(string)($f['name'] ?? ''),
                'mime_type'=>$mime,
                'file_size'=>(int)($f['size'] ?? 0),
                'cover_url'=>(string)($f['thumbnailLink'] ?? ''),
            ];
        }
        $pageToken = $j['nextPageToken'] ?? null;
    } while ($pageToken);
    return native_library_unique_entries($out);
}

function native_library_list_public(string $folderId): array
{
    foreach ([
        'https://drive.google.com/embeddedfolderview?id='.rawurlencode($folderId).'#list',
        'https://drive.google.com/drive/folders/'.rawurlencode($folderId).'?usp=sharing',
    ] as $url) {
        $html = native_library_http_text($url);
        if (!$html) continue;
        $decoded = html_entity_decode($html, ENT_QUOTES | ENT_HTML5, 'UTF-8');
        $out = [];
        preg_match_all('~href="(?:https://drive\\.google\\.com)?/drive/folders/([A-Za-z0-9_-]+)[^"]*"[^>]*>(.*?)</a>~is', $decoded, $fm, PREG_SET_ORDER);
        foreach ($fm as $m) if ($m[1] !== $folderId) $out[] = ['kind'=>'folder','id'=>$m[1],'name'=>native_library_clean_name($m[2]) ?: 'Subpasta'];
        preg_match_all('~href="(?:https://drive\\.google\\.com)?/file/d/([A-Za-z0-9_-]+)[^"]*"[^>]*>(.*?)</a>~is', $decoded, $files, PREG_SET_ORDER);
        foreach ($files as $m) { $name=native_library_clean_name($m[2]); if ($name!=='') $out[]=['kind'=>'file','id'=>$m[1],'name'=>$name,'mime_type'=>native_library_mime($name),'file_size'=>0,'cover_url'=>'']; }
        preg_match_all('~href="(?:https://drive\\.google\\.com)?/(?:open|uc)\\?[^\"]*id=([A-Za-z0-9_-]+)[^\"]*"[^>]*>(.*?)</a>~is', $decoded, $om, PREG_SET_ORDER);
        foreach ($om as $m) { $name=native_library_clean_name($m[2]); if (native_library_is_audio($name,'')) $out[]=['kind'=>'file','id'=>$m[1],'name'=>$name,'mime_type'=>native_library_mime($name),'file_size'=>0,'cover_url'=>'']; }
        preg_match_all('~data-id="([A-Za-z0-9_-]{20,})"[^>]+aria-label="([^"]+\\.(?:mp3|aac|m4a|ogg|oga|opus|flac|wav|wave))"~is', $decoded, $dm, PREG_SET_ORDER);
        foreach ($dm as $m) $out[]=['kind'=>'file','id'=>$m[1],'name'=>html_entity_decode($m[2],ENT_QUOTES|ENT_HTML5,'UTF-8'),'mime_type'=>native_library_mime($m[2]),'file_size'=>0,'cover_url'=>''];
        preg_match_all('~aria-label="([^"]+\\.(?:mp3|aac|m4a|ogg|oga|opus|flac|wav|wave))"[^>]+data-id="([A-Za-z0-9_-]{20,})"~is', $decoded, $am, PREG_SET_ORDER);
        foreach ($am as $m) $out[]=['kind'=>'file','id'=>$m[2],'name'=>html_entity_decode($m[1],ENT_QUOTES|ENT_HTML5,'UTF-8'),'mime_type'=>native_library_mime($m[1]),'file_size'=>0,'cover_url'=>''];
        preg_match_all('~\\["([A-Za-z0-9_-]{20,})"\\s*,\\s*"([^"]+\\.(?:mp3|aac|m4a|ogg|oga|opus|flac|wav|wave))"~i', $decoded, $jm, PREG_SET_ORDER);
        foreach ($jm as $m) $out[]=['kind'=>'file','id'=>$m[1],'name'=>stripcslashes($m[2]),'mime_type'=>native_library_mime($m[2]),'file_size'=>0,'cover_url'=>''];
        preg_match_all('~"([A-Za-z0-9_-]{20,})"[^\"]{0,900}"([^"]+\\.(?:mp3|aac|m4a|ogg|oga|opus|flac|wav|wave))"~i', $decoded, $lm, PREG_SET_ORDER);
        foreach ($lm as $m) $out[]=['kind'=>'file','id'=>$m[1],'name'=>stripcslashes($m[2]),'mime_type'=>native_library_mime($m[2]),'file_size'=>0,'cover_url'=>''];
        preg_match_all('~\\["([A-Za-z0-9_-]{20,})"\\s*,\\s*"([^"]+)".{0,1200}?application/vnd\\.google-apps\\.folder~is', $decoded, $jf, PREG_SET_ORDER);
        foreach ($jf as $m) if ($m[1] !== $folderId) $out[]=['kind'=>'folder','id'=>$m[1],'name'=>native_library_clean_name(stripcslashes($m[2])) ?: 'Subpasta'];
        $out = native_library_unique_entries($out);
        if ($out) return $out;
    }
    return [];
}

function native_library_http_text(string $url): ?string
{
    $headers = ['User-Agent: Mozilla/5.0 EstradaPlay/2.0.8','Accept: text/html,application/xhtml+xml,*/*;q=0.8','Accept-Language: pt-BR,pt;q=0.9'];
    if (function_exists('curl_init')) {
        $ch = curl_init($url);
        curl_setopt_array($ch,[CURLOPT_RETURNTRANSFER=>true,CURLOPT_FOLLOWLOCATION=>true,CURLOPT_CONNECTTIMEOUT=>4,CURLOPT_TIMEOUT=>10,CURLOPT_HTTPHEADER=>$headers,CURLOPT_ENCODING=>'']);
        $body = curl_exec($ch); $status = (int)curl_getinfo($ch,CURLINFO_RESPONSE_CODE); curl_close($ch);
        return $body && $status < 400 ? (string)$body : null;
    }
    $ctx = stream_context_create(['http'=>['header'=>implode("\r\n",$headers),'timeout'=>10]]);
    $body = @file_get_contents($url,false,$ctx);
    return $body ? (string)$body : null;
}

function native_library_clean_name(string $value): string
{
    $value = strip_tags($value);
    $value = html_entity_decode($value, ENT_QUOTES | ENT_HTML5, 'UTF-8');
    return trim((string)preg_replace('/\\s+/', ' ', $value));
}

function native_library_unique_entries(array $entries): array
{
    $seen = []; $out = [];
    foreach ($entries as $e) {
        $id = (string)($e['id'] ?? ''); $kind = (string)($e['kind'] ?? '');
        if ($id === '' || $kind === '') continue;
        $name = trim((string)($e['name'] ?? ''));
        if ($kind === 'folder' && ($name === '' || preg_match('~(?:^https?://|(?:^|\.)google(?:usercontent)?\.com$|clients[0-9]*\.google\.com|appsgrowthpromo|googleapis\.com)~i', $name))) continue;
        $key = $kind.':'.$id;
        if (isset($seen[$key])) continue;
        $seen[$key] = true; $out[] = $e;
    }
    return $out;
}

function native_library_is_audio(string $name, string $mime): bool
{
    if (str_starts_with(strtolower($mime), 'audio/')) return true;
    return (bool)preg_match('/\\.(mp3|aac|m4a|ogg|oga|opus|flac|wav|wave)$/i', $name);
}

function native_library_mime(string $name): string
{
    return match (strtolower(pathinfo($name, PATHINFO_EXTENSION))) {
        'mp3'=>'audio/mpeg','m4a'=>'audio/mp4','aac'=>'audio/aac','ogg','oga'=>'audio/ogg','opus'=>'audio/opus','flac'=>'audio/flac','wav','wave'=>'audio/wav',default=>''
    };
}

function native_library_save_track(array $file, array $path, array &$stats): void
{
    $fileId = trim((string)($file['id'] ?? ''));
    if ($fileId === '') return;
    $name = trim((string)($file['name'] ?? '')) ?: $fileId;
    $title = preg_replace('/\.[^.]+$/', '', $name) ?: $name;
    $folderPath = $path ? implode(' / ', $path) : 'Google Drive';
    $album = $path ? (string)end($path) : 'Google Drive';
    $mime = trim((string)($file['mime_type'] ?? '')) ?: native_library_mime($name);
    $size = (int)($file['file_size'] ?? 0);
    $cover = trim((string)($file['cover_url'] ?? ''));
    $rootId = (int)($GLOBALS['estradaplay_sync_root_id'] ?? 0);
    $token = (string)($GLOBALS['estradaplay_sync_token'] ?? '');
    $now = date('Y-m-d H:i:s');
    $source = 'api/drive_stream.php?id=' . rawurlencode($fileId);
    $q = db()->prepare('SELECT id FROM music_library WHERE origin=? AND origin_ref=? LIMIT 1');
    $q->execute(['Google Drive',$fileId]);
    $id = (int)($q->fetchColumn() ?: 0);
    if ($id > 0) {
        $u = db()->prepare('UPDATE music_library SET title=?,artist=?,album=?,folder=?,mime_type=?,file_size=?,cover_url=?,source_url=?,drive_root_id=?,sync_token=?,last_seen_at=? WHERE id=?');
        $u->execute([$title,$folderPath,$album,$folderPath,$mime,$size ?: null,$cover ?: null,$source,$rootId ?: null,$token ?: null,$now,$id]);
        $stats['updated']++;
    } else {
        $i = db()->prepare('INSERT INTO music_library (title,artist,album,folder,cover_url,origin,origin_ref,source_url,mime_type,file_size,drive_root_id,sync_token,last_seen_at,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)');
        $i->execute([$title,$folderPath,$album,$folderPath,$cover ?: null,'Google Drive',$fileId,$source,$mime,$size ?: null,$rootId ?: null,$token ?: null,$now,$now]);
        $stats['inserted']++;
    }
    $stats['tracks']++;
}
