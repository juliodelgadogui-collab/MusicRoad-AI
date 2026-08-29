<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';
if (!native_restore_user_from_request(null)) json_response(['ok'=>false,'error'=>'Sessão expirada. Entre novamente neste aparelho.'],401);
require __DIR__ . '/radar_db.php';
radar_ensure_tables();

$action = $_GET['action'] ?? 'near';

if ($action === 'near') {
    $lat = (float)($_GET['lat'] ?? 0);
    $lon = (float)($_GET['lon'] ?? 0);
    if (!$lat || !$lon || abs($lat)>90 || abs($lon)>180) {
        json_response(['ok' => false, 'error' => 'Latitude e longitude são obrigatórias.'], 422);
    }
    $radius = min(50000, max(1000, (int)($_GET['radius'] ?? 10000)));
    $latBox = $radius / 110540.0;
    $lonBox = $radius / (111320.0 * max(0.25, cos(deg2rad($lat))));
    $stmt = db()->prepare('SELECT * FROM radars WHERE ativo = 1 AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT 1500');
    $stmt->execute([$lat - $latBox, $lat + $latBox, $lon - $lonBox, $lon + $lonBox]);
    $items = array_values(array_filter($stmt->fetchAll(), fn($r) => ep2_haversine($lat, $lon, (float)$r['latitude'], (float)$r['longitude']) <= $radius));

    $osmImported=0;
    $osmOk=false;
    if (!$items || isset($_GET['refresh'])) {
        $minLat = $lat - $latBox;
        $maxLat = $lat + $latBox;
        $minLon = $lon - $lonBox;
        $maxLon = $lon + $lonBox;
        $bbox = implode(',', [$minLat, $minLon, $maxLat, $maxLon]);
        $query = '[out:json][timeout:35];('
            .'node["highway"="speed_camera"](' . $bbox . ');'
            .'node["enforcement"="maxspeed"](' . $bbox . ');'
            .');out tags;';
        $osm = ep2_overpass_json($query,55);
        if(is_array($osm)){
            $osmOk=true;
            foreach (($osm['elements'] ?? []) as $el) {
                $rlat = $el['lat'] ?? null;
                $rlon = $el['lon'] ?? null;
                if (!is_numeric($rlat) || !is_numeric($rlon)) continue;
                $rlat=(float)$rlat;$rlon=(float)$rlon;
                if(ep2_haversine($lat,$lon,$rlat,$rlon)>$radius+800)continue;
                $tags = is_array($el['tags'] ?? null)?$el['tags']:[];
                $externalId = 'osm-' . ($el['type'] ?? 'node') . '-' . ($el['id'] ?? md5((string)$rlat . ':' . (string)$rlon));
                $exists = db()->prepare('SELECT id FROM radars WHERE external_id = ? LIMIT 1');
                $exists->execute([$externalId]);
                if (!$exists->fetchColumn()) {
                    $rawSpeed=ep2_speed($tags['maxspeed']??null);
                    $heading=ep2_heading($tags['direction']??($tags['camera:direction']??null));
                    $uf=radar_normalize_uf(null,$rlat,$rlon);
                    $insert = db()->prepare('INSERT INTO radars (external_id, latitude, longitude, uf, cidade, rodovia, km, sentido, heading, velocidade, tipo, situacao, fonte, data_fonte, data_importacao, confiabilidade, quantidade_fontes, ativo) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)');
                    $insert->execute([
                        $externalId,$rlat,$rlon,$uf,
                        $tags['addr:city'] ?? null,
                        $tags['road_ref'] ?? ($tags['ref'] ?? ($tags['name'] ?? null)),
                        null,$tags['direction'] ?? null,$heading,$rawSpeed,
                        'RADAR_FIXO','ATIVO','OPENSTREETMAP',null,radar_now(),'MÉDIA',1,1,
                    ]);
                    $osmImported++;
                }else{
                    $uf=radar_normalize_uf(null,$rlat,$rlon);
                    if($uf!==null){
                        $u=db()->prepare("UPDATE radars SET uf=COALESCE(NULLIF(uf,''),?) WHERE external_id=?");
                        $u->execute([$uf,$externalId]);
                    }
                }
            }
        }
        $stmt->execute([$lat - $latBox, $lat + $latBox, $lon - $lonBox, $lon + $lonBox]);
        $items = array_values(array_filter($stmt->fetchAll(), fn($r) => ep2_haversine($lat, $lon, (float)$r['latitude'], (float)$r['longitude']) <= $radius));
    }
    json_response([
        'ok' => true,
        'radars' => $items,
        'csrf' => csrf_token(),
        'coverage'=>['uf'=>ep2_guess_uf($lat,$lon),'osm_ok'=>$osmOk,'osm_imported'=>$osmImported,'total'=>count($items)],
        'message' => $items ? 'Consulta feita na base local/OpenStreetMap.' : 'Nenhum radar encontrado próximo neste raio.'
    ]);
}

