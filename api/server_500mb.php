<?php
declare(strict_types=1);

// SERVER_500MB_V2: metadata-only runtime + protected control-center telemetry.
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
            if (!in_array($name, $cols, true)) db()->exec("ALTER TABLE music_library ADD COLUMN {$name} {$definition}");
        }

        $folderCols = schema_columns('drive_folders');
        $folderDefs = [
            'last_sync_duration_ms' => 'INT NULL',
            'last_sync_tracks' => 'INT NOT NULL DEFAULT 0',
            'last_sync_inserted' => 'INT NOT NULL DEFAULT 0',
            'last_sync_updated' => 'INT NOT NULL DEFAULT 0',
            'last_sync_removed' => 'INT NOT NULL DEFAULT 0',
            'last_sync_errors' => 'INT NOT NULL DEFAULT 0',
        ];
        foreach ($folderDefs as $name => $definition) {
            if (!in_array($name, $folderCols, true)) db()->exec("ALTER TABLE drive_folders ADD COLUMN {$name} {$definition}");
        }

        db()->exec("CREATE TABLE IF NOT EXISTS drive_sync_history (
            id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
            source VARCHAR(32) NOT NULL DEFAULT 'server',
            status VARCHAR(32) NOT NULL DEFAULT 'ok',
            started_at DATETIME NOT NULL,
            finished_at DATETIME NOT NULL,
            duration_ms INT NOT NULL DEFAULT 0,
            roots INT NOT NULL DEFAULT 0,
            tracks INT NOT NULL DEFAULT 0,
            inserted_count INT NOT NULL DEFAULT 0,
            updated_count INT NOT NULL DEFAULT 0,
            removed_count INT NOT NULL DEFAULT 0,
            error_count INT NOT NULL DEFAULT 0,
            catalog_version BIGINT NOT NULL DEFAULT 0,
            detail TEXT NULL,
            KEY idx_sync_history_finished (finished_at),
            KEY idx_sync_history_status (status)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

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

function server_housekeeping(bool $force = false): void
{
    static $ran = false;
    if ($ran && !$force) return;
    $ran = true;
    server_rotate_logs();
    try {
        $last = (int)app_setting('server_housekeeping_ts', '0');
        if (!$force && $last > 0 && time() - $last < 21600) return;

        $retentionDays = 30;
        $health = server_health_snapshot(false);
        if (($health['quota_level'] ?? 'ok') === 'critical') $retentionDays = 7;
        elseif (($health['quota_level'] ?? 'ok') === 'danger') $retentionDays = 14;

        $cut = date('Y-m-d H:i:s', time() - $retentionDays * 86400);
        $s = db()->prepare('DELETE FROM audit_logs WHERE created_at < ?');
        $s->execute([$cut]);

        try {
            db()->exec("DELETE FROM drive_sync_history WHERE finished_at < DATE_SUB(NOW(), INTERVAL 60 DAY)");
            db()->exec("DELETE FROM drive_sync_history WHERE id NOT IN (SELECT id FROM (SELECT id FROM drive_sync_history ORDER BY id DESC LIMIT 100) keep_rows)");
        } catch (Throwable $ignored) {}

        if (in_array(($health['quota_level'] ?? 'ok'), ['danger','critical'], true)) {
            $logDir = dirname(__DIR__) . '/logs';
            @unlink($logDir . '/php-error.log.3');
            if (($health['quota_level'] ?? '') === 'critical') @unlink($logDir . '/php-error.log.2');
        }
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

function server_rotate_cron_key(): string
{
    $key = bin2hex(random_bytes(24));
    set_app_setting('server_cron_key', $key);
    return $key;
}

function server_quota_bytes(): int
{
    $mb = (int)app_setting('server_quota_mb', '500');
    if ($mb < 100 || $mb > 10240) $mb = 500;
    return $mb * 1024 * 1024;
}

function server_directory_bytes(string $path): int
{
    if (!is_dir($path)) return is_file($path) ? (int)filesize($path) : 0;
    $total = 0;
    try {
        $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($path, FilesystemIterator::SKIP_DOTS));
        foreach ($it as $file) {
            if ($file->isLink() || !$file->isFile()) continue;
            $pathname = str_replace('\\', '/', $file->getPathname());
            if (str_contains($pathname, '/.git/')) continue;
            $total += (int)$file->getSize();
            if ($total > 20 * 1024 * 1024 * 1024) break;
        }
    } catch (Throwable $e) {}
    return $total;
}

function server_health_snapshot(bool $scanFiles = true): array
{
    server_ensure_500mb_schema();
    $root = dirname(__DIR__);
    $diskTotal = @disk_total_space($root) ?: 0;
    $diskFree = @disk_free_space($root) ?: 0;
    $dbBytes = 0;
    try {
        if ((string)db()->getAttribute(PDO::ATTR_DRIVER_NAME) === 'mysql') {
            $dbBytes = (int)db()->query('SELECT COALESCE(SUM(data_length+index_length),0) FROM information_schema.TABLES WHERE table_schema=DATABASE()')->fetchColumn();
        }
    } catch (Throwable $e) {}
    $logBytes = 0;
    foreach (glob($root . '/logs/*') ?: [] as $f) if (is_file($f)) $logBytes += (int)filesize($f);
    $filesBytes = $scanFiles ? server_directory_bytes($root) : 0;
    $managedBytes = max(0, $filesBytes + $dbBytes);
    $quota = server_quota_bytes();
    $percent = $quota > 0 ? min(999, ($managedBytes / $quota) * 100) : 0;
    $level = $percent >= 90 ? 'critical' : ($percent >= 80 ? 'danger' : ($percent >= 70 ? 'warning' : 'ok'));
    $levelLabel = ['ok'=>'OK','warning'=>'ATENÇÃO','danger'=>'ALERTA','critical'=>'CRÍTICO'][$level];
    try { $tracks = (int)db()->query('SELECT COUNT(*) FROM music_library')->fetchColumn(); } catch (Throwable $e) { $tracks = 0; }
    try { $roots = (int)db()->query('SELECT COUNT(*) FROM drive_folders WHERE active=1')->fetchColumn(); } catch (Throwable $e) { $roots = 0; }
    $host = (string)($_SERVER['HTTP_HOST'] ?? 'seu-dominio');
    $scheme = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') ? 'https' : 'http';
    $cron = $scheme . '://' . $host . '/api/cron_drive_sync.php?key=' . rawurlencode(server_cron_key());
    return [
        'disk_total'=>$diskTotal,'disk_free'=>$diskFree,'disk_used'=>max(0,$diskTotal-$diskFree),
        'disk_total_h'=>server_human_bytes($diskTotal),'disk_free_h'=>server_human_bytes($diskFree),'disk_used_h'=>server_human_bytes(max(0,$diskTotal-$diskFree)),
        'db_bytes'=>$dbBytes,'db_h'=>server_human_bytes($dbBytes),
        'logs_bytes'=>$logBytes,'logs_h'=>server_human_bytes($logBytes),
        'files_bytes'=>$filesBytes,'files_h'=>server_human_bytes($filesBytes),
        'managed_bytes'=>$managedBytes,'managed_h'=>server_human_bytes($managedBytes),
        'quota_bytes'=>$quota,'quota_h'=>server_human_bytes($quota),'quota_percent'=>round($percent,1),'quota_level'=>$level,'quota_label'=>$levelLabel,
        'tracks'=>$tracks,'active_roots'=>$roots,
        'catalog_version'=>server_catalog_version(),
        'last_sync'=>(string)app_setting('native_library_last_sync','Nunca'),
        'last_sync_duration_ms'=>(int)app_setting('native_library_last_duration_ms','0'),
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

function server_catalog_version(): int
{
    return max(1, (int)app_setting('catalog_version', '1'));
}

function server_bump_catalog_version(): int
{
    $version = server_catalog_version() + 1;
    set_app_setting('catalog_version', (string)$version);
    set_app_setting('catalog_changed_at', date('Y-m-d H:i:s'));
    return $version;
}

function server_sync_history(int $limit = 50): array
{
    server_ensure_500mb_schema();
    $limit = max(1, min(100, $limit));
    try {
        return db()->query('SELECT * FROM drive_sync_history ORDER BY id DESC LIMIT ' . $limit)->fetchAll() ?: [];
    } catch (Throwable $e) { return []; }
}

function server_folder_health(): array
{
    server_ensure_500mb_schema();
    try {
        return db()->query('SELECT id,name,folder_id,active,last_import_at,last_status,last_sync_duration_ms,last_sync_tracks,last_sync_inserted,last_sync_updated,last_sync_removed,last_sync_errors FROM drive_folders ORDER BY active DESC,id ASC')->fetchAll() ?: [];
    } catch (Throwable $e) { return []; }
}

function server_diagnostic_snapshot(): array
{
    $health = server_health_snapshot();
    $dbVersion = '';
    try { $dbVersion = (string)db()->query('SELECT VERSION()')->fetchColumn(); } catch (Throwable $e) {}
    $folderRows = server_folder_health();
    $folderOk = 0; $folderWarn = 0;
    foreach ($folderRows as $row) {
        if ((int)($row['last_sync_errors'] ?? 0) > 0 || str_starts_with((string)($row['last_status'] ?? ''), 'Erro')) $folderWarn++;
        else $folderOk++;
    }
    return [
        'status'=>$health['quota_level'] === 'critical' ? 'CRITICAL' : ($folderWarn > 0 ? 'WARN' : 'OK'),
        'server_version'=>'500MB-v2',
        'php'=>PHP_VERSION,
        'sapi'=>PHP_SAPI,
        'db_driver'=>(string)db()->getAttribute(PDO::ATTR_DRIVER_NAME),
        'db_version'=>$dbVersion,
        'health'=>$health,
        'folders'=>['total'=>count($folderRows),'ok'=>$folderOk,'warning'=>$folderWarn],
        'history_count'=>count(server_sync_history(100)),
        'generated_at'=>date(DATE_ATOM),
    ];
}

function server_record_sync_history(string $source, string $status, string $startedAt, int $durationMs, array $stats, int $catalogVersion, string $detail = ''): void
{
    try {
        $source = preg_replace('/[^a-zA-Z0-9_.-]/', '', $source) ?: 'server';
        $detail = function_exists('mb_substr') ? mb_substr($detail, 0, 1000) : substr($detail, 0, 1000);
        $s = db()->prepare('INSERT INTO drive_sync_history (source,status,started_at,finished_at,duration_ms,roots,tracks,inserted_count,updated_count,removed_count,error_count,catalog_version,detail) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)');
        $s->execute([$source,$status,$startedAt,date('Y-m-d H:i:s'),$durationMs,(int)($stats['roots']??0),(int)($stats['tracks']??0),(int)($stats['inserted']??0),(int)($stats['updated']??0),(int)($stats['removed']??0),count($stats['errors']??[]),$catalogVersion,$detail]);
    } catch (Throwable $e) { error_log('SERVER_500MB history: ' . $e->getMessage()); }
}

function server_sync_active_drive_folders(bool $force = false, array $onlyIds = [], string $source = 'app'): array
{
    if (function_exists('set_time_limit')) @set_time_limit(180);
    ensure_schema();
    server_ensure_500mb_schema();

    if (!$force && !$onlyIds) {
        $lastRaw = trim((string)app_setting('native_library_last_sync', ''));
        $last = $lastRaw !== '' ? strtotime($lastRaw) : false;
        if ($last && time() - $last < 300) {
            return ['skipped'=>true,'reason'=>'catalog_fresh','roots'=>0,'folders'=>0,'tracks'=>0,'inserted'=>0,'updated'=>0,'removed'=>0,'errors'=>[],'last_sync'=>$lastRaw,'catalog_version'=>server_catalog_version()];
        }
    }

    $lock = server_sync_lock_acquire();
    if (!$lock) {
        return ['skipped'=>true,'reason'=>'sync_already_running','roots'=>0,'folders'=>0,'tracks'=>0,'inserted'=>0,'updated'=>0,'removed'=>0,'errors'=>[],'last_sync'=>(string)app_setting('native_library_last_sync',''),'catalog_version'=>server_catalog_version()];
    }

    $startedAll = microtime(true);
    $startedAt = date('Y-m-d H:i:s');
    $stats = ['skipped'=>false,'roots'=>0,'folders'=>0,'tracks'=>0,'inserted'=>0,'updated'=>0,'removed'=>0,'errors'=>[]];
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
        $stats['roots'] = count($roots);
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
                $rootInserted = max(0, (int)$stats['inserted'] - $beforeInserted);
                $rootUpdated = max(0, (int)$stats['updated'] - $beforeUpdated);
                $duration = (int)round((microtime(true)-$rootStarted)*1000);
                $status = 'OK: '.$rootTracks.' músicas verificadas';
                if ($rootInserted > 0) $status .= ' | +'.$rootInserted.' nova(s)';
                if ($rootUpdated > 0) $status .= ' | '.$rootUpdated.' alterada(s)';
                if ($removed > 0) $status .= ' | '.$removed.' removida(s)';
                if ($rootErrors > 0) $status .= ' | '.$rootErrors.' aviso(s)';
                $u = db()->prepare('UPDATE drive_folders SET last_import_at=?,last_status=?,last_sync_duration_ms=?,last_sync_tracks=?,last_sync_inserted=?,last_sync_updated=?,last_sync_removed=?,last_sync_errors=? WHERE id=?');
                $u->execute([date('Y-m-d H:i:s'),$status,$duration,$rootTracks,$rootInserted,$rootUpdated,$removed,$rootErrors,$rootId]);
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

        $changed = (int)$stats['inserted'] + (int)$stats['updated'] + (int)$stats['removed'];
        $catalogVersion = $changed > 0 ? server_bump_catalog_version() : server_catalog_version();
        $durationAll = (int)round((microtime(true)-$startedAll)*1000);
        set_app_setting('native_library_last_sync', date('Y-m-d H:i:s'));
        set_app_setting('native_library_last_duration_ms', (string)$durationAll);
        $stats['catalog_version'] = $catalogVersion;
        $stats['changed'] = $changed;
        $status = count($stats['errors']) > 0 ? 'warning' : 'ok';
        server_record_sync_history($source,$status,$startedAt,$durationAll,$stats,$catalogVersion,implode(' | ',array_slice($stats['errors'],0,4)));
        audit_log('drive.sync', ['source'=>$source,'roots'=>$stats['roots'],'tracks'=>$stats['tracks'],'inserted'=>$stats['inserted'],'updated'=>$stats['updated'],'removed'=>$stats['removed'],'errors'=>count($stats['errors']),'catalog_version'=>$catalogVersion]);
        return $stats;
    } catch (Throwable $e) {
        $durationAll = (int)round((microtime(true)-$startedAll)*1000);
        $stats['errors'][] = $e->getMessage();
        server_record_sync_history($source,'error',$startedAt,$durationAll,$stats,server_catalog_version(),$e->getMessage());
        throw $e;
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
