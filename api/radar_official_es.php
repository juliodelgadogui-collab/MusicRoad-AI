<?php

function mr_http_text(string $url, int $timeout = 25): ?string
{
    global $config;
    $ua = $config['routing']['user_agent'] ?? 'MusicRoadAI/1.2.0';$maxBytes=50331648;
    if (function_exists('curl_init')) {
        $ch = curl_init($url);$raw='';$tooLarge=false;
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => false,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_CONNECTTIMEOUT => 8,
            CURLOPT_TIMEOUT => $timeout,
            CURLOPT_HTTPHEADER => [
                'User-Agent: ' . $ua,
                'Accept: text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.5',
            ],
            CURLOPT_WRITEFUNCTION => static function($handle,string $chunk)use(&$raw,&$tooLarge,$maxBytes):int{$length=strlen($chunk);if(strlen($raw)+$length>$maxBytes){$tooLarge=true;return 0;}$raw.=$chunk;return $length;},
        ]);
        $success = curl_exec($ch);
        $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
        curl_close($ch);
        return ($success !== false && !$tooLarge && $status < 400) ? $raw : null;
    }
    $ctx = stream_context_create(['http' => [
        'method' => 'GET',
        'timeout' => $timeout,
        'header' => "User-Agent: $ua\r\nAccept: text/html,application/xhtml+xml,*/*\r\n",
    ]]);
    $raw = @file_get_contents($url, false, $ctx, 0, $maxBytes+1);
    return $raw === false || strlen((string)$raw)>$maxBytes ? null : (string)$raw;
}

function mr_cache_dir(): string
{
    $dir = dirname(__DIR__) . '/storage/cache/radars';
    if (!is_dir($dir)) @mkdir($dir, 0775, true);
    return $dir;
}

function mr_cached_text(string $key, string $url, int $ttl = 21600): ?string
{
    $file = mr_cache_dir() . '/' . preg_replace('/[^a-z0-9._-]+/i', '_', $key) . '.cache';
    if (is_file($file) && (time() - (int)@filemtime($file)) < $ttl) {
        $v = @file_get_contents($file);
        if ($v !== false && $v !== '') return $v;
    }
    $raw = mr_http_text($url);
    if ($raw !== null && $raw !== '') write_runtime_file($file,$raw);
    return $raw;
}

function mr_clean_html_cell(string $html): string
{
    $text = html_entity_decode(strip_tags(str_ireplace(['<br>', '<br/>', '<br />'], ' ', $html)), ENT_QUOTES | ENT_HTML5, 'UTF-8');
    $text = preg_replace('/\s+/u', ' ', $text) ?? $text;
    return trim($text);
}

