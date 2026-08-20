<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';

$user = require_login();
$action = (string)($_GET['action'] ?? 'near');
$method = strtoupper((string)($_SERVER['REQUEST_METHOD'] ?? 'GET'));

function radar_valid_coordinates(mixed $lat, mixed $lon): bool
{
    return is_numeric($lat) && is_numeric($lon)
        && (float)$lat >= -90 && (float)$lat <= 90
        && (float)$lon >= -180 && (float)$lon <= 180;
}

function radar_coordinates_in_brazil(float $lat,float $lon): bool
{
    return $lat>=-34.8&&$lat<=6.5&&$lon>=-74.8&&$lon<=-32.5;
}

function radar_nearby_box(float $lat, float $lon, int $radius): array
{
    $latDelta = $radius / 111320;
    $cosine = max(0.15, abs(cos(deg2rad($lat))));
    $lonDelta = $radius / (111320 * $cosine);
    return [$lat - $latDelta, $lat + $latDelta, $lon - $lonDelta, $lon + $lonDelta];
}

function radar_fetch_local(float $lat, float $lon, int $radius): array
{
    [$minLat,$maxLat,$minLon,$maxLon] = radar_nearby_box($lat,$lon,$radius);
    $stmt = db()->prepare('SELECT * FROM radars WHERE ativo=1 AND (expires_at IS NULL OR expires_at>=CURRENT_TIMESTAMP) AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT 2000');
    $stmt->execute([$minLat,$maxLat,$minLon,$maxLon]);
    return array_values(array_filter($stmt->fetchAll(), static fn(array $row): bool =>
        haversine_m($lat,$lon,(float)$row['latitude'],(float)$row['longitude']) <= $radius
    ));
}

function radar_osm_import_allowed(): bool
{
    $now = time();
    $attempts = array_values(array_filter((array)($_SESSION['radar_osm_imports'] ?? []),static fn($value): bool => is_numeric($value) && (int)$value >= $now - 60));
    if (count($attempts) >= 4) return false;
    $attempts[] = $now;
    $_SESSION['radar_osm_imports'] = $attempts;
    return true;
}

function radar_import_osm_near(float $lat, float $lon, int $radius): int
{
    global $config;
    [$minLat,$maxLat,$minLon,$maxLon] = radar_nearby_box($lat,$lon,$radius);
    $bbox = implode(',',[$minLat,$minLon,$maxLat,$maxLon]);
    $query = '[out:json][timeout:25];(node["highway"="speed_camera"]('.$bbox.');way["highway"="speed_camera"]('.$bbox.');relation["highway"="speed_camera"]('.$bbox.'););out center tags;';
    $osm = http_json((string)$config['routing']['overpass_url'],'data='.urlencode($query),['Content-Type: application/x-www-form-urlencoded']);
    if (!is_array($osm)) return 0;
    $exists = db()->prepare('SELECT id FROM radars WHERE external_id=? LIMIT 1');
    $insert = db()->prepare('INSERT INTO radars (external_id,latitude,longitude,uf,cidade,rodovia,km,sentido,heading,velocidade,tipo,situacao,fonte,data_fonte,data_importacao,confiabilidade,quantidade_fontes,ativo) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,?,?,1)');
    $created = 0;
    foreach (($osm['elements'] ?? []) as $element) {
        if (!is_array($element)) continue;
        $radarLat = $element['lat'] ?? ($element['center']['lat'] ?? null);
        $radarLon = $element['lon'] ?? ($element['center']['lon'] ?? null);
        if (!radar_valid_coordinates($radarLat,$radarLon)) continue;
        $tags = is_array($element['tags'] ?? null) ? $element['tags'] : [];
        $externalId = 'osm-'.($element['type'] ?? 'node').'-'.($element['id'] ?? sha1((string)$radarLat.'|'.(string)$radarLon));
        $exists->execute([$externalId]);
        if ($exists->fetchColumn()) continue;
        $speedText = preg_replace('/\D+/', '', (string)($tags['maxspeed'] ?? '')) ?? '';
        $speed = $speedText !== '' ? max(10,min(180,(int)$speedText)) : null;
        $insert->execute([
            $externalId,(float)$radarLat,(float)$radarLon,null,$tags['addr:city'] ?? null,
            $tags['road_ref'] ?? ($tags['ref'] ?? null),$tags['distance'] ?? null,$tags['direction'] ?? null,
            null,$speed,'RADAR_FIXO','ATIVO','OPENSTREETMAP',null,'MÉDIA',1,
        ]);
        $created++;
    }
    return $created;
}

