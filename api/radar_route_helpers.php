<?php

function mr_parse_speed($raw): ?int
{
    if ($raw === null || $raw === '') return null;
    if (is_numeric($raw)) {
        $v = (int)round((float)$raw);
        return ($v >= 10 && $v <= 180) ? $v : null;
    }
    if (preg_match('/(\d{2,3})/', (string)$raw, $m)) {
        $v = (int)$m[1];
        return ($v >= 10 && $v <= 180) ? $v : null;
    }
    return null;
}

function mr_sample_route(array $coords, int $maxPoints = 120): array
{
    $n = count($coords);
    if ($n <= $maxPoints) return $coords;
    $out = [];
    $step = ($n - 1) / ($maxPoints - 1);
    for ($i = 0; $i < $maxPoints; $i++) {
        $idx = (int)round($i * $step);
        $idx = min($n - 1, max(0, $idx));
        $out[] = $coords[$idx];
    }
    return $out;
}

function mr_route_line_for_overpass(array $coords, int $maxPoints = 95): string
{
    $sample = mr_sample_route($coords, $maxPoints);
    $parts = [];
    foreach ($sample as $c) {
        if (!is_array($c) || count($c) < 2) continue;
        $lon = (float)$c[0]; $lat = (float)$c[1];
        if (!$lat || !$lon) continue;
        $parts[] = rtrim(rtrim(number_format($lat, 6, '.', ''), '0'), '.') . ',' . rtrim(rtrim(number_format($lon, 6, '.', ''), '0'), '.');
    }
    return implode(',', $parts);
}

function mr_osm_overpass_endpoints(): array
{
    global $config;
    $urls=[];
    $configured=trim((string)($config['routing']['overpass_url']??''));
    if($configured!=='')$urls[]=$configured;
    foreach([
        'https://overpass-api.de/api/interpreter',
        'https://overpass.kumi.systems/api/interpreter',
    ] as $u) if(!in_array($u,$urls,true))$urls[]=$u;
    return $urls;
}

function mr_overpass_query_json(string $query, int $timeoutSeconds = 12): array
{
    $attempts=[];$used=null;$osm=null;
    foreach(mr_osm_overpass_endpoints() as $endpoint){
        $started=microtime(true);
        try{
            $candidate=http_json($endpoint,'data='.urlencode($query),['Content-Type: application/x-www-form-urlencoded'],$timeoutSeconds);
        }catch(Throwable $e){$candidate=null;}
        $attempts[]=['endpoint'=>$endpoint,'ok'=>is_array($candidate),'ms'=>(int)round((microtime(true)-$started)*1000)];
        if(is_array($candidate)){ $osm=$candidate;$used=$endpoint;break; }
    }
    return ['ok'=>is_array($osm),'data'=>$osm,'endpoint'=>$used,'attempts'=>$attempts];
}

function mr_overpass_post_first(string $query, string $label, array &$attempts, int $timeoutSeconds = 7): ?array
{
    foreach (mr_osm_overpass_endpoints() as $endpoint) {
        $started = microtime(true);
        try {
            $candidate = http_json($endpoint, 'data=' . urlencode($query), ['Content-Type: application/x-www-form-urlencoded'], $timeoutSeconds);
        } catch (Throwable $e) {
            $candidate = null;
        }
        $attempts[] = ['query'=>$label,'endpoint'=>$endpoint,'ok'=>is_array($candidate),'ms'=>(int)round((microtime(true)-$started)*1000)];
        if (is_array($candidate)) return $candidate;
    }
    return null;
}

