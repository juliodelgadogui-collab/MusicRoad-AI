<?php
require __DIR__ . '/bootstrap.php';
require __DIR__ . '/drive_helpers.php';

$user = current_user();
$fileId = preg_replace('/[^A-Za-z0-9_-]/', '', (string)($_GET['id'] ?? ''));
$resourceKey = preg_replace('/[^A-Za-z0-9_-]/', '', (string)($_GET['resourcekey'] ?? ''));
if ($fileId === '') {
    http_response_code(422);
    header('Content-Type: text/plain; charset=utf-8');
    exit('Arquivo inválido.');
}

$knownMime = '';
$knownTitle = 'musica';
$libraryDriveTrack = false;
try {
    $stmt = db()->prepare('SELECT title, mime_type, origin FROM music_library WHERE origin_ref = ? LIMIT 1');
    $stmt->execute([$fileId]);
    $row = $stmt->fetch();
    if ($row) {
        $knownMime = trim((string)($row['mime_type'] ?? ''));
        $knownTitle = trim((string)($row['title'] ?? 'musica')) ?: 'musica';
        $libraryDriveTrack = str_contains(strtolower((string)($row['origin'] ?? '')), 'drive');
    }
} catch (Throwable $e) {}

// MediaPlayer/players do not always share the browser/session jar. An anonymous
// request is accepted only for a Drive ID that is already present in MusicRoad's
// library; arbitrary Google Drive IDs remain blocked.
if (!$user && !$libraryDriveTrack) {
    http_response_code(401);
    header('Content-Type: text/plain; charset=utf-8');
    header('Cache-Control: no-store');
    exit('Sessão expirada ou faixa não autorizada.');
}

$resolved = drive_resolve_public_download($fileId, $resourceKey);
if (isset($_GET['check'])) {
    json_response([
        'ok'=>(bool)($resolved['ok'] ?? false),
        'status_code'=>(int)($resolved['status_code'] ?? 0),
        'content_type'=>(string)($resolved['content_type'] ?? ''),
        'cached'=>!empty($resolved['cached']),
        'resourcekey'=>$resourceKey !== '',
        'message'=>(string)($resolved['message'] ?? ''),
    ], !empty($resolved['ok']) ? 200 : 502);
}

if (empty($resolved['ok']) || empty($resolved['url'])) {
    http_response_code(502);
    header('Content-Type: text/plain; charset=utf-8');
    header('Cache-Control: no-store');
    echo $resolved['message'] ?? 'Não foi possível localizar o áudio público no Google Drive.';
    exit;
}

if (!function_exists('curl_init')) {
    header('Location: ' . $resolved['url']);
    exit;
}
if (session_status() === PHP_SESSION_ACTIVE) session_write_close();
stream_remote_audio((string)$resolved['url'], $knownMime, $knownTitle, !empty($_GET['download']));

function stream_remote_audio(string $url, string $knownMime, string $knownTitle, bool $download): void
{
    $requestHeaders = [
        'User-Agent: Mozilla/5.0 MusicRoadAI/2.2',
        'Accept: audio/*,application/octet-stream;q=0.9,*/*;q=0.8',
        'Accept-Encoding: identity',
    ];
    if (!empty($_SERVER['HTTP_RANGE'])) $requestHeaders[] = 'Range: ' . $_SERVER['HTTP_RANGE'];

    $finalHeaders = [];
    $sentHeaders = false;
    $blockedHtml = false;
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_RETURNTRANSFER => false,
        CURLOPT_CONNECTTIMEOUT => 8,
        CURLOPT_TIMEOUT => 0,
        CURLOPT_ENCODING => 'identity',
        CURLOPT_HTTPHEADER => $requestHeaders,
        CURLOPT_HEADERFUNCTION => static function ($curl, string $line) use (&$finalHeaders): int {
            $trimmed = trim($line);
            if (str_starts_with($trimmed, 'HTTP/')) {
                $finalHeaders = [];
            } elseif (str_contains($trimmed, ':')) {
                [$name, $value] = array_map('trim', explode(':', $trimmed, 2));
                $finalHeaders[strtolower($name)] = $value;
            }
            return strlen($line);
        },
        CURLOPT_WRITEFUNCTION => static function ($curl, string $chunk) use (&$finalHeaders, &$sentHeaders, &$blockedHtml, $knownMime, $knownTitle, $download): int {
            if (!$sentHeaders) {
                $status = (int)curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
                $remoteType = strtolower((string)($finalHeaders['content-type'] ?? curl_getinfo($curl, CURLINFO_CONTENT_TYPE) ?? ''));
                if ($remoteType !== '' && !drive_is_binary_content_type($remoteType)) {
                    $blockedHtml = true;
                    http_response_code(502);
                    header('Content-Type: text/plain; charset=utf-8');
                    header('Cache-Control: no-store');
                    echo 'O Google Drive devolveu uma página em vez do arquivo de áudio.';
                    $sentHeaders = true;
                    return 0;
                }
                http_response_code($status ?: 200);
                $mime = $remoteType;
                if ($knownMime !== '' && ($mime === '' || str_starts_with($mime, 'application/octet-stream'))) $mime = $knownMime;
                if ($mime === '') $mime = 'application/octet-stream';
                header('Content-Type: ' . $mime);
                header('Accept-Ranges: bytes');
                header('Cache-Control: private, max-age=3600');
                foreach (['content-length','content-range','etag','last-modified'] as $name) {
                    if (!empty($finalHeaders[$name])) header(ucwords($name, '-') . ': ' . $finalHeaders[$name]);
                }
                $safeTitle = preg_replace('/[^\pL\pN _.-]+/u', '', $knownTitle) ?: 'musica';
                header('Content-Disposition: ' . ($download ? 'attachment' : 'inline') . '; filename="' . addslashes($safeTitle) . '"');
                $sentHeaders = true;
            }
            echo $chunk;
            flush();
            return strlen($chunk);
        },
    ]);
    if ($_SERVER['REQUEST_METHOD'] === 'HEAD') curl_setopt($ch, CURLOPT_NOBODY, true);
    $ok = curl_exec($ch);
    $errno = curl_errno($ch);
    $error = curl_error($ch);
    $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    $contentType = (string)curl_getinfo($ch, CURLINFO_CONTENT_TYPE);
    if ($_SERVER['REQUEST_METHOD'] === 'HEAD' && !headers_sent()) {
        http_response_code($status ?: 200);
        header('Content-Type: ' . ($knownMime !== '' ? $knownMime : ($contentType ?: 'application/octet-stream')));
        header('Accept-Ranges: bytes');
        foreach (['content-length','content-range','etag','last-modified'] as $name) {
            if (!empty($finalHeaders[$name])) header(ucwords($name, '-') . ': ' . $finalHeaders[$name]);
        }
    }
    curl_close($ch);
    if ($blockedHtml) exit;
    if ($ok === false && $errno !== CURLE_WRITE_ERROR && !headers_sent()) {
        http_response_code(502);
        header('Content-Type: text/plain; charset=utf-8');
        echo 'Falha ao transmitir o áudio do Google Drive: ' . $error;
    }
}