if ($action === 'near') {
    if ($method !== 'GET') json_response(['ok'=>false,'error'=>'Use GET.'],405);
    require_session_rate_limit('radars-near',120,60);
    $lat = $_GET['lat'] ?? null;
    $lon = $_GET['lon'] ?? null;
    if (!radar_valid_coordinates($lat,$lon)) json_response(['ok'=>false,'error'=>'Latitude e longitude válidas são obrigatórias.'],422);
    $lat = (float)$lat;
    $lon = (float)$lon;
    if(!radar_coordinates_in_brazil($lat,$lon))json_response(['ok'=>false,'error'=>'A consulta de fiscalização está disponível no território brasileiro.'],422);
    $radius = min(50000,max(1000,(int)($_GET['radius'] ?? 10000)));
    $items = radar_fetch_local($lat,$lon,$radius);
    $imported = 0;
    $allowImport=!$items&&radar_osm_import_allowed();
    $csrf=csrf_token();
    session_write_close();
    if ($allowImport) {
        try { $imported = radar_import_osm_near($lat,$lon,min($radius,12000)); } catch (Throwable $e) {}
        if ($imported > 0) $items = radar_fetch_local($lat,$lon,$radius);
    }
    json_response([
        'ok'=>true,
        'radars'=>$items,
        'csrf'=>$csrf,
        'source'=>$imported > 0 ? 'local+openstreetmap' : 'local',
        'message'=>$items ? 'Consulta concluída.' : 'Nenhum ponto encontrado neste raio.',
    ]);
}

if ($action === 'admin_summary') {
    require_admin();
    if ($method !== 'GET') json_response(['ok'=>false,'error'=>'Use GET.'],405);
    $summary = [
        'total'=>(int)db()->query('SELECT COUNT(*) FROM radars')->fetchColumn(),
        'ativos'=>(int)db()->query('SELECT COUNT(*) FROM radars WHERE ativo=1 AND (expires_at IS NULL OR expires_at>=CURRENT_TIMESTAMP)')->fetchColumn(),
        'inativos'=>(int)db()->query('SELECT COUNT(*) FROM radars WHERE ativo=0')->fetchColumn(),
        'pendentes'=>(int)db()->query("SELECT COUNT(*) FROM road_reports WHERE status IN ('PENDENTE','NAO_CONFIRMADO')")->fetchColumn(),
        'sem_velocidade'=>(int)db()->query('SELECT COUNT(*) FROM radars WHERE velocidade IS NULL')->fetchColumn(),
        'sem_sentido'=>(int)db()->query("SELECT COUNT(*) FROM radars WHERE sentido IS NULL OR sentido=''")->fetchColumn(),
        'por_estado'=>db()->query('SELECT uf,COUNT(*) total FROM radars GROUP BY uf ORDER BY total DESC')->fetchAll(),
        'por_fonte'=>db()->query('SELECT fonte,COUNT(*) total FROM radars GROUP BY fonte ORDER BY total DESC')->fetchAll(),
    ];
    json_response(['ok'=>true,'summary'=>$summary]);
}