function mr_parse_der_es_table(string $html, string $kind): array
{
    $items = [];
    $rows = [];

    // ASP.NET/DER-ES pode devolver HTML irregular. DOMDocument é muito mais tolerante
    // que regex para tabelas quebradas, spans e atributos gerados pelo GridView.
    if (class_exists('DOMDocument')) {
        $doc = new DOMDocument();
        $prev = libxml_use_internal_errors(true);
        @$doc->loadHTML('<?xml encoding="UTF-8">' . $html, LIBXML_NOWARNING | LIBXML_NOERROR);
        libxml_clear_errors();
        libxml_use_internal_errors($prev);
        foreach ($doc->getElementsByTagName('tr') as $tr) {
            $cells = [];
            foreach ($tr->childNodes as $node) {
                if (!($node instanceof DOMElement)) continue;
                $tag = strtolower($node->tagName);
                if ($tag !== 'td' && $tag !== 'th') continue;
                $txt = html_entity_decode((string)$node->textContent, ENT_QUOTES | ENT_HTML5, 'UTF-8');
                $txt = preg_replace('/\s+/u', ' ', $txt) ?? $txt;
                $cells[] = trim($txt);
            }
            if ($cells) $rows[] = $cells;
        }
    }

    // Fallback para hosts onde ext-dom não esteja habilitada.
    if (!$rows && preg_match_all('/<tr\b[^>]*>(.*?)<\/tr>/isu', $html, $rr)) {
        foreach ($rr[1] as $rowHtml) {
            if (!preg_match_all('/<t[dh]\b[^>]*>(.*?)<\/t[dh]>/isu', $rowHtml, $cc)) continue;
            $cells = array_map('mr_clean_html_cell', $cc[1]);
            if ($cells) $rows[] = $cells;
        }
    }

    foreach ($rows as $c) {
        if (count($c) < 6) continue;

        // Não confiar cegamente na posição das colunas: localize a célula que contém
        // "Rodovia ..." e a velocidade a partir das colunas seguintes.
        $code = trim((string)($c[0] ?? ''));
        if ($code === '' || stripos($code, 'cód') !== false || stripos($code, 'equipamento') !== false) continue;

        $locIndex = null;
        foreach ($c as $i => $cell) {
            if (preg_match('/\b(?:Rodovia\s+)?(?:ES|BR)\s*[- ]?\s*\d{2,3}\b/iu', (string)$cell)) { $locIndex = $i; break; }
        }
        if ($locIndex === null) continue;
        $loc = trim((string)$c[$locIndex]);

        $rowText = implode(' | ', $c);
        if (preg_match('/\b(?:DESATIVAD[OA]S?|FORA\s+DE\s+OPERA(?:CAO|ÇÃO)?|REMANEJAD[OA]S?|SUBSTITU[IÍ]D[OA]S?|RETIRAD[OA]S?|INATIV[OA]S?|DESINSTALAD[OA]S?|BAIXAD[OA]S?|CANCELAD[OA]S?)\b/iu',$rowText)) continue;

        $speed = null;
        // A tabela oficial usa a velocidade depois de "Funções Equipamento"; quando
        // a posição muda, procura um valor plausível de 20..130 km/h do fim para o começo.
        for ($i = count($c)-1; $i >= 0; $i--) {
            $candidate = mr_parse_speed($c[$i] ?? null);
            if ($candidate !== null && $candidate >= 20 && $candidate <= 130) { $speed = $candidate; break; }
        }

        $type = '';
        foreach ($c as $cell) if (stripos((string)$cell, 'Fixo') !== false || stripos((string)$cell, 'Portátil') !== false) { $type = (string)$cell; break; }
        $func = '';
        foreach ($c as $cell) if (stripos((string)$cell, 'Velocidade') !== false || stripos((string)$cell, 'Redução') !== false) { $func = (string)$cell; }

        // Portáteis podem conter diversos pontos separados por ponto e vírgula.
        $parts = $kind === 'PORTATIL' ? preg_split('/\s*;\s*/u', $loc) : [$loc];
        $carryRoad = null;
        foreach ($parts ?: [$loc] as $idx => $part) {
            $part = trim((string)$part);
            if ($part === '') continue;
            $road = mr_extract_road_ref($part) ?: $carryRoad;
            if ($road) $carryRoad = $road;
            $km = mr_extract_km($part);
            if (!$road || $km === null) continue;
            $items[] = [
                'external_id' => 'der-es-' . strtolower($kind) . '-' . preg_replace('/[^A-Za-z0-9_-]+/', '-', $code) . '-' . $idx,
                'codigo' => $code,
                'rodovia' => $road,
                'km' => $km,
                'descricao_local' => $part,
                'velocidade' => $speed,
                'tipo' => $kind === 'PORTATIL' ? 'FISCALIZACAO_PORTATIL' : 'RADAR_FIXO',
                'situacao' => 'ATIVO',
                'fonte' => $kind === 'PORTATIL' ? 'DER_ES_PORTATIL' : 'DER_ES_OFICIAL',
                'confiabilidade' => 'ALTA',
                'quantidade_fontes' => 1,
                'ativo' => 1,
                'raw_tipo' => $type,
                'raw_funcao' => $func,
            ];
        }
    }
    return $items;
}

