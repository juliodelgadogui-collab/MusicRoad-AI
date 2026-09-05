<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
require_login();

header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');

$lat = (float)($_GET['lat'] ?? 0);
$lon = (float)($_GET['lon'] ?? 0);
$radius = max(5000, min(22000, (int)($_GET['radius'] ?? 16000)));
if (!$lat || !$lon || $lat < -90 || $lat > 90 || $lon < -180 || $lon > 180) {
    json_response(['ok' => false, 'error' => 'Localização inválida.'], 422);
}

function ep_haversine(float $lat1, float $lon1, float $lat2, float $lon2): float {
    $r = 6371000.0;
    $p1 = deg2rad($lat1); $p2 = deg2rad($lat2);
    $dp = deg2rad($lat2 - $lat1); $dl = deg2rad($lon2 - $lon1);
    $a = sin($dp/2)*sin($dp/2) + cos($p1)*cos($p2)*sin($dl/2)*sin($dl/2);
    return $r * 2 * atan2(sqrt($a), sqrt(max(0.0, 1.0-$a)));
}
function ep_speed($raw): ?int {
    if ($raw === null || $raw === '') return null;
    if (is_numeric($raw)) { $v=(int)round((float)$raw); return ($v>=10 && $v<=180)?$v:null; }
    if (preg_match('/(\d{2,3})/', (string)$raw, $m)) { $v=(int)$m[1]; return ($v>=10 && $v<=180)?$v:null; }
    return null;
}
function ep_heading($raw): ?float {
    if ($raw === null || $raw === '') return null;
    if (is_numeric($raw)) { $v=fmod((float)$raw,360.0); if($v<0)$v+=360.0; return $v; }
    $v=strtolower(trim((string)$raw));
    $map=['n'=>0,'north'=>0,'ne'=>45,'northeast'=>45,'e'=>90,'east'=>90,'se'=>135,'southeast'=>135,'s'=>180,'south'=>180,'sw'=>225,'southwest'=>225,'w'=>270,'west'=>270,'nw'=>315,'northwest'=>315];
    return array_key_exists($v,$map)?(float)$map[$v]:null;
}
function ep_type(array $tags): ?string {
    $highway = strtolower((string)($tags['highway'] ?? ''));
    $enf = strtolower((string)($tags['enforcement'] ?? ''));
    $calming = strtolower((string)($tags['traffic_calming'] ?? ''));
    $barrier = strtolower((string)($tags['barrier'] ?? ''));
    $rail = strtolower((string)($tags['railway'] ?? ''));
    if ($highway === 'speed_camera' || $enf === 'maxspeed') return 'RADAR';
    if ($highway === 'traffic_signals') return 'SEMAFORO';
    if ($highway === 'speed_bump' || in_array($calming, ['bump','hump','table','cushion','yes'], true)) return 'QUEBRA_MOLAS';
    if ($barrier === 'toll_booth' || strtolower((string)($tags['toll'] ?? '')) === 'yes') return 'PEDAGIO';
    if (in_array($rail, ['level_crossing','crossing'], true)) return 'PASSAGEM_NIVEL';
    return null;
}
function ep_add(array &$items, array &$seen, array $h, float $centerLat, float $centerLon, int $radius): void {
    if (!isset($h['lat'],$h['lon']) || !is_numeric($h['lat']) || !is_numeric($h['lon'])) return;
    $hlat=(float)$h['lat']; $hlon=(float)$h['lon'];
    if (ep_haversine($centerLat,$centerLon,$hlat,$hlon) > $radius + 900) return;
    $type=(string)($h['type'] ?? 'RADAR');
    $id=(string)($h['id'] ?? '');
    if ($id === '') $id='geo-'.$type.'-'.round($hlat,5).'-'.round($hlon,5);
    $key=$type.'|'.round($hlat,5).'|'.round($hlon,5);
    if (isset($seen[$id]) || isset($seen[$key])) return;
    $seen[$id]=true; $seen[$key]=true;
    $items[]=[
        'id'=>$id,
        'type'=>$type,
        'lat'=>$hlat,
        'lon'=>$hlon,
        'road'=>trim((string)($h['road'] ?? '')),
        'speed'=>isset($h['speed']) && is_numeric($h['speed']) ? (int)$h['speed'] : null,
        'heading'=>isset($h['heading']) && is_numeric($h['heading']) ? round((float)$h['heading'],1) : null,
        'source'=>(string)($h['source'] ?? 'BASE_LOCAL'),
    ];
}