if ($action === 'save') {
    if ($method !== 'POST') json_response(['ok'=>false,'error'=>'Use POST.'],405);
    require_csrf();
    $data = input_json();
    if (!radar_valid_coordinates($data['latitude'] ?? null,$data['longitude'] ?? null)) {
        json_response(['ok'=>false,'error'=>'Posição inválida. Ative o GPS e tente novamente.'],422);
    }
    $allowedTypes = ['RADAR_FIXO','RADAR_MOVEL','FISCALIZACAO_PORTATIL','VIDEOMONITORAMENTO','FISCALIZACAO_SEMAFORICA','SEMAFORO_RADAR','SEMAFORO_CAMERA','LOMBADA_ELETRONICA','QUEBRA_MOLA','ACIDENTE','OBRA','BURACO'];
    $type = strtoupper(trim((string)($data['tipo'] ?? 'RADAR_FIXO')));
    if (!in_array($type,$allowedTypes,true)) json_response(['ok'=>false,'error'=>'Tipo de ponto inválido.'],422);

    $userId = (int)$user['id'];
    $rate = db()->prepare('SELECT COUNT(*) FROM road_reports WHERE user_id=? AND created_at>=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 24 HOUR)');
    $rate->execute([$userId]);
    if ((int)$rate->fetchColumn() >= 25) json_response(['ok'=>false,'error'=>'Limite diário de relatos atingido. Tente novamente amanhã.'],429);

    $lat = (float)$data['latitude'];
    $lon = (float)$data['longitude'];
    if(!radar_coordinates_in_brazil($lat,$lon))json_response(['ok'=>false,'error'=>'O relato precisa estar no território brasileiro.'],422);
    [$minLat,$maxLat,$minLon,$maxLon] = radar_nearby_box($lat,$lon,50);
    $duplicate = db()->prepare("SELECT id,status,latitude,longitude FROM road_reports WHERE user_id=? AND type=? AND status IN ('PENDENTE','NAO_CONFIRMADO','APROVADO') AND created_at>=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 24 HOUR) AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? ORDER BY id DESC LIMIT 10");
    $duplicate->execute([$userId,$type,$minLat,$maxLat,$minLon,$maxLon]);
    foreach ($duplicate->fetchAll() as $candidate) {
        if (haversine_m($lat,$lon,(float)$candidate['latitude'],(float)$candidate['longitude']) <= 50) {
            json_response(['ok'=>true,'accepted'=>true,'duplicate'=>true,'report_id'=>(int)$candidate['id'],'status'=>$candidate['status'],'message'=>'Este ponto já foi enviado e continua em análise.'],202);
        }
    }

    $speed = isset($data['velocidade']) && is_numeric($data['velocidade']) ? (int)$data['velocidade'] : null;
    if ($speed !== null && ($speed < 10 || $speed > 180)) $speed = null;
    $heading = isset($data['heading']) && is_numeric($data['heading']) ? fmod(((float)$data['heading'] + 3600.0),360.0) : null;
    $externalId = null;
    $payload = [
        'cidade'=>mb_substr(trim((string)($data['cidade'] ?? '')),0,190),
        'uf'=>preg_match('/^[A-Z]{2}$/',strtoupper(trim((string)($data['uf']??''))))?strtoupper(trim((string)$data['uf'])):'',
        'rodovia'=>mb_substr(trim((string)($data['rodovia'] ?? '')),0,100),
        'km'=>mb_substr(trim((string)($data['km'] ?? '')),0,40),
        'sentido'=>mb_substr(trim((string)($data['sentido'] ?? '')),0,100),
    ];
    $stmt = db()->prepare("INSERT INTO road_reports (user_id,external_id,latitude,longitude,type,speed,heading,source,payload_json,status,confirmations,expires_at,created_at) VALUES (?,?,?,?,?,?,?,'MUSICROAD_COMUNIDADE',?,'PENDENTE',0,DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 90 DAY),CURRENT_TIMESTAMP)");
    $stmt->execute([$userId,$externalId,$lat,$lon,$type,$speed,$heading,json_encode($payload,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES)]);
    $reportId = (int)db()->lastInsertId();
    audit_log('road_report.created',['report_id'=>$reportId,'type'=>$type]);
    json_response(['ok'=>true,'accepted'=>true,'report_id'=>$reportId,'status'=>'PENDENTE','message'=>'Ponto enviado para validação. Obrigado por colaborar.'],202);
}

json_response(['ok'=>false,'error'=>'Ação inválida.'],400);
