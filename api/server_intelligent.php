<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/server_context_v7.php';

const ESTRADAPLAY_SERVER_INTELLIGENT_VERSION = '500MB-v7.0';

function intelligent_server_ensure_schema(): void
{
    ensure_schema();
    ep7_ensure_schema();
    $driver = (string)db()->getAttribute(PDO::ATTR_DRIVER_NAME);
    if ($driver === 'mysql') {
        db()->exec("CREATE TABLE IF NOT EXISTS road_reports (
            id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
            user_id BIGINT NULL,
            device_token VARCHAR(190) NULL,
            type VARCHAR(50) NOT NULL,
            latitude DOUBLE NOT NULL,
            longitude DOUBLE NOT NULL,
            speed_kmh SMALLINT NULL,
            limit_kmh SMALLINT NULL,
            note VARCHAR(500) NULL,
            source VARCHAR(32) NOT NULL DEFAULT 'APP',
            status VARCHAR(30) NOT NULL DEFAULT 'PENDENTE',
            created_at DATETIME NOT NULL,
            reviewed_at DATETIME NULL,
            reviewed_by BIGINT NULL,
            KEY idx_rr_status_created(status,created_at),
            KEY idx_rr_created(created_at),
            KEY idx_rr_device(device_token),
            KEY idx_rr_geo(latitude,longitude)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
    } else {
        db()->exec("CREATE TABLE IF NOT EXISTS road_reports (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER,
            device_token TEXT,
            type TEXT NOT NULL,
            latitude REAL NOT NULL,
            longitude REAL NOT NULL,
            speed_kmh INTEGER,
            limit_kmh INTEGER,
            note TEXT,
            source TEXT NOT NULL DEFAULT 'APP',
            status TEXT NOT NULL DEFAULT 'PENDENTE',
            created_at TEXT NOT NULL,
            reviewed_at TEXT,
            reviewed_by INTEGER
        )");
        try { db()->exec('CREATE INDEX IF NOT EXISTS idx_rr_status_created ON road_reports(status,created_at)'); } catch (Throwable $e) {}
        try { db()->exec('CREATE INDEX IF NOT EXISTS idx_rr_created ON road_reports(created_at)'); } catch (Throwable $e) {}
        try { db()->exec('CREATE INDEX IF NOT EXISTS idx_rr_device ON road_reports(device_token)'); } catch (Throwable $e) {}
    }

    $cols = schema_columns('road_reports');
    $defs = $driver === 'mysql'
        ? [
            'speed_kmh'=>'SMALLINT NULL','limit_kmh'=>'SMALLINT NULL','note'=>'VARCHAR(500) NULL',
            'source'=>"VARCHAR(32) NOT NULL DEFAULT 'APP'",'reviewed_at'=>'DATETIME NULL','reviewed_by'=>'BIGINT NULL'
        ]
        : [
            'speed_kmh'=>'INTEGER','limit_kmh'=>'INTEGER','note'=>'TEXT',
            'source'=>"TEXT NOT NULL DEFAULT 'APP'",'reviewed_at'=>'TEXT','reviewed_by'=>'INTEGER'
        ];
    foreach ($defs as $name => $definition) {
        if (!in_array($name, $cols, true)) {
            try { db()->exec("ALTER TABLE road_reports ADD COLUMN {$name} {$definition}"); } catch (Throwable $e) {}
        }
    }
}

function intelligent_server_report_stats(): array
{
    intelligent_server_ensure_schema();
    $out = ['total'=>0,'pending'=>0,'confirmed'=>0,'rejected'=>0,'last24h'=>0];
    try {
        $rows = db()->query("SELECT status,COUNT(*) c FROM road_reports GROUP BY status")->fetchAll() ?: [];
        foreach ($rows as $r) {
            $c=(int)$r['c']; $out['total'] += $c;
            $s=strtoupper((string)$r['status']);
            if ($s==='PENDENTE') $out['pending'] += $c;
            elseif ($s==='CONFIRMADO') $out['confirmed'] += $c;
            elseif ($s==='REJEITADO') $out['rejected'] += $c;
        }
        $cut = date('Y-m-d H:i:s', time()-86400);
        $s = db()->prepare('SELECT COUNT(*) FROM road_reports WHERE created_at >= ?');
        $s->execute([$cut]);
        $out['last24h'] = (int)$s->fetchColumn();
    } catch (Throwable $e) {}
    return $out;
}

function intelligent_server_reports(string $status = '', int $limit = 200): array
{
    intelligent_server_ensure_schema();
    $limit = max(1,min(500,$limit));
    $status = strtoupper(trim($status));
    try {
        if (in_array($status,['PENDENTE','CONFIRMADO','REJEITADO'],true)) {
            $s=db()->prepare('SELECT * FROM road_reports WHERE status=? ORDER BY id DESC LIMIT '.$limit);
            $s->execute([$status]);
            return $s->fetchAll() ?: [];
        }
        return db()->query('SELECT * FROM road_reports ORDER BY id DESC LIMIT '.$limit)->fetchAll() ?: [];
    } catch (Throwable $e) { return []; }
}

function intelligent_server_set_report_status(int $id, string $status, int $adminId): bool
{
    intelligent_server_ensure_schema();
    $status = strtoupper(trim($status));
    if ($id <= 0 || !in_array($status,['PENDENTE','CONFIRMADO','REJEITADO'],true)) return false;
    try {
        $reviewed = $status === 'PENDENTE' ? null : date('Y-m-d H:i:s');
        $reviewer = $status === 'PENDENTE' ? null : $adminId;
        $s=db()->prepare('UPDATE road_reports SET status=?,reviewed_at=?,reviewed_by=? WHERE id=?');
        $s->execute([$status,$reviewed,$reviewer,$id]);
        audit_log('road_report.review',['report_id'=>$id,'status'=>$status]);
        return $s->rowCount() > 0;
    } catch (Throwable $e) { return false; }
}

function intelligent_server_find_duplicate(int $userId, string $device, string $type, float $lat, float $lon, int $windowSeconds = 120, float $radiusM = 120.0): int
{
    intelligent_server_ensure_schema();
    $cut = date('Y-m-d H:i:s', time()-max(30,$windowSeconds));
    try {
        if ($device !== '') {
            $s=db()->prepare('SELECT id,latitude,longitude FROM road_reports WHERE device_token=? AND type=? AND created_at>=? ORDER BY id DESC LIMIT 20');
            $s->execute([$device,$type,$cut]);
        } else {
            $s=db()->prepare('SELECT id,latitude,longitude FROM road_reports WHERE user_id=? AND type=? AND created_at>=? ORDER BY id DESC LIMIT 20');
            $s->execute([$userId,$type,$cut]);
        }
        foreach ($s->fetchAll() ?: [] as $r) {
            if (haversine_m($lat,$lon,(float)$r['latitude'],(float)$r['longitude']) <= $radiusM) return (int)$r['id'];
        }
    } catch (Throwable $e) {}
    return 0;
}

function intelligent_server_housekeeping(bool $force = false): array
{
    intelligent_server_ensure_schema();
    $deleted = 0;
    try {
        $last=(int)app_setting('intelligent_housekeeping_ts','0');
        if (!$force && $last > 0 && time()-$last < 21600) {
            $context=ep7_housekeeping(false);
            return ['skipped'=>true,'deleted'=>(int)($context['deleted']??0)];
        }
        $resolvedCut=date('Y-m-d H:i:s',time()-180*86400);
        $pendingCut=date('Y-m-d H:i:s',time()-365*86400);
        $s=db()->prepare("DELETE FROM road_reports WHERE (status IN ('CONFIRMADO','REJEITADO') AND created_at < ?) OR (status='PENDENTE' AND created_at < ?)");
        $s->execute([$resolvedCut,$pendingCut]);
        $deleted += $s->rowCount();
        $context=ep7_housekeeping(true);$deleted+=(int)($context['deleted']??0);
        set_app_setting('intelligent_housekeeping_ts',(string)time());
    } catch (Throwable $e) { error_log('INTELLIGENT housekeeping: '.$e->getMessage()); }
    return ['skipped'=>false,'deleted'=>$deleted];
}

function intelligent_server_capabilities(): array
{
    return [
        'server_version'=>ESTRADAPLAY_SERVER_INTELLIGENT_VERSION,
        'metadata_only'=>true,
        'stores_audio'=>false,
        'stores_video'=>false,
        'road_reports'=>true,
        'report_review'=>true,
        'road_live_confidence'=>true,
        'road_live_negative_feedback'=>true,
        'route_context'=>true,
        'route_weather'=>true,
        'collaborative_traffic'=>true,
        'traffic_privacy'=>['opt_in'=>true,'device_hash_only'=>true,'retention_h'=>2],
        'fuel_route_ranking'=>true,
        'weather_cache_ttl_s'=>900,
        'drive_catalog_sync'=>true,
        'catalog_version'=>true,
        'quota_guard'=>true,
        'log_rotation'=>true,
        'offline_state_packs'=>['SP','RJ','MG','ES'],
        'road_radio'=>true,
        'radio_audio_stored'=>false,
        'radio_room_max'=>8,
    ];
}

function intelligent_server_status_snapshot(): array
{
    intelligent_server_housekeeping(false);
    $health = server_health_snapshot(false);
    return [
        'version'=>ESTRADAPLAY_SERVER_INTELLIGENT_VERSION,
        'catalog_version'=>server_catalog_version(),
        'catalog_changed_at'=>app_setting('catalog_changed_at',''),
        'reports'=>intelligent_server_report_stats(),
        'context'=>ep7_context_stats(),
        'quota'=>[
            'level'=>$health['quota_level'] ?? 'ok',
            'percent'=>$health['quota_percent'] ?? 0,
            'managed_h'=>$health['managed_h'] ?? '0 B',
            'quota_h'=>$health['quota_h'] ?? '500 MB',
        ],
        'capabilities'=>intelligent_server_capabilities(),
        'generated_at'=>date(DATE_ATOM),
    ];
}