function mr_extract_road_ref(string $text): ?string
{
    $s = strtoupper(str_replace(['–','—','_'], '-', $text));
    if (preg_match('/\b(ES|BR)\s*[- ]?\s*(\d{2,3})\b/u', $s, $m)) {
        return $m[1] . '-' . str_pad($m[2], 3, '0', STR_PAD_LEFT);
    }
    return null;
}

function mr_extract_km(string $text): ?float
{
    if (preg_match('/\bKM\s*[:º°-]?\s*(\d{1,3}(?:[\.,]\d+)?)\b/iu', $text, $m)) {
        return (float)str_replace(',', '.', $m[1]);
    }
    return null;
}

function mr_route_refs(array $route): array
{
    $refs = [];
    foreach (($route['legs'] ?? []) as $leg) {
        foreach (($leg['steps'] ?? []) as $step) {
            foreach (['ref','name','destinations'] as $field) {
                $v = (string)($step[$field] ?? '');
                if ($v === '') continue;
                if (preg_match_all('/\b(ES|BR)\s*[- ]?\s*(\d{2,3})\b/iu', $v, $mm, PREG_SET_ORDER)) {
                    foreach ($mm as $m) $refs[$m[1] . '-' . str_pad($m[2], 3, '0', STR_PAD_LEFT)] = true;
                }
            }
        }
    }
    return array_keys($refs);
}

function mr_route_is_in_es(array $coords): bool
{
    if (!$coords) return false;
    $sample = mr_sample_route($coords, 40);
    $hits = 0;
    foreach ($sample as $c) {
        if (!is_array($c) || count($c) < 2) continue;
        $lon = (float)$c[0]; $lat = (float)$c[1];
        // Caixa ampla do Espírito Santo, suficiente para decidir se vale consultar as fontes estaduais.
        if ($lat >= -21.35 && $lat <= -17.80 && $lon >= -41.95 && $lon <= -39.45) $hits++;
    }
    // Rotas de fronteira (ex.: Bom Jesus do Norte / Bom Jesus do Itabapoana)
    // podem ter só uma pequena parte dentro do ES. Um único ponto amostrado já
    // é suficiente para habilitar a fonte oficial estadual.
    return $hits >= 1;
}

function mr_der_es_rows_for_refs(array $refs): array
{
    $fixedHtml = mr_cached_text('der_es_fixed', 'https://servicos.der.es.gov.br/Radares.aspx', 21600);
    $portableHtml = mr_cached_text('der_es_portable', 'https://servicos.der.es.gov.br/RadaresPortatil.aspx', 21600);
    $rows = [];
    if ($fixedHtml) $rows = array_merge($rows, mr_parse_der_es_table($fixedHtml, 'FIXO'));
    if ($portableHtml) $rows = array_merge($rows, mr_parse_der_es_table($portableHtml, 'PORTATIL'));
    if (!$refs) return $rows;
    $set = array_fill_keys(array_map('strtoupper', $refs), true);
    $filtered = array_values(array_filter($rows, fn($r) => isset($set[strtoupper((string)$r['rodovia'])])));
    // OSRM às vezes omite ou troca a referência da rodovia. Se nenhuma linha casar,
    // não conclui que não há radar: deixa a etapa geométrica decidir.
    return $filtered ?: $rows;
}


function mr_geobases_wfs_json(array $params): ?array
{
    $paramsBase=$params;
    $paramVariants=[];
    foreach(['application/json','json'] as $fmt){$v=$paramsBase;$v['outputFormat']=$fmt;$paramVariants[]=$v;}
    $bases=[
        'https://ide.geobases.es.gov.br/geoserver/geonode/ows?',
        'https://ide.geobases.es.gov.br/geoserver/ows?',
    ];
    foreach($bases as $base){
        foreach($paramVariants as $v){
            try{$j=http_json($base.http_build_query($v));}catch(Throwable $e){$j=null;}
            if(is_array($j)&&isset($j['features'])&&is_array($j['features']))return $j;
        }
    }
    return null;
}

