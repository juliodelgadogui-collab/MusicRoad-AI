<?php
declare(strict_types=1);
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
    try {
        $stmt = db()->prepare('SELECT title,mime_type,origin,source_url FROM music_library WHERE origin_ref=? LIMIT 1');
        $stmt->execute([$fileId]);
        $row = $stmt->fetch();
    } catch (Throwable $e) {
        $stmt = db()->prepare('SELECT title,mime_type,origin FROM music_library WHERE origin_ref=? LIMIT 1');
        $stmt->execute([$fileId]);
        $row = $stmt->fetch();
    }
    if ($row) {
        $knownMime = trim((string)($row['mime_type'] ?? ''));
        $knownTitle = trim((string)($row['title'] ?? 'musica')) ?: 'musica';
        $libraryDriveTrack = str_contains(strtolower((string)($row['origin'] ?? '')), 'drive');
        if ($resourceKey === '') {
            $stored = (string)($row['source_url'] ?? '');
            if ($stored !== '' && preg_match('/[?&]resourcekey=([A-Za-z0-9_-]+)/i', $stored, $m)) {
                $resourceKey = (string)$m[1];
            }
        }
    }
} catch (Throwable $e) {}

if (!$user && !$libraryDriveTrack) {
    http_response_code(401);
    header('Content-Type: text/plain; charset=utf-8');
    header('Cache-Control: no-store');
    exit('Sessão expirada ou faixa não autorizada.');
}

/**
 * IMPORTANT: do not resolve Google's redirects on the hosting server and hand
 * the final effective URL to Android. Some Google download URLs/tokens are tied
 * to the request/network that created them. The phone must follow the Google
 * redirects itself so the final media token is created for that phone.
 */
$clientUrl = 'https://drive.usercontent.google.com/download?id=' . rawurlencode($fileId)
    . '&export=download&authuser=0&confirm=t'
    . ($resourceKey !== '' ? '&resourcekey=' . rawurlencode($resourceKey) : '');
$alternateUrl = 'https://drive.google.com/uc?export=download&confirm=t&id=' . rawurlencode($fileId)
    . ($resourceKey !== '' ? '&resourcekey=' . rawurlencode($resourceKey) : '');

if (isset($_GET['resolve'])) {
    json_response([
        'ok' => true,
        'file_id' => $fileId,
        'direct_url' => $clientUrl,
        'alternate_url' => $alternateUrl,
        'content_type' => $knownMime,
        'cached' => false,
        'resourcekey' => $resourceKey !== '',
        'transport' => 'google_drive_client_redirect',
        'message' => 'O celular seguirá os redirecionamentos do Google Drive diretamente.',
    ]);
}

if (isset($_GET['check'])) {
    $resolved = drive_resolve_public_download($fileId, $resourceKey);
    json_response([
        'ok' => (bool)($resolved['ok'] ?? false),
        'status_code' => (int)($resolved['status_code'] ?? 0),
        'content_type' => (string)($resolved['content_type'] ?? $knownMime),
        'resourcekey' => $resourceKey !== '',
        'message' => (string)($resolved['message'] ?? ''),
    ], !empty($resolved['ok']) ? 200 : 502);
}

// Normal/legacy endpoint: redirect the media client to Google. No audio is
// stored or proxied through the MusicRoad hosting, so it does not consume the
// server's 500 MB storage and almost no MusicRoad bandwidth is used.
header('Cache-Control: private, max-age=60');
header('X-MusicRoad-Transport: google-drive-direct');
header('Location: ' . $clientUrl, true, 302);
exit;
