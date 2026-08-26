<?php
declare(strict_types=1);
require_once __DIR__.'/road_safety_pack_helpers.php';

function road_hazard_now(): string { return gmdate('Y-m-d H:i:s'); }

function road_hazard_ensure_tables(): void {
    static $done=false;
    if($done)return;
    $done=true;
    $driver=(string)db()->getAttribute(PDO::ATTR_DRIVER_NAME);
    if($driver==='mysql'){
        db()->exec("CREATE TABLE IF NOT EXISTS road_hazards (
            id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
            hazard_key VARCHAR(190) NOT NULL,
            type VARCHAR(40) NOT NULL,
            latitude DOUBLE NOT NULL,
            longitude DOUBLE NOT NULL,
            uf VARCHAR(2) NULL,
            road VARCHAR(190) NULL,
            speed INT NULL,
            heading DOUBLE NULL,
            source VARCHAR(120) NOT NULL DEFAULT 'OPENSTREETMAP',
            last_seen DATETIME NOT NULL,
            active TINYINT(1) NOT NULL DEFAULT 1,
            UNIQUE KEY uq_road_hazards_key (hazard_key),
            INDEX idx_road_hazards_uf (uf),
            INDEX idx_road_hazards_type (type),
            INDEX idx_road_hazards_geo (latitude,longitude),
            INDEX idx_road_hazards_active (active)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        db()->exec("CREATE TABLE IF NOT EXISTS road_hazard_sync (
            uf VARCHAR(2) NOT NULL PRIMARY KEY,
            last_attempt DATETIME NULL,
            last_success DATETIME NULL,
            item_count INT NOT NULL DEFAULT 0,
            last_error VARCHAR(500) NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
    }else{
        db()->exec("CREATE TABLE IF NOT EXISTS road_hazards (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            hazard_key TEXT NOT NULL UNIQUE,
            type TEXT NOT NULL,
            latitude REAL NOT NULL,
            longitude REAL NOT NULL,
            uf TEXT,
            road TEXT,
            speed INTEGER,
            heading REAL,
            source TEXT NOT NULL DEFAULT 'OPENSTREETMAP',
            last_seen TEXT NOT NULL,
            active INTEGER NOT NULL DEFAULT 1
        )");
        db()->exec("CREATE TABLE IF NOT EXISTS road_hazard_sync (
            uf TEXT PRIMARY KEY,
            last_attempt TEXT,
            last_success TEXT,
            item_count INTEGER NOT NULL DEFAULT 0,
            last_error TEXT
        )");
        db()->exec('CREATE INDEX IF NOT EXISTS idx_road_hazards_uf ON road_hazards(uf)');
        db()->exec('CREATE INDEX IF NOT EXISTS idx_road_hazards_type ON road_hazards(type)');
        db()->exec('CREATE INDEX IF NOT EXISTS idx_road_hazards_geo ON road_hazards(latitude,longitude)');
    }
}

function road_hazard_bbox_query(string $uf): string {
    [$south,$west,$north,$east]=ep2_state_bounds($uf);
    $bbox=$south.','.$west.','.$north.','.$east;
    return '[out:json][timeout:120];('
        .'node["highway"="speed_camera"]('.$bbox.');'
        .'node["enforcement"="maxspeed"]('.$bbox.');'
        .'node["highway"="traffic_signals"]('.$bbox.');'
        .'node["highway"="speed_bump"]('.$bbox.');'
        .'node["traffic_calming"~"^(bump|hump|table|cushion|yes)$"]('.$bbox.');'
        .'node["barrier"="toll_booth"]('.$bbox.');'
        .'node["railway"~"^(level_crossing|crossing)$"]('.$bbox.');'
        .');out body qt;';
}

function road_hazard_set_sync(string $uf,bool $ok,int $count,string $error=''): void {
    $driver=(string)db()->getAttribute(PDO::ATTR_DRIVER_NAME);
    $now=road_hazard_now();
    if($driver==='mysql'){
        $sql='INSERT INTO road_hazard_sync (uf,last_attempt,last_success,item_count,last_error) VALUES (?,?,?,?,?) '
            .'ON DUPLICATE KEY UPDATE last_attempt=VALUES(last_attempt), last_success=IF(VALUES(last_success) IS NULL,last_success,VALUES(last_success)), item_count=VALUES(item_count), last_error=VALUES(last_error)';
        $stmt=db()->prepare($sql);
        $stmt->execute([$uf,$now,$ok?$now:null,$count,$error!==''?$error:null]);
    }else{
        $stmt=db()->prepare('SELECT uf,last_success FROM road_hazard_sync WHERE uf=?');$stmt->execute([$uf]);$old=$stmt->fetch();
        if($old){
            $stmt=db()->prepare('UPDATE road_hazard_sync SET last_attempt=?, last_success=?, item_count=?, last_error=? WHERE uf=?');
            $stmt->execute([$now,$ok?$now:($old['last_success']??null),$count,$error!==''?$error:null,$uf]);
        }else{
            $stmt=db()->prepare('INSERT INTO road_hazard_sync (uf,last_attempt,last_success,item_count,last_error) VALUES (?,?,?,?,?)');
            $stmt->execute([$uf,$now,$ok?$now:null,$count,$error!==''?$error:null]);
        }
    }
}

function road_hazard_sync_state(string $uf): array {
    road_hazard_ensure_tables();
    $uf=strtoupper(trim($uf));
    if(!in_array($uf,['SP','RJ','MG','ES'],true))return ['ok'=>false,'count'=>0,'error'=>'UF inválida.'];

    $osm=null;$mode='area';
    try{
        $selector='area["ISO3166-2"="BR-'.$uf.'"][boundary="administrative"]->.eparea;';
        $osm=ep2_overpass_json(ep2_osm_query_for_area($selector),$uf==='ES'?120:105);
        if(!is_array($osm) || empty($osm['elements'])){
            $mode='bbox';
            $osm=ep2_overpass_json(road_hazard_bbox_query($uf),$uf==='ES'?130:115);
        }
    }catch(Throwable $e){
        $osm=null;
    }
    if(!is_array($osm) || !isset($osm['elements']) || !is_array($osm['elements'])){
        road_hazard_set_sync($uf,false,road_hazard_count_state($uf),'OpenStreetMap indisponível');
        return ['ok'=>false,'count'=>road_hazard_count_state($uf),'error'=>'OpenStreetMap indisponível. A base anterior foi mantida.','mode'=>$mode];
    }

    $items=[];$seen=[];
    foreach($osm['elements'] as $el){
        if(count($items)>=65000)break;
        $h=ep2_osm_to_hazard($el);if($h===null)continue;
        $guess=ep2_guess_uf((float)$h['lat'],(float)$h['lon']);
        if($guess!=='' && $guess!==$uf)continue;
        ep2_add($items,$seen,$h);
    }
    if(count($items)===0){
        road_hazard_set_sync($uf,false,road_hazard_count_state($uf),'Sincronização retornou zero itens');
        return ['ok'=>false,'count'=>road_hazard_count_state($uf),'error'=>'A consulta retornou zero alertas. A base anterior foi mantida.','mode'=>$mode];
    }

    $now=road_hazard_now();
    db()->beginTransaction();
    try{
        $del=db()->prepare("DELETE FROM road_hazards WHERE UPPER(COALESCE(uf,''))=? AND source='OPENSTREETMAP'");
        $del->execute([$uf]);
        $ins=db()->prepare('INSERT INTO road_hazards (hazard_key,type,latitude,longitude,uf,road,speed,heading,source,last_seen,active) VALUES (?,?,?,?,?,?,?,?,?,?,1)');
        foreach($items as $h){
            $ins->execute([
                (string)$h['id'],(string)$h['type'],(float)$h['lat'],(float)$h['lon'],$uf,
                trim((string)($h['road']??''))?:null,
                isset($h['speed'])&&is_numeric($h['speed'])?(int)$h['speed']:null,
                isset($h['heading'])&&is_numeric($h['heading'])?(float)$h['heading']:null,
                'OPENSTREETMAP',$now
            ]);
        }
        db()->commit();
    }catch(Throwable $e){
        if(db()->inTransaction())db()->rollBack();
        road_hazard_set_sync($uf,false,road_hazard_count_state($uf),$e->getMessage());
        return ['ok'=>false,'count'=>road_hazard_count_state($uf),'error'=>'Falha ao gravar a base: '.$e->getMessage(),'mode'=>$mode];
    }
    road_hazard_set_sync($uf,true,count($items),'');
    return ['ok'=>true,'count'=>count($items),'error'=>'','mode'=>$mode];
}

function road_hazard_count_state(string $uf): int {
    road_hazard_ensure_tables();
    $stmt=db()->prepare("SELECT COUNT(*) FROM road_hazards WHERE active=1 AND UPPER(COALESCE(uf,''))=?");
    $stmt->execute([strtoupper($uf)]);
    return (int)$stmt->fetchColumn();
}

function road_hazard_state_rows(string $uf,int $limit=65000): array {
    road_hazard_ensure_tables();
    $limit=max(1,min(65000,$limit));
    $stmt=db()->prepare("SELECT hazard_key,type,latitude,longitude,uf,road,speed,heading,source,last_seen FROM road_hazards WHERE active=1 AND UPPER(COALESCE(uf,''))=? ORDER BY id ASC LIMIT ".$limit);
    $stmt->execute([strtoupper($uf)]);
    return $stmt->fetchAll()?:[];
}

function road_hazard_bbox_rows(float $minLat,float $maxLat,float $minLon,float $maxLon,int $limit=30000): array {
    road_hazard_ensure_tables();
    $limit=max(1,min(30000,$limit));
    $stmt=db()->prepare('SELECT hazard_key,type,latitude,longitude,uf,road,speed,heading,source,last_seen FROM road_hazards WHERE active=1 AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? ORDER BY id ASC LIMIT '.$limit);
    $stmt->execute([$minLat,$maxLat,$minLon,$maxLon]);
    return $stmt->fetchAll()?:[];
}

function road_hazard_stats(?string $uf=null): array {
    road_hazard_ensure_tables();
    $where=' WHERE active=1';$params=[];
    if($uf!==null && in_array(strtoupper($uf),['SP','RJ','MG','ES'],true)){$where.=" AND UPPER(COALESCE(uf,''))=?";$params[]=strtoupper($uf);}
    $stmt=db()->prepare('SELECT type,COUNT(*) total FROM road_hazards'.$where.' GROUP BY type ORDER BY total DESC');
    $stmt->execute($params);
    $out=[];foreach(($stmt->fetchAll()?:[]) as $r)$out[(string)$r['type']]=(int)$r['total'];
    return $out;
}

function road_hazard_sync_status(): array {
    road_hazard_ensure_tables();
    return db()->query('SELECT uf,last_attempt,last_success,item_count,last_error FROM road_hazard_sync ORDER BY uf')->fetchAll()?:[];
}
