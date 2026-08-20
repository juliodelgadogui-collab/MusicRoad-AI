<?php
require __DIR__ . '/bootstrap.php';
require __DIR__ . '/drive_helpers.php';
$user = require_admin();

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    json_response(['ok' => false, 'error' => 'Use POST.'], 405);
}

require_csrf();
require_session_rate_limit('google-drive-public-import',10,3600);
session_write_close();
ensure_runtime_tables();

$data = input_json();
$folders = resolve_requested_folders($data);
$files = resolve_requested_files($data);
$save = !array_key_exists('save', $data) || (bool)$data['save'];
if (!$folders && !$files) {
    json_response(['ok' => false, 'error' => 'Cadastre uma pasta pública do Google Drive, cole links no processador ou informe um link para importar.'], 422);
}

$total = empty_stats();
$allTracks = [];
foreach ($folders as $folder) {
    $stats = empty_stats();
    $tracks = [];
    $visited = [];
    scan_drive_folder_public_or_api($folder['folder_id'], [$folder['name'] ?: 'Google Drive'], google_api_key(), $stats, $tracks, 0, $visited, $save, $folder['folder_link'] ?? null);
    $status = $stats['tracks_found'] > 0 ? 'OK: ' . $stats['tracks_found'] . ' músicas' : 'Sem músicas encontradas';
    if ($stats['errors']) {
        $status .= ' | ' . implode(' | ', array_slice($stats['errors'], 0, 2));
    }
    update_drive_folder_status($folder['folder_id'], $status);
    if ($save && !empty($folder['folder_link'])) {
        save_drive_folder_reference($folder, $status);
    }
    merge_stats($total, $stats);
    $allTracks = array_merge($allTracks, $tracks);
}
foreach ($files as $file) {
    $stats = empty_stats();
    $track = process_drive_file($file, ['Links avulsos'], $stats, $save);
    merge_stats($total, $stats);
    if ($track) {
        $allTracks[] = $track;
    }
}

audit_log('google_drive.public_import', ['folders' => count($folders), 'files' => count($files), 'save' => $save, 'stats' => $total]);
json_response(['ok' => true, 'stats' => $total, 'tracks' => $allTracks]);

function resolve_requested_folders(array $data): array
{
    if (!empty($data['links'])) {
        $folders = [];
        foreach (split_drive_links((string)$data['links']) as $link) {
            $folderId = drive_folder_id_from_link($link);
            if ($folderId) {
                $folders[] = ['name' => 'Link processado', 'folder_id' => $folderId, 'folder_link' => $link];
            }
        }
        return $folders;
    }
    if (!empty($data['import_all'])) {
        $rows = db()->query('SELECT * FROM drive_folders WHERE active = 1 ORDER BY id ASC')->fetchAll();
        return array_map(fn($r) => [
            'name' => (string)($r['name'] ?: 'Google Drive'),
            'folder_id' => (string)$r['folder_id'],
            'folder_link' => (string)$r['folder_link'],
        ], $rows);
    }
    if (!empty($data['folder_db_id'])) {
        $stmt = db()->prepare('SELECT * FROM drive_folders WHERE id = ? LIMIT 1');
        $stmt->execute([(int)$data['folder_db_id']]);
        $row = $stmt->fetch();
        if ($row) {
            return [[
                'name' => (string)($row['name'] ?: 'Google Drive'),
                'folder_id' => (string)$row['folder_id'],
                'folder_link' => (string)$row['folder_link'],
            ]];
        }
    }
    $folderLink = trim((string)($data['folder_link'] ?? ''));
    $folderId = drive_folder_id_from_link($folderLink);
    if (!$folderId) {
        return [];
    }
    return [[
        'name' => trim((string)($data['folder_name'] ?? 'Google Drive')),
        'folder_id' => $folderId,
        'folder_link' => $folderLink,
    ]];
}

function resolve_requested_files(array $data): array
{
    $files = [];
    if (!empty($data['links'])) {
        foreach (split_drive_links((string)$data['links']) as $link) {
            if (drive_folder_id_from_link($link)) {
                continue;
            }
            $fileId = drive_file_id_from_link($link);
            if ($fileId) {
                $files[] = ['kind' => 'file', 'id' => $fileId, 'name' => '', 'mime_type' => '', 'source_link' => $link];
            }
        }
    }
    $folderLink = trim((string)($data['folder_link'] ?? ''));
    if ($folderLink && !drive_folder_id_from_link($folderLink)) {
        $fileId = drive_file_id_from_link($folderLink);
        if ($fileId) {
            $files[] = ['kind' => 'file', 'id' => $fileId, 'name' => '', 'mime_type' => '', 'source_link' => $folderLink];
        }
    }
    return $files;
}