function mr_osm_radars_for_route(array $coords, int $radiusM = 850): array
{
    $line = mr_route_line_for_overpass($coords, 110);
    if ($line === '' || substr_count($line, ',') < 3) return ['ok'=>false,'items'=>[],'error'=>'rota_invalida_para_overpass','attempts'=>[]];

    $attempts=[];
    $hazardAround='(around:90,'.$line.')';
    $radarAround='(around:'.max(180,min(500,$radiusM)).','.$line.')';
    
    // Consultas separadas: quebra-molas/semáforos não dependem mais da consulta pesada de maxspeed.
    $hazardQuery='[out:json][timeout:12];('
        .'node["highway"="traffic_signals"]'.$hazardAround.';'
        .'node["crossing"="traffic_signals"]'.$hazardAround.';'
        .'node["traffic_calming"~"^(bump|hump|table|cushion|rumble_strip|choker|island|yes)$",i]'.$hazardAround.';'
        .'way["traffic_calming"~"^(bump|hump|table|cushion|rumble_strip|choker|island|yes)$",i]'.$hazardAround.';'
        .'node["highway"="speed_bump"]'.$hazardAround.';'
        .');out center tags;';
    $hazards=mr_overpass_post_first($hazardQuery,'road_hazards',$attempts,7);

    $radarQuery='[out:json][timeout:14];('
        .'node["highway"="speed_camera"]'.$radarAround.';'
        .'node["enforcement"="maxspeed"]'.$radarAround.';'
        .'way["highway"="speed_camera"]'.$radarAround.';'
        .'relation["type"="enforcement"]["enforcement"="maxspeed"]'.$radarAround.';'
        .');out center tags;';
    $radars=mr_overpass_post_first($radarQuery,'speed_enforcement',$attempts,7);

    // Consulta independente e tolerante a falhas: um timeout de maxspeed não elimina
    // radares, semáforos e redutores já encontrados nas duas consultas anteriores.
    $limitAround='(around:40,'.$line.')';
    $limitQuery='[out:json][timeout:10];way["highway"]["maxspeed"]'.$limitAround.';out center tags;';
    $limitsRaw=mr_overpass_post_first($limitQuery,'road_speed_limits',$attempts,6);

    $sources=[];if(is_array($hazards))$sources[]=$hazards;if(is_array($radars))$sources[]=$radars;if(is_array($limitsRaw))$sources[]=$limitsRaw;
    if(!$sources)return ['ok'=>false,'items'=>[],'error'=>'overpass_sem_resposta','attempts'=>$attempts];

    $elements=[];$seen=[];
    foreach($sources as $src){foreach(($src['elements']??[]) as $el){$key=(string)($el['type']??'x').':'.(string)($el['id']??md5(json_encode($el)));if(isset($seen[$key]))continue;$seen[$key]=true;$elements[]=$el;}}

    $items=[];$speedLimits=[];$typeCounts=['radar'=>0,'signal'=>0,'bump'=>0,'speed_limit'=>0];
    foreach($elements as $el){
        $lat=$el['lat']??($el['center']['lat']??null);$lon=$el['lon']??($el['center']['lon']??null);if(!is_numeric($lat)||!is_numeric($lon))continue;
        $tags=is_array($el['tags']??null)?$el['tags']:[];
        $speed=mr_parse_speed($tags['maxspeed']??($tags['maxspeed:forward']??($tags['maxspeed:backward']??($tags['maxspeed:advisory']??null))));
        $highway=strtolower((string)($tags['highway']??''));$crossing=strtolower((string)($tags['crossing']??''));$calming=strtolower((string)($tags['traffic_calming']??''));
        $isRoadLimit=(($el['type']??'')==='way'&&$highway!==''&&$speed!==null&&$highway!=='speed_camera'&&!isset($tags['enforcement']));
        if($isRoadLimit){$speedLimits[]=['external_id'=>'osm-limit-way-'.($el['id']??md5((string)$lat.':'.(string)$lon.':'.$speed)),'latitude'=>(float)$lat,'longitude'=>(float)$lon,'velocidade'=>$speed,'rodovia'=>$tags['ref']??($tags['name']??null),'highway'=>$highway,'fonte'=>'OPENSTREETMAP_MAXSPEED'];$typeCounts['speed_limit']++;continue;}
        if($highway==='traffic_signals'||$crossing==='traffic_signals'){$type='SEMAFORO';$alertRadius=280;$typeCounts['signal']++;}
        elseif($highway==='speed_bump'||in_array($calming,['bump','hump','table','cushion','rumble_strip','choker','island','yes'],true)){$type='QUEBRA_MOLA';$alertRadius=300;$typeCounts['bump']++;}
        else{$type=(($tags['enforcement']??'')==='maxspeed'||($tags['type']??'')==='enforcement')?'FISCALIZACAO_VELOCIDADE':'RADAR_FIXO';$alertRadius=300;$typeCounts['radar']++;}
        $items[]=['external_id'=>'osm-'.($el['type']??'node').'-'.($el['id']??md5((string)$lat.':'.(string)$lon)),'latitude'=>(float)$lat,'longitude'=>(float)$lon,'uf'=>$tags['addr:state']??null,'cidade'=>$tags['addr:city']??null,'rodovia'=>$tags['road_ref']??($tags['ref']??($tags['name']??null)),'km'=>$tags['distance']??null,'sentido'=>$tags['direction']??null,'heading'=>is_numeric($tags['direction']??null)?(float)$tags['direction']:null,'velocidade'=>$speed,'tipo'=>$type,'situacao'=>'ATIVO','fonte'=>'OPENSTREETMAP','alert_radius_m'=>$alertRadius,'data_fonte'=>null,'confiabilidade'=>'MÉDIA','quantidade_fontes'=>1,'ativo'=>1];
    }
    return ['ok'=>true,'items'=>$items,'speed_limits'=>$speedLimits,'elements'=>count($elements),'query_radius_m'=>$radiusM,'type_counts'=>$typeCounts,'hazards_ok'=>is_array($hazards),'radars_ok'=>is_array($radars),'limits_ok'=>is_array($limitsRaw),'attempts'=>$attempts];
}

