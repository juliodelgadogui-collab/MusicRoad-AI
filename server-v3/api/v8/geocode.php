<?php
declare(strict_types=1);
require_once __DIR__ . '/../../bootstrap.php';
$user = v3_require_user_json();
$q = trim((string)($_GET['q'] ?? ''));
if (mb_strlen($q) < 2 || mb_strlen($q) > 120) v3_json(['ok'=>false,'error'=>'Digite um destino válido.'],422);

$last = (float)($_SESSION['v3_geocode_at'] ?? 0);
$now = microtime(true);
if ($last > 0 && $now - $last < 0.8) v3_json(['ok'=>false,'error'=>'Aguarde um instante antes de pesquisar novamente.'],429);
$_SESSION['v3_geocode_at'] = $now;

$results = [];
$token = v3_mapbox_token();
if ($token !== '') {
    $url = 'https://api.mapbox.com/geocoding/v5/mapbox.places/' . rawurlencode($q) . '.json?' . http_build_query([
        'access_token'=>$token,'country'=>'br','language'=>'pt','limit'=>5,'types'=>'address,place,locality,poi,district,postcode'
    ],'', '&', PHP_QUERY_RFC3986);
    $data = v3_http_json($url, [], 20);
    foreach (($data['features'] ?? []) as $f) {
        $center = $f['center'] ?? null;
        if (!is_array($center) || !isset($center[0],$center[1])) continue;
        $lat=(float)$center[1];$lon=(float)$center[0];
        if (!v3_coord($lat,$lon)) continue;
        $results[]=['lat'=>$lat,'lon'=>$lon,'name'=>(string)($f['place_name']??$f['text']??$q),'provider'=>'mapbox'];
    }
}

if (!$results) {
    $url = 'https://nominatim.openstreetmap.org/search?' . http_build_query([
        'format'=>'jsonv2','q'=>$q,'countrycodes'=>'br','limit'=>5,'addressdetails'=>1
    ],'', '&', PHP_QUERY_RFC3986);
    $data = v3_http_json($url, ['Accept-Language: pt-BR,pt;q=0.9'], 20);
    foreach (($data ?? []) as $r) {
        $lat=(float)($r['lat']??NAN);$lon=(float)($r['lon']??NAN);
        if (!v3_coord($lat,$lon)) continue;
        $results[]=['lat'=>$lat,'lon'=>$lon,'name'=>(string)($r['display_name']??$q),'provider'=>'nominatim'];
    }
}

v3_json(['ok'=>true,'version'=>EPC_V3_VERSION,'results'=>$results,'account'=>v3_account($user)]);