function mr_geobases_road_segments(string $roadRef): array
{
    static $mem = [];
    $roadRef = strtoupper($roadRef);
    if (isset($mem[$roadRef])) return $mem[$roadRef];
    $cacheFile = mr_cache_dir() . '/geobases_' . preg_replace('/[^A-Z0-9_-]+/', '_', $roadRef) . '.json';
    $data = null;
    if (is_file($cacheFile) && (time() - (int)@filemtime($cacheFile)) < 604800) {
        $raw = @file_get_contents($cacheFile);
        $tmp = $raw ? json_decode($raw, true) : null;
        if (is_array($tmp)) $data = $tmp;
    }
    if ($data === null) {
        $params = [
            'service' => 'WFS', 'version' => '1.0.0', 'request' => 'GetFeature',
            'typename' => 'geonode:der_rodovias_es_epsg_31984',
            'outputFormat' => 'json', 'srsName' => 'EPSG:4326',
            'CQL_FILTER' => "sigla='" . str_replace("'", "''", $roadRef) . "'",
        ];
        $data = mr_geobases_wfs_json($params);
        if (is_array($data)) {$encoded=json_encode($data,JSON_UNESCAPED_UNICODE);if(is_string($encoded))write_runtime_file($cacheFile,$encoded);}
    }
    $segments = [];
    foreach (($data['features'] ?? []) as $f) {
        $p = $f['properties'] ?? [];
        $ki = mr_num($p['km_inicial'] ?? $p['KM_INICIAL'] ?? null);
        $kf = mr_num($p['km_final'] ?? $p['KM_FINAL'] ?? null);
        $geometry = $f['geometry'] ?? null;
        if ($ki === null || $kf === null || !is_array($geometry)) continue;
        $lines = mr_geometry_lines($geometry);
        foreach ($lines as $line) {
            if (count($line) >= 2) $segments[] = ['km_initial' => $ki, 'km_final' => $kf, 'coords' => $line];
        }
    }
    return $mem[$roadRef] = $segments;
}

function mr_num($v): ?float
{
    if ($v === null || $v === '') return null;
    if (is_numeric($v)) return (float)$v;
    $s = str_replace(',', '.', preg_replace('/[^0-9,.-]+/', '', (string)$v));
    return is_numeric($s) ? (float)$s : null;
}

function mr_geometry_lines(array $g): array
{
    $type = $g['type'] ?? '';
    $c = $g['coordinates'] ?? [];
    if ($type === 'LineString' && is_array($c)) return [$c];
    if ($type === 'MultiLineString' && is_array($c)) return array_values(array_filter($c, 'is_array'));
    return [];
}

function mr_interpolate_line(array $coords, float $ratio): ?array
{
    $ratio = max(0.0, min(1.0, $ratio));
    $lens = []; $total = 0.0;
    for ($i = 1; $i < count($coords); $i++) {
        if (!isset($coords[$i-1][0],$coords[$i-1][1],$coords[$i][0],$coords[$i][1])) { $lens[] = 0.0; continue; }
        $d = haversine_m((float)$coords[$i-1][1], (float)$coords[$i-1][0], (float)$coords[$i][1], (float)$coords[$i][0]);
        $lens[] = $d; $total += $d;
    }
    if ($total <= 0) return null;
    $target = $total * $ratio; $acc = 0.0;
    for ($i = 1; $i < count($coords); $i++) {
        $d = $lens[$i-1] ?? 0.0;
        if ($acc + $d >= $target && $d > 0) {
            $t = ($target - $acc) / $d;
            $a = $coords[$i-1]; $b = $coords[$i];
            return ['lat' => (float)$a[1] + ((float)$b[1] - (float)$a[1]) * $t,
                    'lon' => (float)$a[0] + ((float)$b[0] - (float)$a[0]) * $t];
        }
        $acc += $d;
    }
    $last = end($coords);
    return is_array($last) && isset($last[0],$last[1]) ? ['lat'=>(float)$last[1],'lon'=>(float)$last[0]] : null;
}