function mr_upsert_osm_radar(array $r): void
{
    if (empty($r['external_id'])) return;
    $exists = db()->prepare('SELECT id, velocidade FROM radars WHERE external_id = ? LIMIT 1');
    $exists->execute([$r['external_id']]);
    $row = $exists->fetch();
    if ($row) {
        $u = db()->prepare("UPDATE radars SET latitude=?,longitude=?,velocidade=COALESCE(?,velocidade),rodovia=COALESCE(?,rodovia),sentido=COALESCE(?,sentido),heading=COALESCE(?,heading),tipo=?,situacao='ATIVO',fonte='OPENSTREETMAP',data_importacao=CURRENT_TIMESTAMP,ativo=1,expires_at=NULL WHERE id=?");
        $u->execute([$r['latitude'],$r['longitude'],$r['velocidade']??null,$r['rodovia']??null,$r['sentido']??null,$r['heading']??null,$r['tipo']??'RADAR_FIXO',$row['id']]);
        return;
    }
    $insert = db()->prepare('INSERT INTO radars (external_id, latitude, longitude, uf, cidade, rodovia, km, sentido, heading, velocidade, tipo, situacao, fonte, data_fonte, data_importacao, confiabilidade, quantidade_fontes, ativo) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?)');
    $insert->execute([
        $r['external_id'], $r['latitude'], $r['longitude'], $r['uf'] ?? null, $r['cidade'] ?? null,
        $r['rodovia'] ?? null, $r['km'] ?? null, $r['sentido'] ?? null, $r['heading'] ?? null,
        $r['velocidade'] ?? null, $r['tipo'] ?? 'RADAR_FIXO', $r['situacao'] ?? 'ATIVO', $r['fonte'] ?? 'OPENSTREETMAP',
        $r['data_fonte'] ?? null, $r['confiabilidade'] ?? 'MÉDIA', $r['quantidade_fontes'] ?? 1, $r['ativo'] ?? 1,
    ]);
}

function mr_route_metrics(array $coords): array
{
    $route = mr_sample_route($coords, 1500);
    $cum = [0.0];
    $total = 0.0;
    for ($i = 1; $i < count($route); $i++) {
        $total += haversine_m((float)$route[$i-1][1], (float)$route[$i-1][0], (float)$route[$i][1], (float)$route[$i][0]);
        $cum[$i] = $total;
    }
    return [$route, $cum];
}

