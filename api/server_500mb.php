<?php
declare(strict_types=1);

// SERVER_500MB_V1: metadata-only runtime for small shared hosting.
function server_ensure_500mb_schema(): void
{
    try {
        $driver = (string)db()->getAttribute(PDO::ATTR_DRIVER_NAME);
        if ($driver !== 'mysql') return;

        $cols = schema_columns('music_library');
        $defs = [
            'drive_root_id' => 'INT NULL',
            'sync_token' => 'VARCHAR(64) NULL',
            'last_seen_at' => 'DATETIME NULL',
        ];
        foreach ($defs as $name => $definition) {
            if (!in_array($name, $cols, true)) {
                db()->exec("ALTER TABLE music_library ADD COLUMN {$name} {$definition}");
            }
        }

        $folderCols = schema_columns('drive_folders');
        $folderDefs = [
            'last_sync_duration_ms' => 'INT NULL',
            'last_sync_tracks' => 'INT NOT NULL DEFAULT 0',
            'last_sync_removed' => 'INT NOT NULL DEFAULT 0',
            'last_sync_errors' => 'INT NOT NULL DEFAULT 0',
        ];
        foreach ($folderDefs as $name => $definition) {
            if (!in_array($name, $folderCols, true)) {
                db()->exec("ALTER TABLE drive_folders ADD COLUMN {$name} {$definition}");
            }
        }

        server_mysql_index('music_library', 'idx_music_drive_root', 'drive_root_id');
        server_mysql_index('music_library', 'idx_music_sync_token', 'sync_token');
        server_mysql_index('music_library', 'idx_music_last_seen', 'last_seen_at');
        server_mysql_index('drive_folders', 'idx_drive_active', 'active');
        server_mysql_index('client_device_state', 'idx_device_last_seen', 'last_seen_at');
        server_mysql_index('audit_logs', 'idx_audit_created', 'created_at');
    } catch (Throwable $e) {
        error_log('SERVER_500MB schema: ' . $e->getMessage());
    }
}

function server_mysql_index(string $table, string $index, string $column): void
{
    try {
        $s = db()->prepare('SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=?');
        $s->execute([$table, $index]);
        if ((int)$s->fetchColumn() === 0) {
            $safeTable = preg_replace('/[^A-Za-z0-9_]/', '', $table);
            $safeIndex = preg_replace('/[^A-Za-z0-9_]/', '', $index);
            $safeColumn = preg_replace('/[^A-Za-z0-9_]/', '', $column);
            db()->exec("CREATE INDEX {$safeIndex} ON {$safeTable} ({$safeColumn})");
        }
    } catch (Throwable $e) {}
}

function server_rotate_logs(): void
{
    $dir = dirname(__DIR__) . '/logs';
    if (!is_dir($dir)) return;
    $file = $dir . '/php-error.log';
    $max = 2 * 1024 * 1024;
    if (!is_file($file) || filesize($file) <= $max) return;
    @unlink($file . '.3');
    if (is_file($file . '.2')) @rename($file . '.2', $file . '.3');
    if (is_file($file . '.1')) @rename($file . '.1', $file . '.2');
    @rename($file, $file . '.1');
    @touch($file);
}

function server_housekeeping(): void
{
    static $ran = false;
    if ($ran) return;
    $ran = true;
    server_rotate_logs();
    try {
        $last = (int)app_setting('server_housekeeping_ts', '0');
        if ($last > 0 && time() - $last < 21600) return;
        $cut = date('Y-m-d H:i:s', time() - 30 * 86400);
        $s = db()->prepare('DELETE FROM audit_logs WHERE created_at < ?');
        $s->execute([$cut]);
        set_app_setting('server_housekeeping_ts', (string)time());
    } catch (Throwable $e) {
        error_log('SERVER_500MB housekeeping: ' . $e->getMessage());
    }
}

function server_cron_key(): string
{
    $key = trim((string)app_setting('server_cron_key', ''));
    if ($key !== '') return $key;
    $key = bin2hex(random_bytes(24));
    set_app_setting('server_cron_key', $key);
    return $key;
}

