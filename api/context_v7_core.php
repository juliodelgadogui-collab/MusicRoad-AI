<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';

const ESTRADAPLAY_CONTEXT_VERSION = '7.0';

function ep7_is_mysql(): bool
{
    return (string)db()->getAttribute(PDO::ATTR_DRIVER_NAME) === 'mysql';
}

function ep7_safe_exec(string $sql): void
{
    try { db()->exec($sql); } catch (Throwable $e) { error_log('EP7 SQL: ' . $e->getMessage()); }
}

function ep7_ensure_schema(): void
{
    static $done = false;
    if ($done) return;
    $done = true;
    ensure_schema();

    if (ep7_is_mysql()) {
        ep7_safe_exec("CREATE TABLE IF NOT EXISTS road_traffic_samples (
            id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
            device_hash CHAR(64) NOT NULL,
            cell_key VARCHAR(120) NOT NULL,
            latitude DOUBLE NOT NULL,
            longitude DOUBLE NOT NULL,
            road VARCHAR(60) NULL,
            heading SMALLINT NULL,
            speed_kmh SMALLINT NOT NULL,
            limit_kmh SMALLINT NULL,
            accuracy_m SMALLINT NULL,
            captured_at BIGINT NULL,
            expires_at DATETIME NOT NULL,
            created_at DATETIME NOT NULL,
            updated_at DATETIME NOT NULL,
            UNIQUE KEY uq_traffic_device_cell (device_hash,cell_key),
            KEY idx_traffic_exp (expires_at),
            KEY idx_traffic_geo (latitude,longitude),
            KEY idx_traffic_cell (cell_key)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

        ep7_safe_exec("CREATE TABLE IF NOT EXISTS road_weather_cache (
            cache_key VARCHAR(96) NOT NULL PRIMARY KEY,
            latitude DOUBLE NOT NULL,
            longitude DOUBLE NOT NULL,
            payload LONGTEXT NOT NULL,
            fetched_at DATETIME NOT NULL,
            expires_at DATETIME NOT NULL,
            KEY idx_weather_exp (expires_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

        ep7_safe_exec("CREATE TABLE IF NOT EXISTS fuel_price_reports (
            id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
            user_id BIGINT NULL,
            device_token VARCHAR(160) NULL,
            station VARCHAR(160) NOT NULL,
            fuel VARCHAR(48) NOT NULL,
            price DECIMAL(6,3) NOT NULL,
            latitude DOUBLE NOT NULL,
            longitude DOUBLE NOT NULL,
            reported_at BIGINT NULL,
            created_at DATETIME NOT NULL,
            KEY idx_fuel_geo_time (latitude,longitude,created_at),
            KEY idx_fuel_type_time (fuel,created_at),
            KEY idx_fuel_device (device_token)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

        ep7_safe_exec("CREATE TABLE IF NOT EXISTS road_live_events (
            id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
            user_id BIGINT NULL,
            device_token VARCHAR(160) NULL,
            event_type VARCHAR(32) NOT NULL,
            latitude DOUBLE NOT NULL,
            longitude DOUBLE NOT NULL,
            road VARCHAR(40) NULL,
            note VARCHAR(160) NULL,
            confirmations INT UNSIGNED NOT NULL DEFAULT 0,
            dismissals INT UNSIGNED NOT NULL DEFAULT 0,
            heading SMALLINT NULL,
            speed_kmh SMALLINT NULL,
            occurred_at BIGINT NULL,
            expires_at DATETIME NOT NULL,
            created_at DATETIME NOT NULL,
            last_feedback_at DATETIME NULL,
            KEY idx_road_live_geo (latitude,longitude),
            KEY idx_road_live_exp (expires_at),
            KEY idx_road_live_type_time (event_type,created_at),
            KEY idx_road_live_device (device_token)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

        ep7_safe_exec("CREATE TABLE IF NOT EXISTS road_live_votes (
            event_id BIGINT UNSIGNED NOT NULL,
            device_hash CHAR(64) NOT NULL,
            vote TINYINT NOT NULL,
            created_at DATETIME NOT NULL,
            updated_at DATETIME NOT NULL,
            PRIMARY KEY(event_id,device_hash),
            KEY idx_live_votes_updated (updated_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
    } else {
        ep7_safe_exec("CREATE TABLE IF NOT EXISTS road_traffic_samples (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_hash TEXT NOT NULL,
            cell_key TEXT NOT NULL,
            latitude REAL NOT NULL,
            longitude REAL NOT NULL,
            road TEXT,
            heading INTEGER,
            speed_kmh INTEGER NOT NULL,
            limit_kmh INTEGER,
            accuracy_m INTEGER,
            captured_at INTEGER,
            expires_at TEXT NOT NULL,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            UNIQUE(device_hash,cell_key)
        )");
        ep7_safe_exec('CREATE INDEX IF NOT EXISTS idx_traffic_exp ON road_traffic_samples(expires_at)');
        ep7_safe_exec('CREATE INDEX IF NOT EXISTS idx_traffic_geo ON road_traffic_samples(latitude,longitude)');
        ep7_safe_exec('CREATE INDEX IF NOT EXISTS idx_traffic_cell ON road_traffic_samples(cell_key)');

        ep7_safe_exec("CREATE TABLE IF NOT EXISTS road_weather_cache (
            cache_key TEXT PRIMARY KEY,
            latitude REAL NOT NULL,
            longitude REAL NOT NULL,
            payload TEXT NOT NULL,
            fetched_at TEXT NOT NULL,
            expires_at TEXT NOT NULL
        )");
        ep7_safe_exec('CREATE INDEX IF NOT EXISTS idx_weather_exp ON road_weather_cache(expires_at)');

        ep7_safe_exec("CREATE TABLE IF NOT EXISTS fuel_price_reports (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER,
            device_token TEXT,
            station TEXT NOT NULL,
            fuel TEXT NOT NULL,
            price REAL NOT NULL,
            latitude REAL NOT NULL,
            longitude REAL NOT NULL,
            reported_at INTEGER,
            created_at TEXT NOT NULL
        )");
        ep7_safe_exec('CREATE INDEX IF NOT EXISTS idx_fuel_geo_time ON fuel_price_reports(latitude,longitude,created_at)');
        ep7_safe_exec('CREATE INDEX IF NOT EXISTS idx_fuel_type_time ON fuel_price_reports(fuel,created_at)');

        ep7_safe_exec("CREATE TABLE IF NOT EXISTS road_live_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER,
            device_token TEXT,
            event_type TEXT NOT NULL,
            latitude REAL NOT NULL,
            longitude REAL NOT NULL,
            road TEXT,
            note TEXT,
            confirmations INTEGER NOT NULL DEFAULT 0,
            dismissals INTEGER NOT NULL DEFAULT 0,
            heading INTEGER,
            speed_kmh INTEGER,
            occurred_at INTEGER,
            expires_at TEXT NOT NULL,
            created_at TEXT NOT NULL,
            last_feedback_at TEXT
        )");
        ep7_safe_exec('CREATE INDEX IF NOT EXISTS idx_road_live_geo ON road_live_events(latitude,longitude)');
        ep7_safe_exec('CREATE INDEX IF NOT EXISTS idx_road_live_exp ON road_live_events(expires_at)');
        ep7_safe_exec('CREATE INDEX IF NOT EXISTS idx_road_live_type_time ON road_live_events(event_type,created_at)');
        ep7_safe_exec('CREATE INDEX IF NOT EXISTS idx_road_live_device ON road_live_events(device_token)');

        ep7_safe_exec("CREATE TABLE IF NOT EXISTS road_live_votes (
            event_id INTEGER NOT NULL,
            device_hash TEXT NOT NULL,
            vote INTEGER NOT NULL,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            PRIMARY KEY(event_id,device_hash)
        )");
        ep7_safe_exec('CREATE INDEX IF NOT EXISTS idx_live_votes_updated ON road_live_votes(updated_at)');
    }

    $cols = schema_columns('road_live_events');
    $defs = ep7_is_mysql()
        ? ['dismissals'=>'INT UNSIGNED NOT NULL DEFAULT 0','heading'=>'SMALLINT NULL','speed_kmh'=>'SMALLINT NULL','last_feedback_at'=>'DATETIME NULL']
        : ['dismissals'=>'INTEGER NOT NULL DEFAULT 0','heading'=>'INTEGER','speed_kmh'=>'INTEGER','last_feedback_at'=>'TEXT'];
    foreach ($defs as $name => $definition) {
        if (!in_array($name, $cols, true)) ep7_safe_exec("ALTER TABLE road_live_events ADD COLUMN {$name} {$definition}");
    }

    // Preserve confirmations made by older app/server versions.
    try {
        if (schema_columns('road_live_confirmations')) {
            $salt = ep7_privacy_salt();
            $rows = db()->query('SELECT event_id,device_token,created_at FROM road_live_confirmations ORDER BY created_at DESC LIMIT 5000')->fetchAll() ?: [];
            foreach ($rows as $r) {
                $token = trim((string)($r['device_token'] ?? ''));
                if ($token === '') continue;
                $hash = hash('sha256', $salt . '|' . strtolower($token));
                $created = (string)($r['created_at'] ?? date('Y-m-d H:i:s'));
                if (ep7_is_mysql()) {
                    $s = db()->prepare('INSERT IGNORE INTO road_live_votes (event_id,device_hash,vote,created_at,updated_at) VALUES (?,?,1,?,?)');
                } else {
                    $s = db()->prepare('INSERT OR IGNORE INTO road_live_votes (event_id,device_hash,vote,created_at,updated_at) VALUES (?,?,1,?,?)');
                }
                $s->execute([(int)$r['event_id'],$hash,$created,$created]);
            }
        }
    } catch (Throwable $e) {}
}

function ep7_privacy_salt(): string
{
    $salt = trim((string)app_setting('ep7_privacy_salt', ''));
    if ($salt !== '') return $salt;
    $salt = bin2hex(random_bytes(24));
    set_app_setting('ep7_privacy_salt', $salt);
    return $salt;
}

function ep7_device_hash(string $token): string
{
    $token = strtolower(trim($token));
    return $token === '' ? '' : hash('sha256', ep7_privacy_salt() . '|' . $token);
}

function ep7_housekeeping(bool $force = false): array
{
    ep7_ensure_schema();
    $last = (int)app_setting('ep7_housekeeping_ts', '0');
    if (!$force && $last > 0 && time() - $last < 1800) return ['skipped'=>true,'deleted'=>0];
    $deleted = 0;
    try {
        $now = date('Y-m-d H:i:s');
        foreach (['road_traffic_samples','road_weather_cache','road_live_events'] as $table) {
            $s = db()->prepare("DELETE FROM {$table} WHERE expires_at < ?");
            $s->execute([$now]);
            $deleted += $s->rowCount();
        }
        $voteCut = date('Y-m-d H:i:s', time() - 14 * 86400);
        $s = db()->prepare('DELETE FROM road_live_votes WHERE updated_at < ?');
        $s->execute([$voteCut]);
        $deleted += $s->rowCount();
        set_app_setting('ep7_housekeeping_ts', (string)time());
    } catch (Throwable $e) { error_log('EP7 housekeeping: ' . $e->getMessage()); }
    return ['skipped'=>false,'deleted'=>$deleted];
}

function ep7_valid_coord(float $lat, float $lon): bool
{
    return is_finite($lat) && is_finite($lon) && $lat >= -35.5 && $lat <= 6.5 && $lon >= -75.5 && $lon <= -30.0;
}

function ep7_parse_bool(mixed $value, bool $default = false): bool
{
    if ($value === null) return $default;
    if (is_bool($value)) return $value;
    if (is_numeric($value)) return (int)$value !== 0;
    return in_array(strtolower(trim((string)$value)), ['1','true','yes','sim','on'], true);
}

function ep7_route_points(mixed $raw, float $fallbackLat, float $fallbackLon, int $maxPoints = 48): array
{
    $points = [];
    if (is_array($raw)) {
        foreach ($raw as $p) {
            if (!is_array($p)) continue;
            $lat = isset($p['lat']) ? (float)$p['lat'] : (isset($p[0]) ? (float)$p[0] : NAN);
            $lon = isset($p['lon']) ? (float)$p['lon'] : (isset($p[1]) ? (float)$p[1] : NAN);
            if (!ep7_valid_coord($lat,$lon)) continue;
            if ($points && haversine_m((float)end($points)['lat'],(float)end($points)['lon'],$lat,$lon) < 20) continue;
            $points[] = ['lat'=>$lat,'lon'=>$lon];
        }
    }
    if (!$points) $points[] = ['lat'=>$fallbackLat,'lon'=>$fallbackLon];
    if (haversine_m($fallbackLat,$fallbackLon,(float)$points[0]['lat'],(float)$points[0]['lon']) > 150) {
        array_unshift($points, ['lat'=>$fallbackLat,'lon'=>$fallbackLon]);
    }
    if (count($points) > $maxPoints) {
        $src = $points; $points = [];
        $last = count($src)-1;
        for ($i=0;$i<$maxPoints;$i++) {
            $idx = (int)round(($i / max(1,$maxPoints-1)) * $last);
            $points[] = $src[$idx];
        }
    }
    return $points;
}

function ep7_route_distance(array $route): float
{
    $total = 0.0;
    for ($i=1;$i<count($route);$i++) $total += haversine_m((float)$route[$i-1]['lat'],(float)$route[$i-1]['lon'],(float)$route[$i]['lat'],(float)$route[$i]['lon']);
    return $total;
}

function ep7_route_metrics(float $lat, float $lon, array $route): array
{
    if (count($route) < 2) {
        $d = haversine_m($lat,$lon,(float)$route[0]['lat'],(float)$route[0]['lon']);
        return ['lateral_m'=>$d,'ahead_m'=>$d,'segment'=>0];
    }
    $best = INF; $bestAlong = 0.0; $cum = 0.0; $bestSeg = 0;
    for ($i=1;$i<count($route);$i++) {
        $aLat=(float)$route[$i-1]['lat'];$aLon=(float)$route[$i-1]['lon'];
        $bLat=(float)$route[$i]['lat'];$bLon=(float)$route[$i]['lon'];
        $refLat=deg2rad(($aLat+$bLat+$lat)/3.0);
        $mx=111320.0*max(0.2,cos($refLat));$my=110540.0;
        $bx=($bLon-$aLon)*$mx;$by=($bLat-$aLat)*$my;
        $px=($lon-$aLon)*$mx;$py=($lat-$aLat)*$my;
        $seg2=$bx*$bx+$by*$by;$seg=sqrt($seg2);
        if ($seg2 < 1.0) { $cum += $seg; continue; }
        $t=max(0.0,min(1.0,($px*$bx+$py*$by)/$seg2));
        $dx=$px-$t*$bx;$dy=$py-$t*$by;$d=sqrt($dx*$dx+$dy*$dy);
        if ($d < $best) { $best=$d;$bestAlong=$cum+$t*$seg;$bestSeg=$i-1; }
        $cum += $seg;
    }
    return ['lateral_m'=>$best,'ahead_m'=>$bestAlong,'segment'=>$bestSeg];
}

function ep7_bbox(array $route, float $padM): array
{
    $lats=array_map(fn($p)=>(float)$p['lat'],$route);$lons=array_map(fn($p)=>(float)$p['lon'],$route);
    $mid=array_sum($lats)/max(1,count($lats));
    $padLat=$padM/110540.0;$padLon=$padM/(111320.0*max(0.2,cos(deg2rad($mid))));
    return [min($lats)-$padLat,max($lats)+$padLat,min($lons)-$padLon,max($lons)+$padLon];
}

function ep7_confidence(int $confirmations, int $dismissals, int $createdMs, int $expiresMs): array
{
    $now=(int)round(microtime(true)*1000);
    $life=max(1,$expiresMs-$createdMs);$age=max(0,$now-$createdMs);
    $fresh=max(0.0,min(1.0,1.0-$age/$life));
    $score=0.42 + min(0.36,$confirmations*0.09) - min(0.54,$dismissals*0.15) + $fresh*0.18;
    $score=max(0.02,min(0.99,$score));
    $label=$score>=0.82?'ALTA':($score>=0.58?'BOA':($score>=0.38?'MÉDIA':'BAIXA'));
    return ['score'=>round($score,2),'label'=>$label,'freshness'=>round($fresh,2)];
}