function mr_point_segment_projection(float $plat, float $plon, float $alat, float $alon, float $blat, float $blon): array
{
    $lat0 = deg2rad(($plat + $alat + $blat) / 3.0);
    $mx = 111320.0 * max(0.2, cos($lat0));
    $my = 110540.0;
    $ax = ($alon - $plon) * $mx; $ay = ($alat - $plat) * $my;
    $bx = ($blon - $plon) * $mx; $by = ($blat - $plat) * $my;
    $vx = $bx - $ax; $vy = $by - $ay;
    $den = $vx*$vx + $vy*$vy;
    $t = $den > 0 ? -($ax*$vx + $ay*$vy) / $den : 0.0;
    $t = max(0.0, min(1.0, $t));
    $x = $ax + $t*$vx; $y = $ay + $t*$vy;
    return ['distance' => sqrt($x*$x + $y*$y), 't' => $t];
}

function mr_radar_position_on_route(array $r, array $route, array $cum): array
{
    $lat = (float)$r['latitude']; $lon = (float)$r['longitude'];
    $best = INF; $bestM = 0.0; $bestHeading = null;
    $n = count($route);
    for ($i = 1; $i < $n; $i++) {
        $a = $route[$i-1]; $b = $route[$i];
        $p = mr_point_segment_projection($lat, $lon, (float)$a[1], (float)$a[0], (float)$b[1], (float)$b[0]);
        if ($p['distance'] < $best) {
            $best = $p['distance'];
            $seg = max(0.0, ($cum[$i] ?? 0) - ($cum[$i-1] ?? 0));
            $bestM = ($cum[$i-1] ?? 0) + $seg * $p['t'];
            $bestHeading = mr_route_bearing((float)$a[1],(float)$a[0],(float)$b[1],(float)$b[0]);
        }
    }
    return ['distance_to_route_m' => $best, 'route_m' => $bestM, 'route_heading' => $bestHeading];
}

function mr_route_bearing(float $lat1, float $lon1, float $lat2, float $lon2): float
{
    $p1=deg2rad($lat1);$p2=deg2rad($lat2);$dl=deg2rad($lon2-$lon1);
    $y=sin($dl)*cos($p2);$x=cos($p1)*sin($p2)-sin($p1)*cos($p2)*cos($dl);
    return fmod(rad2deg(atan2($y,$x))+360.0,360.0);
}

function mr_heading_delta(float $a, float $b): float
{
    $d=abs(fmod($a-$b+540.0,360.0)-180.0);
    return min(180.0,max(0.0,$d));
}

function mr_traffic_direction_heading(?string $value): ?float
{
    $text=trim((string)$value);
    if($text==='')return null;
    if(function_exists('iconv')){$fold=@iconv('UTF-8','ASCII//TRANSLIT//IGNORE',$text);if($fold!==false)$text=$fold;}
    $text=strtoupper((string)preg_replace('/[^A-Z]+/',' ',strtoupper($text)));
    if(preg_match('/\b(AMBOS|DUPLO|BIDIRECIONAL|DOIS SENTIDOS)\b/',$text))return null;
    foreach([
        'NORDESTE'=>45.0,'SUDESTE'=>135.0,'SUDOESTE'=>225.0,'NOROESTE'=>315.0,
        'NORTE'=>0.0,'LESTE'=>90.0,'SUL'=>180.0,'OESTE'=>270.0,
    ] as $word=>$heading)if(preg_match('/\b'.preg_quote($word,'/').'\b/',$text))return $heading;
    return null;
}

function mr_source_priority(?string $source): int
{
    $s = strtoupper((string)$source);
    if (strpos($s, 'ANTT') !== false || strpos($s, 'DNIT') !== false || strpos($s, 'DER_') !== false) return 50;
    if (strpos($s, 'OFICIAL') !== false) return 45;
    if (strpos($s, 'BASE_LOCAL') !== false) return 30;
    if (strpos($s, 'OPENSTREETMAP') !== false) return 20;
    if (strpos($s, 'COMUN') !== false) return 10;
    return 25;
}