function server_health_snapshot(): array
{
    server_ensure_500mb_schema();
    $root = dirname(__DIR__);
    $total = @disk_total_space($root) ?: 0;
    $free = @disk_free_space($root) ?: 0;
    $dbBytes = 0;
    try {
        if ((string)db()->getAttribute(PDO::ATTR_DRIVER_NAME) === 'mysql') {
            $dbBytes = (int)db()->query('SELECT COALESCE(SUM(data_length+index_length),0) FROM information_schema.TABLES WHERE table_schema=DATABASE()')->fetchColumn();
        }
    } catch (Throwable $e) {}
    $logBytes = 0;
    foreach (glob($root . '/logs/*') ?: [] as $f) if (is_file($f)) $logBytes += (int)filesize($f);
    try { $tracks = (int)db()->query('SELECT COUNT(*) FROM music_library')->fetchColumn(); } catch (Throwable $e) { $tracks = 0; }
    try { $roots = (int)db()->query('SELECT COUNT(*) FROM drive_folders WHERE active=1')->fetchColumn(); } catch (Throwable $e) { $roots = 0; }
    $host = (string)($_SERVER['HTTP_HOST'] ?? 'seu-dominio');
    $scheme = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') ? 'https' : 'http';
    $cron = $scheme . '://' . $host . '/api/cron_drive_sync.php?key=' . rawurlencode(server_cron_key());
    return [
        'disk_total'=>$total,'disk_free'=>$free,'disk_used'=>max(0,$total-$free),
        'disk_total_h'=>server_human_bytes($total),'disk_free_h'=>server_human_bytes($free),'disk_used_h'=>server_human_bytes(max(0,$total-$free)),
        'db_bytes'=>$dbBytes,'db_h'=>server_human_bytes($dbBytes),
        'logs_bytes'=>$logBytes,'logs_h'=>server_human_bytes($logBytes),
        'tracks'=>$tracks,'active_roots'=>$roots,
        'last_sync'=>(string)app_setting('native_library_last_sync','Nunca'),
        'cron_url'=>$cron,
    ];
}

function server_human_bytes(int|float $bytes): string
{
    $bytes = max(0, (float)$bytes);
    foreach (['B','KB','MB','GB','TB'] as $unit) {
        if ($bytes < 1024 || $unit === 'TB') return number_format($bytes, $unit === 'B' ? 0 : 1, ',', '.') . ' ' . $unit;
        $bytes /= 1024;
    }
    return '0 B';
}