function split_drive_links(string $value): array
{
    $lines = preg_split('/\R+/', $value) ?: [];
    $links = [];
    foreach ($lines as $line) {
        $line = trim($line);
        if ($line !== '') {
            $links[] = $line;
        }
    }
    return array_values(array_unique($links));
}

function empty_stats(): array
{
    return [
        'folders_scanned' => 0,
        'tracks_found' => 0,
        'inserted' => 0,
        'updated' => 0,
        'skipped' => 0,
        'errors' => [],
        'verified' => 0,
        'saved' => 0,
    ];
}

function merge_stats(array &$total, array $stats): void
{
    foreach (['folders_scanned', 'tracks_found', 'inserted', 'updated', 'skipped', 'verified', 'saved'] as $key) {
        $total[$key] += (int)$stats[$key];
    }
    $total['errors'] = array_merge($total['errors'], $stats['errors']);
}

function update_drive_folder_status(string $folderId, string $status): void
{
    $stmt = db()->prepare('UPDATE drive_folders SET last_import_at = CURRENT_TIMESTAMP, last_status = ? WHERE folder_id = ?');
    $stmt->execute([$status, $folderId]);
}

function save_drive_folder_reference(array $folder, string $status): void
{
    $folderId = (string)($folder['folder_id'] ?? '');
    $folderLink = (string)($folder['folder_link'] ?? '');
    if ($folderId === '' || $folderLink === '') {
        return;
    }
    $name = trim((string)($folder['name'] ?? '')) ?: 'Google Drive';
    $exists = db()->prepare('SELECT id FROM drive_folders WHERE folder_id = ? LIMIT 1');
    $exists->execute([$folderId]);
    if ($exists->fetchColumn()) {
        $stmt = db()->prepare('UPDATE drive_folders SET name = ?, folder_link = ?, active = 1, last_import_at = CURRENT_TIMESTAMP, last_status = ? WHERE folder_id = ?');
        $stmt->execute([$name, $folderLink, $status, $folderId]);
        return;
    }
    $stmt = db()->prepare('INSERT INTO drive_folders (name, folder_id, folder_link, active, last_import_at, last_status, created_at) VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP)');
    $stmt->execute([$name, $folderId, $folderLink, $status]);
}

function scan_drive_folder_public_or_api(string $folderId, array $path, string $apiKey, array &$stats, array &$tracks, int $depth, array &$visited, bool $save, ?string $sourceLink = null): void
{
    if ($depth > 12) {
        $stats['errors'][] = 'Limite de subpastas atingido em ' . implode('/', $path);
        return;
    }
    if (isset($visited[$folderId])) {
        return;
    }
    $visited[$folderId] = true;
    $stats['folders_scanned']++;

    $entries = [];
    if ($apiKey !== '') {
        $entries = list_drive_folder_by_api($folderId, $apiKey);
    }
    if (!$entries) {
        $entries = list_drive_folder_public($folderId);
    }
    if (!$entries) {
        $stats['errors'][] = 'Não consegui listar a pasta pública: ' . implode('/', $path) . '. Verifique se está como "qualquer pessoa com o link pode ver".';
        return;
    }

    foreach ($entries as $entry) {
        if (($entry['kind'] ?? '') === 'folder') {
            scan_drive_folder_public_or_api($entry['id'], array_merge($path, [$entry['name'] ?: 'Subpasta']), $apiKey, $stats, $tracks, $depth + 1, $visited, $save, $sourceLink);
            continue;
        }
        if ($stats['tracks_found'] >= 3000) {
            $stats['errors'][] = 'Limite de 3000 músicas por importação atingido.';
            return;
        }
        $entry['source_link'] = $sourceLink;
        $track = process_drive_file($entry, $path, $stats, $save);
        if ($track) {
            $tracks[] = $track;
        }
    }
}