function mr_route_item_kind(array $r): string
{
    $t = strtoupper((string)($r['tipo'] ?? ''));
    if (strpos($t,'QUEBRA')!==false || strpos($t,'LOMBADA')!==false || strpos($t,'BUMP')!==false || strpos($t,'HUMP')!==false || strpos($t,'CALMING')!==false) return 'bump';
    if (strpos($t,'SEMAFOR')!==false || strpos($t,'SEMÁFOR')!==false || strpos($t,'SIGNAL')!==false || strpos($t,'AVAN')!==false) return 'signal';
    if (strpos($t,'VIDEO')!==false || strpos($t,'OCR')!==false || strpos($t,'MONITOR')!==false) return 'video';
    if (strpos($t,'RADAR')!==false || strpos($t,'VELOC')!==false || strpos($t,'PORTAT')!==false || strpos($t,'MOVEL')!==false || strpos($t,'MÓVEL')!==false) return 'speed';
    return 'other';
}

function mr_dedupe_radars(array $items): array
{
    $out = []; $ids=[]; $grid=[]; $cellSize=.0004;
    foreach ($items as $r) {
        if (!isset($r['latitude'],$r['longitude']) || !is_numeric($r['latitude']) || !is_numeric($r['longitude'])) continue;
        $merged = false; $rk = mr_route_item_kind($r); $matchIndex=null;
        $external=(string)($r['external_id']??'');
        if($external!==''&&isset($ids[$external]))$matchIndex=$ids[$external];
        $gx=(int)floor((float)$r['latitude']/$cellSize);$gy=(int)floor((float)$r['longitude']/$cellSize);
        $candidates=[];
        if($matchIndex!==null)$candidates[]=$matchIndex;
        for($dx=-1;$dx<=1;$dx++)for($dy=-1;$dy<=1;$dy++)foreach(($grid[($gx+$dx).':'.($gy+$dy)]??[]) as $idx)$candidates[]=$idx;
        foreach (array_values(array_unique($candidates)) as $i) {
            $e=$out[$i];
            $sameId = $external!=='' && !empty($e['external_id']) && $external === $e['external_id'];
            $ek = mr_route_item_kind($e);
            $sameKind = $rk === $ek || ($rk === 'speed' && $ek === 'speed');
            $threshold = $rk === 'bump' ? 7 : ($rk === 'signal' ? 12 : ($rk === 'video' ? 20 : 32));
            $near = $sameKind && haversine_m((float)$r['latitude'], (float)$r['longitude'], (float)$e['latitude'], (float)$e['longitude']) <= $threshold;
            if (!$sameId && !$near) continue;

            $sources = [];
            foreach ([$e['fontes'] ?? null, $e['fonte'] ?? null, $r['fontes'] ?? null, $r['fonte'] ?? null] as $src) {
                if (is_array($src)) { foreach ($src as $v) if ($v) $sources[(string)$v] = true; }
                elseif ($src) $sources[(string)$src] = true;
            }

            $ep = mr_source_priority($e['fonte'] ?? null);
            $rp = mr_source_priority($r['fonte'] ?? null);
            if ($rp > $ep) {
                $base = $r;
                foreach ($e as $k => $v) if ((!isset($base[$k]) || $base[$k] === '' || $base[$k] === null) && $v !== '' && $v !== null) $base[$k] = $v;
                $out[$i] = $base;
            } else {
                foreach ($r as $k => $v) if ((!isset($out[$i][$k]) || $out[$i][$k] === '' || $out[$i][$k] === null) && $v !== '' && $v !== null) $out[$i][$k] = $v;
            }
            $out[$i]['fontes'] = array_keys($sources);
            $out[$i]['quantidade_fontes'] = count($sources);
            if (max($ep,$rp) >= 45) $out[$i]['confiabilidade'] = 'ALTA';
            if($external!=='')$ids[$external]=$i;
            $merged = true;
            break;
        }
        if (!$merged) {
            $r['fontes'] = !empty($r['fonte']) ? [(string)$r['fonte']] : [];
            $r['quantidade_fontes'] = max(1, (int)($r['quantidade_fontes'] ?? 1));
            $index=count($out);$out[]=$r;
            if($external!=='')$ids[$external]=$index;
            $grid[$gx.':'.$gy][]=$index;
        }
    }
    return $out;
}
