<?php
declare(strict_types=1);
require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_library_sync.php';
require_once __DIR__ . '/server_intelligent.php';

$allowed = PHP_SAPI === 'cli';
if (!$allowed) {
    $given = trim((string)($_GET['key'] ?? ''));
    $allowed = $given !== '' && hash_equals(server_cron_key(), $given);
}
if (!$allowed) json_response(['ok'=>false,'error'=>'Chave de cron inválida.'],403);

try {
    $sync = native_library_sync_active_drive_folders(false, [], 'cron');
    $housekeeping = intelligent_server_housekeeping(false);
    server_housekeeping(false);
    if (PHP_SAPI === 'cli') {
        echo json_encode(['ok'=>true,'sync'=>$sync,'housekeeping'=>$housekeeping], JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES) . PHP_EOL;
        exit;
    }
    json_response(['ok'=>true,'sync'=>$sync,'housekeeping'=>$housekeeping]);
} catch (Throwable $e) {
    if (PHP_SAPI === 'cli') {
        fwrite(STDERR, $e->getMessage() . PHP_EOL);
        exit(1);
    }
    json_response(['ok'=>false,'error'=>'Falha na sincronização automática.'],500);
}