$latBox = $radius / 110540.0;
$lonScale = max(0.25, cos(deg2rad($lat)));
$lonBox = $radius / (111320.0 * $lonScale);
$minLat=$lat-$latBox; $maxLat=$lat+$latBox; $minLon=$lon-$lonBox; $maxLon=$lon+$lonBox;

$items=[]; $seen=[]; $localCount=0; $osmOk=false;

try {
    $stmt=db()->prepare('SELECT id, external_id, latitude, longitude, rodovia, heading, sentido, velocidade, tipo, fonte FROM radars WHERE ativo = 1 AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT 2500');
    $stmt->execute([$minLat,$maxLat,$minLon,$maxLon]);
    foreach (($stmt->fetchAll() ?: []) as $r) {
        $heading = is_numeric($r['heading'] ?? null) ? (float)$r['heading'] : ep_heading($r['sentido'] ?? null);
        ep_add($items,$seen,[
            'id'=>!empty($r['external_id'])?(string)$r['external_id']:'db-radar-'.(string)$r['id'],
            'type'=>'RADAR','lat'=>(float)$r['latitude'],'lon'=>(float)$r['longitude'],
            'road'=>$r['rodovia'] ?? '','speed'=>ep_speed($r['velocidade'] ?? null),'heading'=>$heading,
            'source'=>$r['fonte'] ?? 'BASE_LOCAL'
        ],$lat,$lon,$radius);
        $localCount++;
    }
} catch (Throwable $e) {}

try {
    global $config;
    $bbox = implode(',', [number_format($minLat,6,'.',''), number_format($minLon,6,'.',''), number_format($maxLat,6,'.',''), number_format($maxLon,6,'.','')]);
    $q='[out:json][timeout:28];('
        .'node["highway"="speed_camera"]('.$bbox.');'
        .'node["enforcement"="maxspeed"]('.$bbox.');'
        .'node["highway"="traffic_signals"]('.$bbox.');'
        .'node["highway"="speed_bump"]('.$bbox.');'
        .'node["traffic_calming"~"^(bump|hump|table|cushion|yes)$"]('.$bbox.');'
        .'node["barrier"="toll_booth"]('.$bbox.');'
        .'node["railway"~"^(level_crossing|crossing)$"]('.$bbox.');'
        .');out tags;';
    $osm=http_json($config['routing']['overpass_url'],'data='.urlencode($q),['Content-Type: application/x-www-form-urlencoded']);
    if (is_array($osm)) {
        $osmOk=true;
        foreach (($osm['elements'] ?? []) as $el) {
            if (count($items) >= 4800) break;
            $tags=is_array($el['tags'] ?? null)?$el['tags']:[];
            $type=ep_type($tags); if($type===null)continue;
            $rlat=$el['lat'] ?? null; $rlon=$el['lon'] ?? null;
            if(!is_numeric($rlat)||!is_numeric($rlon))continue;
            $road=$tags['road_ref'] ?? ($tags['ref'] ?? ($tags['name'] ?? ''));
            $heading=ep_heading($tags['direction'] ?? ($tags['camera:direction'] ?? null));
            ep_add($items,$seen,[
                'id'=>'osm-'.($el['type'] ?? 'node').'-'.($el['id'] ?? md5((string)$rlat.':'.(string)$rlon)),
                'type'=>$type,'lat'=>(float)$rlat,'lon'=>(float)$rlon,'road'=>$road,
                'speed'=>ep_speed($tags['maxspeed'] ?? null),'heading'=>$heading,'source'=>'OPENSTREETMAP'
            ],$lat,$lon,$radius);
        }
    }
} catch (Throwable $e) {}

usort($items, function(array $a,array $b) use($lat,$lon): int {
    return ep_haversine($lat,$lon,(float)$a['lat'],(float)$a['lon']) <=> ep_haversine($lat,$lon,(float)$b['lat'],(float)$b['lon']);
});

json_response([
    'ok'=>true,
    'version'=>'1.0',
    'center'=>['lat'=>$lat,'lon'=>$lon],
    'radius_m'=>$radius,
    'generated_at'=>gmdate('c'),
    'expires_in_s'=>604800,
    'hazards'=>$items,
    'coverage'=>['local_radars'=>$localCount,'osm_ok'=>$osmOk,'total'=>count($items),'truncated'=>count($items)>=4800],
]);