function mr_der_es_position(string $roadRef, float $km): ?array
{
    $segments = mr_geobases_road_segments($roadRef);
    $best = null; $bestDelta = INF;
    foreach ($segments as $seg) {
        $a = (float)$seg['km_initial']; $b = (float)$seg['km_final'];
        $lo = min($a,$b); $hi = max($a,$b);
        $delta = $km < $lo ? $lo - $km : ($km > $hi ? $km - $hi : 0.0);
        if ($delta < $bestDelta) { $bestDelta = $delta; $best = $seg; }
        if ($delta == 0.0) break;
    }
    // Não inventa posição se o km estiver muito distante do trecho oficial encontrado.
    if (!$best || $bestDelta > 1.5) return null;
    $a = (float)$best['km_initial']; $b = (float)$best['km_final'];
    $den = $b - $a;
    $ratio = abs($den) < 0.0001 ? 0.5 : (($km - $a) / $den);
    return mr_interpolate_line($best['coords'], $ratio);
}

function mr_official_der_es_radars_for_route(array $coords, array $route): array
{
    if (!mr_route_is_in_es($coords)) return ['ok'=>true,'items'=>[],'refs'=>[],'rows'=>0,'positioned'=>0,'reason'=>'fora_es'];
    $refs = mr_route_refs($route);
    $rows = mr_der_es_rows_for_refs($refs);
    $items = []; $positioned = 0;
    foreach ($rows as $r) {
        $pos = mr_der_es_position((string)$r['rodovia'], (float)$r['km']);
        if (!$pos) continue;
        $r['latitude'] = $pos['lat'];
        $r['longitude'] = $pos['lon'];
        $r['uf'] = 'ES';
        $r['cidade'] = null;
        $r['data_fonte'] = date('Y-m-d');
        $items[] = $r; $positioned++;
    }
    return ['ok'=>true,'items'=>$items,'refs'=>$refs,'rows'=>count($rows),'positioned'=>$positioned];
}

function mr_sync_der_es_to_db(): array
{
    $fixedHtml = mr_cached_text('der_es_fixed', 'https://servicos.der.es.gov.br/Radares.aspx', 300);
    $portableHtml = mr_cached_text('der_es_portable', 'https://servicos.der.es.gov.br/RadaresPortatil.aspx', 300);
    $rows = [];
    if ($fixedHtml) $rows = array_merge($rows, mr_parse_der_es_table($fixedHtml, 'FIXO'));
    if ($portableHtml) $rows = array_merge($rows, mr_parse_der_es_table($portableHtml, 'PORTATIL'));
    $imported = 0; $positioned = 0; $roads = []; $seen=[];
    foreach ($rows as $r) {
        if(!empty($r['external_id']))$seen[]=(string)$r['external_id'];
        $roads[(string)$r['rodovia']] = true;
        $pos = mr_der_es_position((string)$r['rodovia'], (float)$r['km']);
        if (!$pos) continue;
        $positioned++;
        $r['latitude'] = $pos['lat']; $r['longitude'] = $pos['lon'];
        $r['uf'] = 'ES'; $r['data_fonte'] = date('Y-m-d');
        try { mr_upsert_official_radar($r); $imported++; } catch (Throwable $e) {}
    }
    $deactivated=0;
    if($fixedHtml&&$portableHtml&&count($seen)>=10){
        $seen=array_values(array_unique($seen));$marks=implode(',',array_fill(0,count($seen),'?'));
        $stmt=db()->prepare("UPDATE radars SET ativo=0,situacao='INATIVO_NA_FONTE',data_importacao=CURRENT_TIMESTAMP WHERE fonte IN ('DER_ES_OFICIAL','DER_ES_PORTATIL') AND external_id NOT IN ($marks)");
        $stmt->execute($seen);$deactivated=$stmt->rowCount();
    }
    return ['ok'=>count($rows)>0,'rows'=>count($rows),'positioned'=>$positioned,'imported'=>$imported,'deactivated'=>$deactivated,'roads'=>count($roads)];
}