function list_drive_folder_by_api(string $folderId, string $apiKey): array
{
    $entries = [];
    $pageToken = null;
    do {
        $q = "'" . str_replace("'", "\\'", $folderId) . "' in parents and trashed = false";
        $params = [
            'key' => $apiKey,
            'q' => $q,
            'pageSize' => 1000,
            'fields' => 'nextPageToken,files(id,name,mimeType,size,thumbnailLink)',
            'supportsAllDrives' => 'true',
            'includeItemsFromAllDrives' => 'true',
        ];
        if ($pageToken) {
            $params['pageToken'] = $pageToken;
        }
        $response = http_json('https://www.googleapis.com/drive/v3/files?' . http_build_query($params));
        if (!$response) {
            return [];
        }
        foreach (($response['files'] ?? []) as $file) {
            $mime = (string)($file['mimeType'] ?? '');
            $entries[] = [
                'kind' => $mime === 'application/vnd.google-apps.folder' ? 'folder' : 'file',
                'id' => (string)$file['id'],
                'name' => (string)$file['name'],
                'mime_type' => $mime,
                'file_size' => $file['size'] ?? null,
                'cover_url' => $file['thumbnailLink'] ?? null,
            ];
        }
        $pageToken = $response['nextPageToken'] ?? null;
    } while ($pageToken);
    return unique_drive_entries($entries);
}

function list_drive_folder_public(string $folderId): array
{
    $entries = [];
    $urls = [
        'https://drive.google.com/embeddedfolderview?id=' . rawurlencode($folderId) . '#list',
        'https://drive.google.com/drive/folders/' . rawurlencode($folderId) . '?usp=sharing',
    ];
    foreach ($urls as $url) {
        $html = drive_http_text($url);
        if (!$html) {
            continue;
        }
        $entries = array_merge($entries, parse_public_drive_html($html, $folderId));
    }
    return unique_drive_entries($entries);
}

function drive_http_text(string $url): ?string
{
    $headers = [
        'User-Agent: Mozilla/5.0 MusicRoadAI/1.0',
        'Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        'Accept-Language: pt-BR,pt;q=0.9,en;q=0.8',
    ];
    if (function_exists('curl_init')) {
        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_CONNECTTIMEOUT => 10,
            CURLOPT_TIMEOUT => 30,
            CURLOPT_HTTPHEADER => $headers,
            CURLOPT_ENCODING => '',
        ]);
        $body = curl_exec($ch);
        $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
        curl_close($ch);
        return $body && $status < 400 ? (string)$body : null;
    }
    $context = stream_context_create(['http' => ['header' => implode("\r\n", $headers), 'timeout' => 30]]);
    $body = @file_get_contents($url, false, $context);
    return $body ? (string)$body : null;
}