function server_sync_active_drive_folders(bool $force = false, array $onlyIds = []): array
{
    if (function_exists('set_time_limit')) @set_time_limit(180);
    ensure_schema();
    server_ensure_500mb_schema();

    if (!$force && !$onlyIds) {
        $lastRaw = trim((string)app_setting('native_library_last_sync', ''));
        $last = $lastRaw !== '' ? strtotime($lastRaw) : false;
        if ($last && time() - $last < 300) {
            return ['skipped'=>true,'reason'=>'catalog_fresh','roots'=>0,'folders'=>0,'tracks'=>0,'inserted'=>0,'updated'=>0,'removed'=>0,'errors'=>[],'last_sync'=>$lastRaw];
        }
    }

    $lock = server_sync_lock_acquire();
    if (!$lock) {
        return ['skipped'=>true,'reason'=>'sync_already_running','roots'=>0,'folders'=>0,'tracks'=>0,'inserted'=>0,'updated'=>0,'removed'=>0,'errors'=>[],'last_sync'=>(string)app_setting('native_library_last_sync','')];
    }

    $startedAll = microtime(true);
    try {
        $sql = 'SELECT id,name,folder_id,folder_link FROM drive_folders WHERE active=1';
        $params = [];
        $ids = array_values(array_filter(array_map('intval', $onlyIds), fn($v) => $v > 0));
        if ($ids) {
            $sql .= ' AND id IN (' . implode(',', array_fill(0, count($ids), '?')) . ')';
            $params = $ids;
        }
        $sql .= ' ORDER BY id ASC';
        $q = db()->prepare($sql); $q->execute($params);
        $roots = $q->fetchAll() ?: [];
        $stats = ['skipped'=>false,'roots'=>count($roots),'folders'=>0,'tracks'=>0,'inserted'=>0,'updated'=>0,'removed'=>0,'errors'=>[]];
        $apiKey = google_api_key();

        foreach ($roots as $root) {
            $rootStarted = microtime(true);
            $rootId = (int)$root['id'];
            $visited = [];
            $rootName = trim((string)($root['name'] ?? '')) ?: 'Google Drive';
            $syncToken = bin2hex(random_bytes(12));
            $beforeTracks = (int)$stats['tracks'];
            $beforeInserted = (int)$stats['inserted'];
            $beforeUpdated = (int)$stats['updated'];
            $beforeErrors = count($stats['errors']);
            $GLOBALS['estradaplay_sync_root_id'] = $rootId;
            $GLOBALS['estradaplay_sync_token'] = $syncToken;
            try {
                native_library_scan_folder((string)$root['folder_id'], [$rootName], $apiKey, $visited, $stats, 0);
                $rootErrors = count($stats['errors']) - $beforeErrors;
                $removed = 0;
                if ($rootErrors === 0) $removed = server_sync_prune_root($rootId, $syncToken);
                $stats['removed'] += $removed;
                $rootTracks = max(0, (int)$stats['tracks'] - $beforeTracks);
                $duration = (int)round((microtime(true)-$rootStarted)*1000);
                $status = 'OK: '.$rootTracks.' músicas verificadas'.($removed > 0 ? ' | '.$removed.' removida(s)' : '').($rootErrors > 0 ? ' | '.$rootErrors.' aviso(s)' : '');
                $u = db()->prepare('UPDATE drive_folders SET last_import_at=?,last_status=?,last_sync_duration_ms=?,last_sync_tracks=?,last_sync_removed=?,last_sync_errors=? WHERE id=?');
                $u->execute([date('Y-m-d H:i:s'),$status,$duration,$rootTracks,$removed,$rootErrors,$rootId]);
            } catch (Throwable $e) {
                $stats['errors'][] = $rootName.': '.$e->getMessage();
                $duration = (int)round((microtime(true)-$rootStarted)*1000);
                try {
                    $u = db()->prepare('UPDATE drive_folders SET last_import_at=?,last_status=?,last_sync_duration_ms=?,last_sync_errors=? WHERE id=?');
                    $u->execute([date('Y-m-d H:i:s'),'Erro: '.$e->getMessage(),$duration,1,$rootId]);
                } catch (Throwable $ignored) {}
            } finally {
                unset($GLOBALS['estradaplay_sync_root_id'],$GLOBALS['estradaplay_sync_token']);
            }
        }
        set_app_setting('native_library_last_sync', date('Y-m-d H:i:s'));
        set_app_setting('native_library_last_duration_ms', (string)(int)round((microtime(true)-$startedAll)*1000));
        audit_log('drive.sync', ['roots'=>$stats['roots'],'tracks'=>$stats['tracks'],'inserted'=>$stats['inserted'],'updated'=>$stats['updated'],'removed'=>$stats['removed'],'errors'=>count($stats['errors'])]);
        return $stats;
    } finally {
        server_sync_lock_release($lock);
    }
}

function server_sync_prune_root(int $rootId, string $token): int
{
    if ($rootId <= 0 || $token === '') return 0;
    $s = db()->prepare("DELETE FROM music_library WHERE origin='Google Drive' AND drive_root_id=? AND sync_token IS NOT NULL AND sync_token<>?");
    $s->execute([$rootId,$token]);
    return $s->rowCount();
}

function server_sync_lock_acquire(): array|false
{
    try {
        if ((string)db()->getAttribute(PDO::ATTR_DRIVER_NAME) === 'mysql') {
            $s = db()->query("SELECT GET_LOCK('estradaplay_drive_sync',0)");
            if ((int)$s->fetchColumn() === 1) return ['type'=>'mysql'];
            return false;
        }
    } catch (Throwable $e) {}
    $path = sys_get_temp_dir() . '/estradaplay-drive-sync.lock';
    $fh = @fopen($path, 'c+');
    if (!$fh || !@flock($fh, LOCK_EX | LOCK_NB)) { if ($fh) @fclose($fh); return false; }
    return ['type'=>'file','handle'=>$fh];
}

function server_sync_lock_release(array $lock): void
{
    if (($lock['type'] ?? '') === 'mysql') {
        try { db()->query("SELECT RELEASE_LOCK('estradaplay_drive_sync')"); } catch (Throwable $e) {}
        return;
    }
    if (($lock['type'] ?? '') === 'file' && isset($lock['handle']) && is_resource($lock['handle'])) {
        @flock($lock['handle'], LOCK_UN); @fclose($lock['handle']);
    }
}