function mr_upsert_official_radar(array $r): void
{
    if (empty($r['external_id']) || !isset($r['latitude'],$r['longitude'])) return;
    $stmt = db()->prepare('SELECT id FROM radars WHERE external_id = ? LIMIT 1');
    $stmt->execute([$r['external_id']]);
    $id = $stmt->fetchColumn();
    if ($id) {
        $u = db()->prepare('UPDATE radars SET latitude=?, longitude=?, uf=?, cidade=?, rodovia=?, km=?, sentido=?, heading=?, velocidade=?, tipo=?, situacao=?, fonte=?, data_fonte=?, data_importacao=CURRENT_TIMESTAMP, confiabilidade=?, ativo=1,expires_at=NULL WHERE id=?');
        $u->execute([$r['latitude'],$r['longitude'],$r['uf'] ?? 'ES',$r['cidade']??null,$r['rodovia'] ?? null,$r['km'] ?? null,$r['sentido']??null,$r['heading']??null,$r['velocidade'] ?? null,$r['tipo'] ?? 'RADAR_FIXO',$r['situacao'] ?? 'ATIVO',$r['fonte'] ?? 'DER_ES_OFICIAL',$r['data_fonte'] ?? null,$r['confiabilidade'] ?? 'ALTA',$id]);
        return;
    }
    $i = db()->prepare('INSERT INTO radars (external_id, latitude, longitude, uf, cidade, rodovia, km, sentido, heading, velocidade, tipo, situacao, fonte, data_fonte, data_importacao, confiabilidade, quantidade_fontes, ativo) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, 1)');
    $i->execute([$r['external_id'],$r['latitude'],$r['longitude'],$r['uf'] ?? 'ES',$r['cidade'] ?? null,$r['rodovia'] ?? null,$r['km'] ?? null,$r['sentido'] ?? null,$r['heading'] ?? null,$r['velocidade'] ?? null,$r['tipo'] ?? 'RADAR_FIXO',$r['situacao'] ?? 'ATIVO',$r['fonte'] ?? 'DER_ES_OFICIAL',$r['data_fonte'] ?? null,$r['confiabilidade'] ?? 'ALTA',$r['quantidade_fontes'] ?? 1]);
}

/**
 * V11: consulta uma única janela WFS do GEOBASES ao redor da rota, em vez de
 * fazer uma requisição por rodovia. Isso reduz latência e permite posicionar
 * radares DER-ES mesmo quando o OSRM não entrega "steps/ref".
 */
function mr_geobases_segments_for_route(array $coords): array
{
    if (!$coords) return ['ok'=>false,'segments'=>[],'roads'=>[],'error'=>'rota_vazia'];
    $lats=[];$lons=[];
    foreach(mr_sample_route($coords,120) as $c){
        if(!is_array($c)||!isset($c[0],$c[1]))continue;
        $lons[]=(float)$c[0];$lats[]=(float)$c[1];
    }
    if(!$lats||!$lons)return ['ok'=>false,'segments'=>[],'roads'=>[],'error'=>'bbox_invalida'];
    $pad=.055;
    $minLat=min($lats)-$pad;$maxLat=max($lats)+$pad;$minLon=min($lons)-$pad;$maxLon=max($lons)+$pad;
    $key='geobases_route_'.sha1(implode('|',[
        round($minLat,2),round($maxLat,2),round($minLon,2),round($maxLon,2)
    ]));
    $cacheFile=mr_cache_dir().'/'.$key.'.json';$data=null;
    if(is_file($cacheFile)&&(time()-(int)@filemtime($cacheFile))<604800){
        $j=json_decode((string)@file_get_contents($cacheFile),true);if(is_array($j))$data=$j;
    }
    if($data===null){
        $params=[
            'service'=>'WFS','version'=>'1.0.0','request'=>'GetFeature',
            'typename'=>'geonode:der_rodovias_es_epsg_31984','outputFormat'=>'json','srsName'=>'EPSG:4326',
            'bbox'=>implode(',',[$minLon,$minLat,$maxLon,$maxLat,'EPSG:4326'])
        ];
        $data=mr_geobases_wfs_json($params);
        if(is_array($data)){$encoded=json_encode($data,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);if(is_string($encoded))write_runtime_file($cacheFile,$encoded);}
    }
    if(!is_array($data))return ['ok'=>false,'segments'=>[],'roads'=>[],'error'=>'geobases_sem_resposta'];
    $segments=[];$roads=[];
    foreach(($data['features']??[]) as $f){
        $p=is_array($f['properties']??null)?$f['properties']:[];
        $road=mr_extract_road_ref((string)($p['sigla']??$p['SIGLA']??$p['rodovia']??$p['RODOVIA']??''));
        if(!$road)continue;
        $ki=mr_num($p['km_inicial']??$p['KM_INICIAL']??null);$kf=mr_num($p['km_final']??$p['KM_FINAL']??null);
        if($ki===null||$kf===null)continue;
        foreach(mr_geometry_lines(is_array($f['geometry']??null)?$f['geometry']:[]) as $line){
            if(count($line)<2)continue;
            $segments[$road][]=['km_initial'=>$ki,'km_final'=>$kf,'coords'=>$line];$roads[$road]=true;
        }
    }
    return ['ok'=>true,'segments'=>$segments,'roads'=>array_keys($roads),'features'=>count($data['features']??[])];
}