function parse_public_drive_html(string $html, string $currentFolderId): array
{
    $entries = [];
    $decoded = html_entity_decode($html, ENT_QUOTES | ENT_HTML5, 'UTF-8');

    preg_match_all('~href="(?:https://drive\.google\.com)?/drive/folders/([A-Za-z0-9_-]+)[^"]*"[^>]*>(.*?)</a>~is', $decoded, $folderMatches, PREG_SET_ORDER);
    foreach ($folderMatches as $m) {
        if ($m[1] === $currentFolderId) {
            continue;
        }
        $entries[] = ['kind' => 'folder', 'id' => $m[1], 'name' => clean_drive_name($m[2]) ?: 'Subpasta'];
    }

    preg_match_all('~href="(?:https://drive\.google\.com)?/file/d/([A-Za-z0-9_-]+)[^"]*"[^>]*>(.*?)</a>~is', $decoded, $fileMatches, PREG_SET_ORDER);
    foreach ($fileMatches as $m) {
        $name = clean_drive_name($m[2]);
        if ($name !== '') {
            $entries[] = drive_file_entry($m[1], $name);
        }
    }

    preg_match_all('~href="(?:https://drive\.google\.com)?/(?:open|uc)\?[^"]*id=([A-Za-z0-9_-]+)[^"]*"[^>]*>(.*?)</a>~is', $decoded, $openMatches, PREG_SET_ORDER);
    foreach ($openMatches as $m) {
        $name = clean_drive_name($m[2]);
        if (is_audio_drive_file($name, '')) {
            $entries[] = drive_file_entry($m[1], $name);
        }
    }

    preg_match_all('~data-id="([A-Za-z0-9_-]{20,})"[^>]+aria-label="([^"]+\.(?:mp3|aac|m4a|ogg|oga|opus|flac|wav|wave))"~is', $decoded, $dataIdMatches, PREG_SET_ORDER);
    foreach ($dataIdMatches as $m) {
        $entries[] = drive_file_entry($m[1], decode_drive_string($m[2]));
    }

    preg_match_all('~aria-label="([^"]+\.(?:mp3|aac|m4a|ogg|oga|opus|flac|wav|wave))"[^>]+data-id="([A-Za-z0-9_-]{20,})"~is', $decoded, $ariaMatches, PREG_SET_ORDER);
    foreach ($ariaMatches as $m) {
        $entries[] = drive_file_entry($m[2], decode_drive_string($m[1]));
    }

    preg_match_all('~\["([A-Za-z0-9_-]{20,})"\s*,\s*"([^"]+\.(?:mp3|aac|m4a|ogg|oga|opus|flac|wav|wave))"~i', $decoded, $jsonFileMatches, PREG_SET_ORDER);
    foreach ($jsonFileMatches as $m) {
        $entries[] = drive_file_entry($m[1], decode_drive_string($m[2]));
    }

    preg_match_all('~"([A-Za-z0-9_-]{20,})"[^"]{0,900}"([^"]+\.(?:mp3|aac|m4a|ogg|oga|opus|flac|wav|wave))"~i', $decoded, $looseFileMatches, PREG_SET_ORDER);
    foreach ($looseFileMatches as $m) {
        $entries[] = drive_file_entry($m[1], decode_drive_string($m[2]));
    }

    preg_match_all('~\["([A-Za-z0-9_-]{20,})"\s*,\s*"([^"]+)".{0,1200}?application/vnd\.google-apps\.folder~is', $decoded, $jsonFolderMatches, PREG_SET_ORDER);
    foreach ($jsonFolderMatches as $m) {
        if ($m[1] !== $currentFolderId) {
            $entries[] = ['kind' => 'folder', 'id' => $m[1], 'name' => clean_drive_name(decode_drive_string($m[2])) ?: 'Subpasta'];
        }
    }

    return $entries;
}

function clean_drive_name(string $value): string
{
    $value = preg_replace('~<script\b[^>]*>.*?</script>~is', '', $value);
    $value = preg_replace('~<style\b[^>]*>.*?</style>~is', '', $value);
    $value = strip_tags((string)$value);
    $value = html_entity_decode($value, ENT_QUOTES | ENT_HTML5, 'UTF-8');
    return trim(preg_replace('/\s+/', ' ', $value));
}

function decode_drive_string(string $value): string
{
    $decoded = json_decode('"' . str_replace('"', '\"', $value) . '"');
    return is_string($decoded) ? $decoded : stripcslashes($value);
}

function drive_file_entry(string $id, string $name): array
{
    return [
        'kind' => 'file',
        'id' => $id,
        'name' => $name,
        'mime_type' => mime_from_audio_name($name),
        'file_size' => null,
        'cover_url' => null,
    ];
}

function mime_from_audio_name(string $name): string
{
    $ext = strtolower(pathinfo($name, PATHINFO_EXTENSION));
    return match ($ext) {
        'mp3' => 'audio/mpeg',
        'm4a' => 'audio/mp4',
        'aac' => 'audio/aac',
        'ogg', 'oga' => 'audio/ogg',
        'opus' => 'audio/opus',
        'flac' => 'audio/flac',
        'wav', 'wave' => 'audio/wav',
        default => '',
    };
}

function unique_drive_entries(array $entries): array
{
    $seen = [];
    $unique = [];
    foreach ($entries as $entry) {
        if (empty($entry['id']) || empty($entry['kind'])) {
            continue;
        }
        $key = $entry['kind'] . ':' . $entry['id'];
        if (isset($seen[$key])) {
            continue;
        }
        $seen[$key] = true;
        $unique[] = $entry;
    }
    return $unique;
}

function is_audio_drive_file(string $name, string $mime): bool
{
    if (str_starts_with($mime, 'audio/')) {
        return true;
    }
    return (bool)preg_match('/\.(mp3|aac|m4a|ogg|oga|opus|flac|wav|wave)$/i', $name);
}