if ($action === 'admin_summary') {
    if (($user['role'] ?? '') !== 'admin') json_response(['ok'=>false,'error'=>'Acesso negado.'],403);
    $summary = [
        'total' => (int)db()->query('SELECT COUNT(*) FROM radars')->fetchColumn(),
        'ativos' => (int)db()->query('SELECT COUNT(*) FROM radars WHERE ativo = 1')->fetchColumn(),
        'inativos' => (int)db()->query('SELECT COUNT(*) FROM radars WHERE ativo = 0')->fetchColumn(),
        'sem_velocidade' => (int)db()->query('SELECT COUNT(*) FROM radars WHERE velocidade IS NULL')->fetchColumn(),
        'sem_sentido' => (int)db()->query("SELECT COUNT(*) FROM radars WHERE sentido IS NULL OR sentido = ''")->fetchColumn(),
        'por_estado' => db()->query("SELECT COALESCE(NULLIF(UPPER(uf),''),'SEM UF') uf, COUNT(*) total FROM radars GROUP BY COALESCE(NULLIF(UPPER(uf),''),'SEM UF') ORDER BY total DESC")->fetchAll(),
        'por_fonte' => db()->query('SELECT fonte, COUNT(*) total FROM radars GROUP BY fonte ORDER BY total DESC')->fetchAll(),
    ];
    json_response(['ok' => true, 'summary' => $summary]);
}

if ($action === 'save') {
    if (($user['role'] ?? '') !== 'admin') json_response(['ok'=>false,'error'=>'Acesso negado.'],403);
    require_csrf();
    $data = input_json();
    $lat=(float)($data['latitude']??0);$lon=(float)($data['longitude']??0);
    if(!$lat||!$lon||abs($lat)>90||abs($lon)>180)json_response(['ok'=>false,'error'=>'Coordenadas inválidas.'],422);
    $uf=radar_normalize_uf($data['uf']??null,$lat,$lon);
    $stmt = db()->prepare('INSERT INTO radars (external_id, latitude, longitude, uf, cidade, rodovia, km, sentido, heading, velocidade, tipo, situacao, fonte, data_fonte, data_importacao, confiabilidade, quantidade_fontes, ativo) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)');
    $stmt->execute([
        $data['external_id'] ?? null,$lat,$lon,$uf,
        $data['cidade'] ?? null,$data['rodovia'] ?? null,$data['km'] ?? null,$data['sentido'] ?? null,
        $data['heading'] ?? null,$data['velocidade'] ?? null,$data['tipo'] ?? 'RADAR_FIXO',
        $data['situacao'] ?? 'ATIVO',$data['fonte'] ?? 'USUARIO',$data['data_fonte'] ?? null,
        radar_now(),$data['confiabilidade'] ?? 'BAIXA',max(1,(int)($data['quantidade_fontes'] ?? 1)),!empty($data['ativo'])?1:0,
    ]);
    json_response(['ok' => true, 'id' => (int)db()->lastInsertId(), 'uf'=>$uf]);
}

json_response(['ok' => false, 'error' => 'Ação inválida.'], 400);