function mr_der_es_position_from_segment_map(string $roadRef,float $km,array $segmentMap): ?array
{
    $roadRef=mr_extract_road_ref($roadRef)?:strtoupper(trim($roadRef));
    $segments=$segmentMap[$roadRef]??[];$best=null;$bestDelta=INF;
    foreach($segments as $seg){
        $a=(float)$seg['km_initial'];$b=(float)$seg['km_final'];$lo=min($a,$b);$hi=max($a,$b);
        $delta=$km<$lo?$lo-$km:($km>$hi?$km-$hi:0.0);
        if($delta<$bestDelta){$bestDelta=$delta;$best=$seg;}if($delta===0.0)break;
    }
    if(!$best||$bestDelta>2.0)return null;
    $a=(float)$best['km_initial'];$b=(float)$best['km_final'];$den=$b-$a;
    $ratio=abs($den)<.0001?.5:(($km-$a)/$den);
    return mr_interpolate_line($best['coords'],$ratio);
}

function mr_official_der_es_radars_for_coords(array $coords): array
{
    if(!mr_route_is_in_es($coords))return ['ok'=>true,'items'=>[],'rows'=>0,'positioned'=>0,'roads'=>[],'reason'=>'fora_es'];
    $geo=mr_geobases_segments_for_route($coords);
    $roads=$geo['roads']??[];
    // Sem a malha da rota não fazemos dezenas de chamadas por rodovia: devolvemos
    // diagnóstico e deixamos banco/ANTT/OSM responderem sem travar o mapa.
    if(empty($geo['ok'])||!$roads)return ['ok'=>false,'items'=>[],'rows'=>0,'positioned'=>0,'roads'=>[],'geobases_ok'=>false,'error'=>$geo['error']??'geobases_sem_segmentos'];
    $rows=mr_der_es_rows_for_refs($roads);
    $items=[];$positioned=0;
    foreach($rows as $r){
        $pos=mr_der_es_position_from_segment_map((string)$r['rodovia'],(float)$r['km'],$geo['segments']??[]);
        // Fallback por rodovia individual se o WFS por bbox não trouxer o segmento.
        if(!$pos)$pos=mr_der_es_position((string)$r['rodovia'],(float)$r['km']);
        if(!$pos)continue;
        $r['latitude']=$pos['lat'];$r['longitude']=$pos['lon'];$r['uf']='ES';$r['data_fonte']=date('Y-m-d');
        $r['position_method']='GEOBases_KM';$items[]=$r;$positioned++;
    }
    return ['ok'=>count($rows)>0,'items'=>$items,'rows'=>count($rows),'positioned'=>$positioned,'roads'=>$roads,'geobases_ok'=>(bool)($geo['ok']??false),'error'=>$geo['error']??null];
}
