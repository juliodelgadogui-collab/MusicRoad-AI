<?php
declare(strict_types=1);
require_once __DIR__.'/road_safety_pack_helpers.php';

function road_hazard_now(): string { return gmdate('Y-m-d H:i:s'); }

function road_hazard_ensure_tables(): void {
    static $done=false;if($done)return;$done=true;
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
            INDEX idx_road_hazards_uf (uf), INDEX idx_road_hazards_type (type),
            INDEX idx_road_hazards_geo (latitude,longitude), INDEX idx_road_hazards_active (active),
            INDEX idx_road_hazards_last_seen (last_seen)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        db()->exec("CREATE TABLE IF NOT EXISTS road_hazard_sync (
            uf VARCHAR(2) NOT NULL PRIMARY KEY,last_attempt DATETIME NULL,last_success DATETIME NULL,
            item_count INT NOT NULL DEFAULT 0,last_error VARCHAR(500) NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        db()->exec("CREATE TABLE IF NOT EXISTS road_hazard_chunk_sync (
            chunk_key VARCHAR(80) NOT NULL PRIMARY KEY,uf VARCHAR(2) NOT NULL,
            south DOUBLE NOT NULL,west DOUBLE NOT NULL,north DOUBLE NOT NULL,east DOUBLE NOT NULL,
            last_attempt DATETIME NULL,last_success DATETIME NULL,item_count INT NOT NULL DEFAULT 0,last_error VARCHAR(500) NULL,
            INDEX idx_road_hazard_chunk_uf (uf),INDEX idx_road_hazard_chunk_success (last_success)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        try{db()->exec('CREATE INDEX idx_road_hazards_last_seen ON road_hazards(last_seen)');}catch(Throwable $e){}
    }else{
        db()->exec("CREATE TABLE IF NOT EXISTS road_hazards (id INTEGER PRIMARY KEY AUTOINCREMENT,hazard_key TEXT NOT NULL UNIQUE,type TEXT NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL,uf TEXT,road TEXT,speed INTEGER,heading REAL,source TEXT NOT NULL DEFAULT 'OPENSTREETMAP',last_seen TEXT NOT NULL,active INTEGER NOT NULL DEFAULT 1)");
        db()->exec("CREATE TABLE IF NOT EXISTS road_hazard_sync (uf TEXT PRIMARY KEY,last_attempt TEXT,last_success TEXT,item_count INTEGER NOT NULL DEFAULT 0,last_error TEXT)");
        db()->exec("CREATE TABLE IF NOT EXISTS road_hazard_chunk_sync (chunk_key TEXT PRIMARY KEY,uf TEXT NOT NULL,south REAL NOT NULL,west REAL NOT NULL,north REAL NOT NULL,east REAL NOT NULL,last_attempt TEXT,last_success TEXT,item_count INTEGER NOT NULL DEFAULT 0,last_error TEXT)");
        db()->exec('CREATE INDEX IF NOT EXISTS idx_road_hazards_uf ON road_hazards(uf)');db()->exec('CREATE INDEX IF NOT EXISTS idx_road_hazards_type ON road_hazards(type)');db()->exec('CREATE INDEX IF NOT EXISTS idx_road_hazards_geo ON road_hazards(latitude,longitude)');db()->exec('CREATE INDEX IF NOT EXISTS idx_road_hazards_last_seen ON road_hazards(last_seen)');db()->exec('CREATE INDEX IF NOT EXISTS idx_road_hazard_chunk_uf ON road_hazard_chunk_sync(uf)');
    }
}

function road_hazard_overpass_urls(): array {
    global $config;$urls=[];$configured=trim((string)($config['routing']['overpass_url']??''));if($configured!=='')$urls[]=$configured;
    foreach(['https://overpass.private.coffee/api/interpreter','https://overpass-api.de/api/interpreter','https://maps.mail.ru/osm/tools/overpass/api/interpreter','https://overpass.kumi.systems/api/interpreter'] as $u)if(!in_array($u,$urls,true))$urls[]=$u;
    return $urls;
}

function road_hazard_overpass_request(string $query,int $timeout=70): ?array {
    global $config;$timeout=max(20,min(140,$timeout));$body='data='.urlencode($query);$ua=(string)($config['routing']['user_agent']??'EstradaPlay/3.0');
    foreach(road_hazard_overpass_urls() as $url){
        try{
            $headers=['User-Agent: '.$ua,'Accept: application/json','Content-Type: application/x-www-form-urlencoded'];
            if(function_exists('curl_init')){$ch=curl_init($url);curl_setopt_array($ch,[CURLOPT_RETURNTRANSFER=>true,CURLOPT_FOLLOWLOCATION=>true,CURLOPT_CONNECTTIMEOUT=>10,CURLOPT_TIMEOUT=>$timeout,CURLOPT_HTTPHEADER=>$headers,CURLOPT_POST=>true,CURLOPT_POSTFIELDS=>$body,CURLOPT_ENCODING=>'']);$raw=curl_exec($ch);$status=(int)curl_getinfo($ch,CURLINFO_RESPONSE_CODE);curl_close($ch);if(!$raw||$status>=400)continue;}
            else{$ctx=stream_context_create(['http'=>['method'=>'POST','header'=>implode("\r\n",$headers),'content'=>$body,'timeout'=>$timeout,'ignore_errors'=>true]]);$raw=@file_get_contents($url,false,$ctx);if(!$raw)continue;}
            $data=json_decode((string)$raw,true);if(is_array($data)&&isset($data['elements'])&&is_array($data['elements']))return $data;
        }catch(Throwable $e){}
    }
    return null;
}

function road_hazard_bbox_query_values(float $south,float $west,float $north,float $east): string {return ep2_osm_query_for_bbox($south,$west,$north,$east,60);}
function road_hazard_bbox_query(string $uf): string {[$s,$w,$n,$e]=ep2_state_bounds($uf);return road_hazard_bbox_query_values($s,$w,$n,$e);}

function road_hazard_set_sync(string $uf,bool $ok,int $count,string $error=''): void {
    $driver=(string)db()->getAttribute(PDO::ATTR_DRIVER_NAME);$now=road_hazard_now();
    if($driver==='mysql'){$sql='INSERT INTO road_hazard_sync (uf,last_attempt,last_success,item_count,last_error) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE last_attempt=VALUES(last_attempt), last_success=IF(VALUES(last_success) IS NULL,last_success,VALUES(last_success)), item_count=VALUES(item_count), last_error=VALUES(last_error)';db()->prepare($sql)->execute([$uf,$now,$ok?$now:null,$count,$error!==''?$error:null]);}
    else{$stmt=db()->prepare('SELECT uf,last_success FROM road_hazard_sync WHERE uf=?');$stmt->execute([$uf]);$old=$stmt->fetch();if($old)db()->prepare('UPDATE road_hazard_sync SET last_attempt=?,last_success=?,item_count=?,last_error=? WHERE uf=?')->execute([$now,$ok?$now:($old['last_success']??null),$count,$error!==''?$error:null,$uf]);else db()->prepare('INSERT INTO road_hazard_sync (uf,last_attempt,last_success,item_count,last_error) VALUES (?,?,?,?,?)')->execute([$uf,$now,$ok?$now:null,$count,$error!==''?$error:null]);}
}

function road_hazard_collect_elements(array $elements,string $uf,bool $exactArea,array &$items,array &$seen): void {
    foreach($elements as $el){if(count($items)>=250000)break;$h=ep2_osm_to_hazard($el);if($h===null)continue;if(!$exactArea){$guess=ep2_guess_uf((float)$h['lat'],(float)$h['lon']);if($guess!==''&&$guess!==$uf)continue;}ep2_add($items,$seen,$h);}
}

/** Full-state fallback for manual refresh. Automatic national sync uses smaller persistent chunks below. */
function road_hazard_sync_bbox_chunks(string $uf,array &$items,array &$seen): bool {
    [$s,$w,$n,$e]=ep2_state_bounds($uf);$latMid=($s+$n)/2.0;$lon1=$w+($e-$w)/3.0;$lon2=$w+2.0*($e-$w)/3.0;$ok=false;
    foreach([[$s,$w,$latMid,$lon1],[$s,$lon1,$latMid,$lon2],[$s,$lon2,$latMid,$e],[$latMid,$w,$n,$lon1],[$latMid,$lon1,$n,$lon2],[$latMid,$lon2,$n,$e]] as $b){$osm=road_hazard_overpass_request(road_hazard_bbox_query_values($b[0],$b[1],$b[2],$b[3]),55);if(!is_array($osm)||empty($osm['elements']))continue;$ok=true;road_hazard_collect_elements($osm['elements'],$uf,false,$items,$seen);}
    return $ok;
}

function road_hazard_upsert_items(string $uf,array $items,string $seenAt): int {
    $driver=(string)db()->getAttribute(PDO::ATTR_DRIVER_NAME);$stored=0;
    if($driver==='mysql'){
        $up=db()->prepare("INSERT INTO road_hazards (hazard_key,type,latitude,longitude,uf,road,speed,heading,source,last_seen,active) VALUES (?,?,?,?,?,?,?,?,?,?,1) ON DUPLICATE KEY UPDATE type=VALUES(type),latitude=VALUES(latitude),longitude=VALUES(longitude),uf=VALUES(uf),road=VALUES(road),speed=VALUES(speed),heading=VALUES(heading),source=VALUES(source),last_seen=VALUES(last_seen),active=1");
        foreach($items as $h){$up->execute([(string)$h['id'],(string)$h['type'],(float)$h['lat'],(float)$h['lon'],$uf,trim((string)($h['road']??''))?:null,isset($h['speed'])&&is_numeric($h['speed'])?(int)$h['speed']:null,isset($h['heading'])&&is_numeric($h['heading'])?(float)$h['heading']:null,(string)($h['source']??'OPENSTREETMAP'),$seenAt]);$stored++;}
    }else{
        $find=db()->prepare('SELECT id FROM road_hazards WHERE hazard_key=? LIMIT 1');$upd=db()->prepare('UPDATE road_hazards SET type=?,latitude=?,longitude=?,uf=?,road=?,speed=?,heading=?,source=?,last_seen=?,active=1 WHERE id=?');$ins=db()->prepare('INSERT INTO road_hazards (hazard_key,type,latitude,longitude,uf,road,speed,heading,source,last_seen,active) VALUES (?,?,?,?,?,?,?,?,?,?,1)');
        foreach($items as $h){$key=(string)$h['id'];$find->execute([$key]);$id=$find->fetchColumn();$args=[(string)$h['type'],(float)$h['lat'],(float)$h['lon'],$uf,trim((string)($h['road']??''))?:null,isset($h['speed'])&&is_numeric($h['speed'])?(int)$h['speed']:null,isset($h['heading'])&&is_numeric($h['heading'])?(float)$h['heading']:null,(string)($h['source']??'OPENSTREETMAP'),$seenAt];if($id)$upd->execute(array_merge($args,[(int)$id]));else $ins->execute(array_merge([$key],$args));$stored++;}
    }
    return $stored;
}

function road_hazard_store_state_snapshot(string $uf,array $items): int {
    $now=road_hazard_now();db()->beginTransaction();
    try{$stored=road_hazard_upsert_items($uf,$items,$now);db()->prepare("UPDATE road_hazards SET active=0 WHERE UPPER(COALESCE(uf,''))=? AND source='OPENSTREETMAP' AND last_seen < ?")->execute([$uf,$now]);db()->commit();return $stored;}catch(Throwable $e){if(db()->inTransaction())db()->rollBack();throw $e;}
}

function road_hazard_sync_state(string $uf): array {
    road_hazard_ensure_tables();$uf=strtoupper(trim($uf));if(!ep2_valid_uf($uf))return ['ok'=>false,'count'=>0,'error'=>'UF inválida.'];
    $items=[];$seen=[];$mode='area';$selector='area["ISO3166-2"="BR-'.$uf.'"]->.eparea;';$osm=road_hazard_overpass_request(ep2_osm_query_for_area($selector),110);if(is_array($osm)&&!empty($osm['elements']))road_hazard_collect_elements($osm['elements'],$uf,true,$items,$seen);
    if(count($items)===0){$mode='chunks';road_hazard_sync_bbox_chunks($uf,$items,$seen);}
    if(count($items)===0){$old=road_hazard_count_state($uf);road_hazard_set_sync($uf,false,$old,'Nenhum servidor de dados retornou pontos válidos');return ['ok'=>false,'count'=>$old,'error'=>'Não foi possível atualizar agora. A base anterior foi mantida.','mode'=>$mode];}
    try{$stored=road_hazard_store_state_snapshot($uf,$items);}catch(Throwable $e){$old=road_hazard_count_state($uf);road_hazard_set_sync($uf,false,$old,$e->getMessage());return ['ok'=>false,'count'=>$old,'error'=>'Falha ao gravar a base.','mode'=>$mode];}
    road_hazard_set_sync($uf,true,$stored,'');return ['ok'=>true,'count'=>$stored,'error'=>'','mode'=>$mode];
}

/** Adaptive state grid: dense states use smaller cells; very large sparse states use larger cells. */
function road_hazard_state_chunks(string $uf): array {
    $uf=strtoupper($uf);if(!ep2_valid_uf($uf))return[];[$s,$w,$n,$e]=ep2_state_bounds($uf);
    $step=in_array($uf,['SP','RJ','DF'],true)?0.55:(in_array($uf,['AM','PA','MT'],true)?1.50:1.00);
    $out=[];$iy=0;
    for($south=$s;$south<$n-0.000001;$south+=$step,$iy++){
        $north=min($n,$south+$step);$ix=0;
        for($west=$w;$west<$e-0.000001;$west+=$step,$ix++){
            $east=min($e,$west+$step);$key=sprintf('%s_%03d_%03d',$uf,$iy,$ix);
            $out[]=['key'=>$key,'uf'=>$uf,'south'=>$south,'west'=>$west,'north'=>$north,'east'=>$east];
        }
    }
    return $out;
}

function road_hazard_chunk_query(array $chunk): string {
    $uf=(string)$chunk['uf'];$bbox='('.$chunk['south'].','.$chunk['west'].','.$chunk['north'].','.$chunk['east'].')';$filter='(area.eparea)'.$bbox;
    return '[out:json][timeout:60];area["ISO3166-2"="BR-'.$uf.'"]->.eparea;('.ep2_safety_query_body($filter).');out body qt;';
}

function road_hazard_set_chunk_sync(array $chunk,bool $ok,int $count,string $error=''): void {
    $now=road_hazard_now();$driver=(string)db()->getAttribute(PDO::ATTR_DRIVER_NAME);$args=[(string)$chunk['key'],(string)$chunk['uf'],(float)$chunk['south'],(float)$chunk['west'],(float)$chunk['north'],(float)$chunk['east'],$now,$ok?$now:null,$count,$error!==''?$error:null];
    if($driver==='mysql'){
        $sql='INSERT INTO road_hazard_chunk_sync (chunk_key,uf,south,west,north,east,last_attempt,last_success,item_count,last_error) VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE south=VALUES(south),west=VALUES(west),north=VALUES(north),east=VALUES(east),last_attempt=VALUES(last_attempt),last_success=IF(VALUES(last_success) IS NULL,last_success,VALUES(last_success)),item_count=VALUES(item_count),last_error=VALUES(last_error)';db()->prepare($sql)->execute($args);
    }else{
        $q=db()->prepare('SELECT chunk_key,last_success FROM road_hazard_chunk_sync WHERE chunk_key=?');$q->execute([$chunk['key']]);$old=$q->fetch();
        if($old)db()->prepare('UPDATE road_hazard_chunk_sync SET uf=?,south=?,west=?,north=?,east=?,last_attempt=?,last_success=?,item_count=?,last_error=? WHERE chunk_key=?')->execute([(string)$chunk['uf'],(float)$chunk['south'],(float)$chunk['west'],(float)$chunk['north'],(float)$chunk['east'],$now,$ok?$now:($old['last_success']??null),$count,$error!==''?$error:null,(string)$chunk['key']]);
        else db()->prepare('INSERT INTO road_hazard_chunk_sync (chunk_key,uf,south,west,north,east,last_attempt,last_success,item_count,last_error) VALUES (?,?,?,?,?,?,?,?,?,?)')->execute($args);
    }
}

function road_hazard_store_chunk_snapshot(array $chunk,array $items): int {
    $now=road_hazard_now();$uf=(string)$chunk['uf'];db()->beginTransaction();
    try{
        $stored=road_hazard_upsert_items($uf,$items,$now);
        // A successful exact-area chunk is authoritative only inside its own box.
        db()->prepare("UPDATE road_hazards SET active=0 WHERE UPPER(COALESCE(uf,''))=? AND source='OPENSTREETMAP' AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? AND last_seen < ?")
            ->execute([$uf,(float)$chunk['south'],(float)$chunk['north'],(float)$chunk['west'],(float)$chunk['east'],$now]);
        db()->commit();return $stored;
    }catch(Throwable $e){if(db()->inTransaction())db()->rollBack();throw $e;}
}

function road_hazard_sync_chunk(array $chunk): array {
    road_hazard_ensure_tables();$uf=strtoupper((string)($chunk['uf']??''));if(!ep2_valid_uf($uf))return['ok'=>false,'error'=>'UF inválida.'];
    $osm=road_hazard_overpass_request(road_hazard_chunk_query($chunk),75);
    if(!is_array($osm)){road_hazard_set_chunk_sync($chunk,false,0,'Fonte indisponível');return['ok'=>false,'uf'=>$uf,'chunk'=>$chunk['key'],'count'=>0,'error'=>'Fonte indisponível'];}
    $items=[];$seen=[];road_hazard_collect_elements($osm['elements']??[],$uf,true,$items,$seen);
    try{$stored=road_hazard_store_chunk_snapshot($chunk,$items);road_hazard_set_chunk_sync($chunk,true,$stored,'');$total=road_hazard_count_state($uf);road_hazard_set_sync($uf,true,$total,'');return['ok'=>true,'uf'=>$uf,'chunk'=>$chunk['key'],'count'=>$stored,'state_total'=>$total];}
    catch(Throwable $e){road_hazard_set_chunk_sync($chunk,false,0,$e->getMessage());return['ok'=>false,'uf'=>$uf,'chunk'=>$chunk['key'],'count'=>0,'error'=>'Falha ao gravar'];}
}

function road_hazard_chunk_status_map(): array {
    road_hazard_ensure_tables();$out=[];foreach((db()->query('SELECT chunk_key,uf,last_attempt,last_success,item_count,last_error FROM road_hazard_chunk_sync')->fetchAll()?:[]) as $r)$out[(string)$r['chunk_key']]=$r;return$out;
}

function road_hazard_next_chunk_for_sync(?string $onlyUf=null): ?array {
    road_hazard_ensure_tables();$onlyUf=$onlyUf!==null?strtoupper(trim($onlyUf)):null;if($onlyUf!==null&&!ep2_valid_uf($onlyUf))return null;$status=road_hazard_chunk_status_map();$best=null;$bestTs=PHP_INT_MAX;
    $ufs=$onlyUf!==null?[$onlyUf]:ep2_brazil_ufs();
    foreach($ufs as $uf)foreach(road_hazard_state_chunks($uf) as $chunk){$row=$status[$chunk['key']]??null;if(!$row||empty($row['last_success']))return$chunk;$ts=strtotime((string)$row['last_success']);if($ts===false)$ts=0;if($ts<$bestTs){$bestTs=$ts;$best=$chunk;}}
    return$best;
}

function road_hazard_chunk_progress(string $uf): array {
    $uf=strtoupper($uf);$chunks=road_hazard_state_chunks($uf);$status=road_hazard_chunk_status_map();$done=0;$points=0;$oldest=null;
    foreach($chunks as $c){$r=$status[$c['key']]??null;if($r&&!empty($r['last_success'])){$done++;$points+=(int)($r['item_count']??0);$ts=strtotime((string)$r['last_success']);if($ts!==false&&($oldest===null||$ts<$oldest))$oldest=$ts;}}
    return['uf'=>$uf,'done'=>$done,'total'=>count($chunks),'percent'=>count($chunks)>0?round($done*100/count($chunks),1):0,'chunk_points'=>$points,'oldest_success'=>$oldest?gmdate('c',$oldest):null];
}

function road_hazard_count_state(string $uf): int {road_hazard_ensure_tables();$stmt=db()->prepare("SELECT COUNT(*) FROM road_hazards WHERE active=1 AND UPPER(COALESCE(uf,''))=?");$stmt->execute([strtoupper($uf)]);return(int)$stmt->fetchColumn();}
function road_hazard_state_rows(string $uf,int $limit=65000): array {road_hazard_ensure_tables();$limit=max(1,min(150000,$limit));$stmt=db()->prepare("SELECT hazard_key,type,latitude,longitude,uf,road,speed,heading,source,last_seen FROM road_hazards WHERE active=1 AND UPPER(COALESCE(uf,''))=? ORDER BY id ASC LIMIT ".$limit);$stmt->execute([strtoupper($uf)]);return$stmt->fetchAll()?:[];}
function road_hazard_bbox_rows(float $minLat,float $maxLat,float $minLon,float $maxLon,int $limit=30000): array {road_hazard_ensure_tables();$limit=max(1,min(80000,$limit));$stmt=db()->prepare('SELECT hazard_key,type,latitude,longitude,uf,road,speed,heading,source,last_seen FROM road_hazards WHERE active=1 AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? ORDER BY id ASC LIMIT '.$limit);$stmt->execute([$minLat,$maxLat,$minLon,$maxLon]);return$stmt->fetchAll()?:[];}
function road_hazard_stats(?string $uf=null): array {road_hazard_ensure_tables();$where=' WHERE active=1';$params=[];if($uf!==null&&ep2_valid_uf($uf)){$where.=" AND UPPER(COALESCE(uf,''))=?";$params[]=strtoupper($uf);}$stmt=db()->prepare('SELECT type,COUNT(*) total FROM road_hazards'.$where.' GROUP BY type ORDER BY total DESC');$stmt->execute($params);$out=[];foreach(($stmt->fetchAll()?:[])as$r)$out[(string)$r['type']]=(int)$r['total'];return$out;}
function road_hazard_sync_status(): array {road_hazard_ensure_tables();return db()->query('SELECT uf,last_attempt,last_success,item_count,last_error FROM road_hazard_sync ORDER BY uf')->fetchAll()?:[];}

function road_hazard_next_uf_for_sync(): string {
    road_hazard_ensure_tables();$status=[];foreach(road_hazard_sync_status()as$r)$status[strtoupper((string)$r['uf'])]=$r;$best='';$bestAt=PHP_INT_MAX;
    foreach(ep2_brazil_ufs()as$uf){$row=$status[$uf]??null;if(!$row||empty($row['last_success']))return$uf;$ts=strtotime((string)$row['last_success']);if($ts===false)$ts=0;if($ts<$bestAt){$bestAt=$ts;$best=$uf;}}
    return$best!==''?$best:'SP';
}