function process_drive_file(array $entry, array $path, array &$stats, bool $save): ?array
{
    $entry = hydrate_drive_file_metadata($entry);
    $verification = verify_drive_file($entry);
    if ($verification['status'] === 'IGNORADO') {
        $stats['skipped']++;
        record_drive_link_check($entry, $verification, null);
        return [
            'id' => null,
            'title' => $entry['name'] ?: 'Arquivo sem nome',
            'artist' => implode(' / ', $path),
            'album' => end($path) ?: 'Google Drive',
            'origin' => 'Google Drive',
            'origin_ref' => $entry['id'],
            'mime_type' => $entry['mime_type'] ?? null,
            'file_size' => $entry['file_size'] ?? null,
            'saved_action' => 'skipped',
            'check_status' => $verification['status'],
            'check_message' => $verification['message'],
        ];
    }
    if ($verification['status'] === 'ERRO') {
        $stats['errors'][] = $verification['message'];
        record_drive_link_check($entry, $verification, null);
        return [
            'id' => null,
            'title' => $entry['name'] ?: 'Arquivo com erro',
            'artist' => implode(' / ', $path),
            'album' => end($path) ?: 'Google Drive',
            'origin' => 'Google Drive',
            'origin_ref' => $entry['id'],
            'mime_type' => $entry['mime_type'] ?? null,
            'file_size' => $entry['file_size'] ?? null,
            'saved_action' => 'error',
            'check_status' => $verification['status'],
            'check_message' => $verification['message'],
        ];
    }

    $stats['verified']++;
    $stats['tracks_found']++;
    if (!$save) {
        record_drive_link_check($entry, $verification, null);
        return [
            'id' => null,
            'title' => preg_replace('/\.[^.]+$/', '', (string)$entry['name']) ?: (string)$entry['name'],
            'artist' => implode(' / ', $path),
            'album' => end($path) ?: 'Google Drive',
            'origin' => 'Google Drive',
            'origin_ref' => $entry['id'],
            'mime_type' => $entry['mime_type'] ?? null,
            'file_size' => $entry['file_size'] ?? null,
            'saved_action' => 'verified',
            'check_status' => $verification['status'],
            'check_message' => $verification['message'],
        ];
    }

    $track = save_drive_track_from_entry($entry, $path);
    $track['check_status'] = $verification['status'];
    $track['check_message'] = $verification['message'];
    $stats[$track['saved_action']]++;
    $stats['saved']++;
    record_drive_link_check($entry, $verification, $track['id']);
    return $track;
}

function hydrate_drive_file_metadata(array $entry): array
{
    $entry['name'] = trim((string)($entry['name'] ?? ''));
    $entry['mime_type'] = (string)($entry['mime_type'] ?? '');
    $apiKey = google_api_key();
    if ($apiKey !== '') {
        $response = http_json('https://www.googleapis.com/drive/v3/files/' . rawurlencode((string)$entry['id']) . '?' . http_build_query([
            'key' => $apiKey,
            'fields' => 'id,name,mimeType,size,thumbnailLink',
            'supportsAllDrives' => 'true',
        ]));
        if ($response) {
            $entry['name'] = $entry['name'] ?: (string)($response['name'] ?? '');
            $entry['mime_type'] = $entry['mime_type'] ?: (string)($response['mimeType'] ?? '');
            $entry['file_size'] = $entry['file_size'] ?? ($response['size'] ?? null);
            $entry['cover_url'] = $entry['cover_url'] ?? ($response['thumbnailLink'] ?? null);
        }
    }
    if ($entry['name'] === '') {
        $html = drive_http_text('https://drive.google.com/file/d/' . rawurlencode((string)$entry['id']) . '/view');
        if ($html) {
            if (preg_match('~<title>(.*?)</title>~is', $html, $m)) {
                $entry['name'] = preg_replace('/\s*-\s*Google Drive\s*$/i', '', clean_drive_name($m[1]));
            }
            if ($entry['name'] === '' && preg_match('~itemprop="name"\s+content="([^"]+)"~i', $html, $m)) {
                $entry['name'] = decode_drive_string($m[1]);
            }
        }
    }
    if ($entry['mime_type'] === '' && $entry['name'] !== '') {
        $entry['mime_type'] = mime_from_audio_name($entry['name']);
    }
    return $entry;
}

function verify_drive_file(array $entry): array
{
    $name = (string)($entry['name'] ?? '');
    $mime = (string)($entry['mime_type'] ?? '');
    if (!is_audio_drive_file($name, $mime)) {
        return ['status' => 'IGNORADO', 'message' => 'Arquivo não parece ser música: ' . ($name ?: (string)$entry['id'])];
    }

    $resolved = drive_resolve_public_download((string)$entry['id']);
    if (!empty($resolved['ok'])) {
        $type = trim((string)($resolved['content_type'] ?? ''));
        $message = 'Áudio público verificado e pronto para streaming';
        if ($type !== '') {
            $message .= ' (' . $type . ')';
        }
        return ['status' => 'OK', 'message' => $message . '.'];
    }

    return [
        'status' => 'ERRO',
        'message' => ($resolved['message'] ?? 'Não foi possível obter o arquivo de áudio público.') . ' Arquivo: ' . ($name ?: (string)$entry['id']),
    ];
}

function drive_http_status(string $url): array
{
    if (function_exists('curl_init')) {
        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_NOBODY => true,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_CONNECTTIMEOUT => 8,
            CURLOPT_TIMEOUT => 15,
            CURLOPT_HTTPHEADER => ['User-Agent: Mozilla/5.0 MusicRoadAI/1.0'],
        ]);
        curl_exec($ch);
        $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
        $contentType = (string)curl_getinfo($ch, CURLINFO_CONTENT_TYPE);
        curl_close($ch);
        return ['status_code' => $status, 'content_type' => $contentType];
    }
    $headers = @get_headers($url, true);
    if (!$headers || !isset($headers[0])) {
        return ['status_code' => 0, 'content_type' => ''];
    }
    preg_match('/\s(\d{3})\s/', (string)$headers[0], $m);
    return ['status_code' => isset($m[1]) ? (int)$m[1] : 0, 'content_type' => (string)($headers['Content-Type'] ?? '')];
}

function record_drive_link_check(array $entry, array $verification, ?int $musicId): void
{
    try {
        $stmt = db()->prepare('INSERT INTO drive_link_checks (source_link, file_id, title, mime_type, status, message, saved_music_id, checked_at) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)');
        $stmt->execute([
            $entry['source_link'] ?? null,
            $entry['id'] ?? null,
            $entry['name'] ?? null,
            $entry['mime_type'] ?? null,
            $verification['status'],
            $verification['message'],
            $musicId,
        ]);
    } catch (Throwable $e) {
        // O log de verificação não deve impedir a importação.
    }
}

function save_drive_track_from_entry(array $file, array $path): array
{
    $name = (string)$file['name'];
    $title = preg_replace('/\.[^.]+$/', '', $name) ?: $name;
    $album = $path ? end($path) : 'Google Drive';
    $folderPath = $path ? implode(' / ', $path) : 'Pasta pública';
    $fileId = (string)$file['id'];
    $stmt = db()->prepare('SELECT id FROM music_library WHERE origin = ? AND origin_ref = ? LIMIT 1');
    $stmt->execute(['Google Drive', $fileId]);
    $existingId = $stmt->fetchColumn();
    if ($existingId) {
        $update = db()->prepare('UPDATE music_library SET title = ?, album = ?, artist = ?, mime_type = ?, file_size = ?, cover_url = ? WHERE id = ?');
        $update->execute([$title, $album, $folderPath, $file['mime_type'] ?? null, $file['file_size'] ?? null, $file['cover_url'] ?? null, $existingId]);
        $id = (int)$existingId;
        $action = 'updated';
    } else {
        $insert = db()->prepare('INSERT INTO music_library (title, artist, album, cover_url, origin, origin_ref, mime_type, file_size, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)');
        $insert->execute([$title, $folderPath, $album, $file['cover_url'] ?? null, 'Google Drive', $fileId, $file['mime_type'] ?? null, $file['file_size'] ?? null]);
        $id = (int)db()->lastInsertId();
        $action = 'inserted';
    }
    return [
        'id' => $id,
        'title' => $title,
        'artist' => $folderPath,
        'album' => $album,
        'origin' => 'Google Drive',
        'origin_ref' => $fileId,
        'mime_type' => $file['mime_type'] ?? null,
        'file_size' => $file['file_size'] ?? null,
        'saved_action' => $action,
    ];
}
