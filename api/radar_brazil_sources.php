<?php

/*
 * MusicRoad AI - Radares Brasil
 * Fontes: ANTT (nacional/concessoes), DNIT PNCV (quando recurso publico e descoberto),
 * DER-ES, DER-MG, DER-RJ e OpenStreetMap (consultado pelo helper da rota).
 * As listas oficiais que so publicam rodovia+km sao posicionadas na rota usando
 * marcos quilometricos OSM e, no RJ, pontos oficiais/localidades geocodificados como calibracao de fallback.
 */

function mr_radar_provider_exception(string $scope, Throwable $error): string
{
    if (!function_exists('report_runtime_exception')) return 'provider_unavailable';
    return 'provider_unavailable:' . report_runtime_exception('radar_' . $scope, $error);
}

function mr_norm_text_key(string $s): string
{
    $s = trim($s);
    if (function_exists('iconv')) {
        $v = @iconv('UTF-8', 'ASCII//TRANSLIT//IGNORE', $s);
        if ($v !== false) $s = $v;
    }
    $s = strtolower($s);
    return preg_replace('/[^a-z0-9]+/', '_', $s) ?: '';
}

function mr_assoc_ci(array $row): array
{
    $out = [];
    foreach ($row as $k => $v) $out[mr_norm_text_key((string)$k)] = $v;
    return $out;
}

function mr_pick_ci(array $row, array $keys, $default = null)
{
    $r = mr_assoc_ci($row);
    foreach ($keys as $k) {
        $nk = mr_norm_text_key($k);
        if (array_key_exists($nk, $r) && $r[$nk] !== '' && $r[$nk] !== null) return $r[$nk];
    }
    return $default;
}

function mr_decimal($v): ?float
{
    if ($v === null || $v === '') return null;
    if (is_numeric($v)) return (float)$v;
    $s = trim((string)$v);
    $s = str_replace([' ', '\xc2\xa0'], '', $s);
    if (substr_count($s, ',') === 1 && substr_count($s, '.') === 0) $s = str_replace(',', '.', $s);
    else if (substr_count($s, ',') === 1 && substr_count($s, '.') >= 1) $s = str_replace(',', '', $s);
    $s = preg_replace('/[^0-9.\-]+/', '', $s) ?? '';
    return is_numeric($s) ? (float)$s : null;
}

function mr_normalize_road_ref(?string $raw, ?string $ufHint = null): ?string
{
    if ($raw === null) return null;
    $s = strtoupper(trim($raw));
    $s = str_replace(['–','—','_','/'], '-', $s);
    $s = preg_replace('/\s+/', ' ', $s) ?? $s;
    if (preg_match('/\bBR\s*[- ]?\s*(\d{1,3})\b/u', $s, $m)) return 'BR-' . str_pad($m[1], 3, '0', STR_PAD_LEFT);
    if (preg_match('/\b(MGC|LMG|AMG)\s*[- ]?\s*(\d{1,4})\b/u', $s, $m)) return $m[1] . '-' . str_pad((string)((int)$m[2]), 3, '0', STR_PAD_LEFT);
    $ufs = 'AC|AL|AP|AM|BA|CE|DF|ES|GO|MA|MT|MS|MG|PA|PB|PR|PE|PI|RJ|RN|RS|RO|RR|SC|SP|SE|TO';
    if (preg_match('/\b(' . $ufs . ')\s*[- ]?\s*(\d{1,4})\b/u', $s, $m)) return $m[1] . '-' . str_pad((string)((int)$m[2]), 3, '0', STR_PAD_LEFT);
    if ($ufHint && preg_match('/\b(\d{2,4})\b/u', $s, $m)) {
        $uf = strtoupper($ufHint);
        return $uf . '-' . str_pad((string)((int)$m[1]), 3, '0', STR_PAD_LEFT);
    }
    return null;
}

function mr_refs_from_text(string $text): array
{
    $refs = [];
    $patterns = [
        '/\bBR\s*[- ]?\s*\d{1,3}\b/iu',
        '/\b(?:MGC|LMG|AMG)\s*[- ]?\s*\d{1,4}\b/iu',
        '/\b(?:AC|AL|AP|AM|BA|CE|DF|ES|GO|MA|MT|MS|MG|PA|PB|PR|PE|PI|RJ|RN|RS|RO|RR|SC|SP|SE|TO)\s*[- ]?\s*\d{1,4}\b/iu',
    ];
    foreach ($patterns as $p) {
        if (preg_match_all($p, $text, $m)) {
            foreach ($m[0] as $raw) {
                $ref = mr_normalize_road_ref($raw);
                if ($ref) $refs[$ref] = true;
            }
        }
    }
    return array_keys($refs);
}

function mr_route_refs_brazil(array $route): array
{
    $refs = [];
    foreach (($route['legs'] ?? []) as $leg) {
        foreach (($leg['steps'] ?? []) as $step) {
            foreach (['ref','name','destinations'] as $field) {
                foreach (mr_refs_from_text((string)($step[$field] ?? '')) as $r) $refs[$r] = true;
            }
        }
    }
    return array_keys($refs);
}

function mr_route_ref_intervals(array $route): array
{
    $out = [];
    $cursor = 0.0;
    foreach (($route['legs'] ?? []) as $leg) {
        foreach (($leg['steps'] ?? []) as $step) {
            $d = max(0.0, (float)($step['distance'] ?? 0));
            $refs = [];
            foreach (['ref','name'] as $field) foreach (mr_refs_from_text((string)($step[$field] ?? '')) as $r) $refs[$r] = true;
            foreach (array_keys($refs) as $ref) $out[] = ['ref'=>$ref,'start_m'=>$cursor,'end_m'=>$cursor+$d];
            $cursor += $d;
        }
    }
    return $out;
}

function mr_ref_for_route_m(float $m, array $intervals): ?string
{
    $best = null; $bestDelta = INF;
    foreach ($intervals as $it) {
        $a=(float)$it['start_m']; $b=(float)$it['end_m'];
        if ($m >= $a-80 && $m <= $b+80) return $it['ref'];
        $delta = min(abs($m-$a), abs($m-$b));
        if ($delta < $bestDelta) { $bestDelta=$delta; $best=$it['ref']; }
    }
    return $bestDelta <= 500 ? $best : null;
}

function mr_parse_milestone_km(array $tags): ?float
{
    foreach (['distance','km','kilometer','ref','name'] as $k) {
        if (!isset($tags[$k])) continue;
        $v = (string)$tags[$k];
        if (preg_match('/(?:km\s*)?(\d{1,4}(?:[.,]\d{1,3})?)/iu', $v, $m)) {
            $n = (float)str_replace(',', '.', $m[1]);
            if ($n >= 0 && $n < 3000) return $n;
        }
    }
    return null;
}

function mr_osm_route_milestones(array $coords, array $route, array $metricRoute, array $cum): array
{
    global $config;
    $line = mr_route_line_for_overpass($coords, 100);
    if ($line === '') return ['ok'=>false,'items'=>[]];
    $around='(around:1400,' . $line . ')';
    $q='[out:json][timeout:35];(node["highway"="milestone"]'.$around.';node["information"="milestone"]'.$around.';);out tags;';
    $raw = http_json($config['routing']['overpass_url'], 'data=' . urlencode($q), ['Content-Type: application/x-www-form-urlencoded']);
    if (!is_array($raw)) return ['ok'=>false,'items'=>[]];
    $intervals = mr_route_ref_intervals($route);
    $items=[];
    foreach (($raw['elements'] ?? []) as $el) {
        if (!isset($el['lat'],$el['lon'])) continue;
        $tags=is_array($el['tags'] ?? null)?$el['tags']:[];
        $km=mr_parse_milestone_km($tags); if ($km===null) continue;
        $probe=['latitude'=>(float)$el['lat'],'longitude'=>(float)$el['lon']];
        $p=mr_radar_position_on_route($probe,$metricRoute,$cum);
        if ($p['distance_to_route_m'] > 500) continue;
        $ref=null;
        foreach (['road_ref','ref:road','route','network'] as $key) {
            $ref=mr_normalize_road_ref(isset($tags[$key])?(string)$tags[$key]:null);
            if ($ref) break;
        }
        if (!$ref) $ref=mr_ref_for_route_m((float)$p['route_m'],$intervals);
        if (!$ref) continue;
        $items[]=['ref'=>$ref,'km'=>$km,'route_m'=>(float)$p['route_m'],'lat'=>(float)$el['lat'],'lon'=>(float)$el['lon']];
    }
    return ['ok'=>true,'items'=>$items,'intervals'=>$intervals];
}

function mr_calibrations_from_milestones(array $milestones): array
{
    $by=[];
    foreach ($milestones as $m) $by[$m['ref']][]=$m;
    foreach ($by as &$arr) usort($arr,fn($a,$b)=>$a['km']<=>$b['km']);
    unset($arr);
    return $by;
}

function mr_estimate_route_m_from_km(string $roadRef, float $km, array $calibs): ?float
{
    $roadRef=mr_normalize_road_ref($roadRef) ?: $roadRef;
    $p=$calibs[$roadRef] ?? [];
    if (count($p)<2) return null;
    $bestPair=null; $bestScore=INF;
    for($i=1;$i<count($p);$i++){
        $a=$p[$i-1];$b=$p[$i];$dk=abs($b['km']-$a['km']);
        if($dk<0.05)continue;
        $lo=min($a['km'],$b['km']);$hi=max($a['km'],$b['km']);
        $outside=$km<$lo?$lo-$km:($km>$hi?$km-$hi:0.0);
        $score=$outside*10+$dk;
        if($outside<=4.0 && $score<$bestScore){$bestScore=$score;$bestPair=[$a,$b];}
        if($outside==0.0){$bestPair=[$a,$b];break;}
    }
    if(!$bestPair)return null;
    [$a,$b]=$bestPair;
    $ratio=($km-$a['km'])/($b['km']-$a['km']);
    if($ratio < -0.35 || $ratio > 1.35) return null;
    return $a['route_m'] + ($b['route_m']-$a['route_m'])*$ratio;
}

function mr_point_at_route_m(array $route, array $cum, float $target): ?array
{
    if (!$route) return null;
    $target=max(0.0,$target);
    for($i=1;$i<count($route);$i++){
        $aM=(float)($cum[$i-1]??0);$bM=(float)($cum[$i]??$aM);
        if($target<=$bM){
            $den=max(0.001,$bM-$aM);$t=max(0.0,min(1.0,($target-$aM)/$den));
            $a=$route[$i-1];$b=$route[$i];
            return ['lat'=>(float)$a[1]+((float)$b[1]-(float)$a[1])*$t,'lon'=>(float)$a[0]+((float)$b[0]-(float)$a[0])*$t];
        }
    }
    $last=end($route);return is_array($last)?['lat'=>(float)$last[1],'lon'=>(float)$last[0]]:null;
}

function mr_bbox_for_route(array $coords, float $pad=0.08): array
{
    $lats=array_column($coords,1);$lons=array_column($coords,0);
    return ['minLat'=>min($lats)-$pad,'maxLat'=>max($lats)+$pad,'minLon'=>min($lons)-$pad,'maxLon'=>max($lons)+$pad];
}

function mr_in_bbox(float $lat,float $lon,array $b): bool
{
    return $lat>=$b['minLat']&&$lat<=$b['maxLat']&&$lon>=$b['minLon']&&$lon<=$b['maxLon'];
}

function mr_ckan_latest_antt_radar_json_url(): ?string
{
    // Recurso oficial ANTT publicado em 28/07/2026. Mantemos URL direta como
    // fallback para o caso da API CKAN ficar indisponível no provedor do usuário.
    $fallback='https://dados.antt.gov.br/dataset/79d287f4-f5ca-4385-a17c-f61f53831f17/resource/eaace43a-aedf-4f69-949e-5001ae54173b/download/dados-dos-radares6_2026.json';
    $meta=mr_cached_text('antt_radar_ckan_meta','https://dados.antt.gov.br/api/3/action/package_show?id=radar',21600);
    $j=$meta?json_decode($meta,true):null;
    if(!is_array($j)||empty($j['success']))return $fallback;
    $c=[];
    foreach(($j['result']['resources']??[]) as $r){
        $fmt=strtoupper((string)($r['format']??''));$name=strtolower((string)($r['name']??''));
        if($fmt!=='JSON'||strpos($name,'radar')===false||strpos($name,'dicion')!==false)continue;
        $url=(string)($r['url']??'');if($url==='')continue;
        $stamp=(string)($r['last_modified']??($r['created']??''));
        $c[]=['url'=>$url,'stamp'=>$stamp];
    }
    if(!$c)return $fallback;
    usort($c,fn($a,$b)=>strcmp($b['stamp'],$a['stamp']));return $c[0]['url']?:$fallback;
}

function mr_json_records($j): array
{
    if(!is_array($j)||$j===[])return [];
    $keys=array_keys($j);$isList=$keys===range(0,count($j)-1);
    if($isList){
        // Uma lista de objetos já é o formato esperado pela ANTT.
        if(isset($j[0])&&is_array($j[0]))return $j;
        return [];
    }
    // GeoJSON e envelopes comuns de portais de dados.
    if(isset($j['features'])&&is_array($j['features'])){
        $rows=[];
        foreach($j['features'] as $f){
            if(!is_array($f))continue;
            $row=is_array($f['properties']??null)?$f['properties']:[];
            $g=$f['geometry']??null;
            if(is_array($g)&&($g['type']??'')==='Point'&&isset($g['coordinates'][0],$g['coordinates'][1])){
                $row['longitude']=$row['longitude']??$g['coordinates'][0];
                $row['latitude']=$row['latitude']??$g['coordinates'][1];
            }
            if($row)$rows[]=$row;
        }
        if($rows)return $rows;
    }
    foreach(['records','data','result','items','radares','rows'] as $k){
        if(isset($j[$k])&&is_array($j[$k])){
            $r=mr_json_records($j[$k]);if($r)return $r;
        }
    }
    // Último recurso: procura a primeira lista de objetos em até um nível.
    foreach($j as $v)if(is_array($v)){$r=mr_json_records($v);if($r)return $r;}
    return [];
}

function mr_antt_radars_for_route(array $coords): array
{
    $url=mr_ckan_latest_antt_radar_json_url();
    if(!$url)return ['ok'=>false,'items'=>[],'error'=>'recurso_antt_nao_encontrado'];
    $raw=mr_cached_text('antt_radar_latest_' . sha1($url),$url,21600);
    $j=$raw?json_decode($raw,true):null;$rows=mr_json_records($j);$b=mr_bbox_for_route($coords,0.12);$items=[];
    foreach($rows as $row){
        if(!is_array($row))continue;
        $lat=mr_decimal(mr_pick_ci($row,['latitude','lat','latitude_gps','vl_latitude','coord_latitude']));
        $lon=mr_decimal(mr_pick_ci($row,['longitude','lon','lng','long','longitude_gps','vl_longitude','coord_longitude']));
        // Alguns recursos/planilhas trocam a ordem das coordenadas. Corrige somente
        // quando a inversão cai claramente dentro da caixa geográfica do Brasil.
        if($lat!==null&&$lon!==null){
            $normalBrazil=($lat>=-34.5&&$lat<=6.5&&$lon>=-74.5&&$lon<=-33.0);
            $swappedBrazil=($lon>=-34.5&&$lon<=6.5&&$lat>=-74.5&&$lat<=-33.0);
            if(!$normalBrazil&&$swappedBrazil){$tmp=$lat;$lat=$lon;$lon=$tmp;}
        }
        if($lat===null||$lon===null||$lat < -34.5||$lat > 6.5||$lon < -74.5||$lon > -33.0||!mr_in_bbox($lat,$lon,$b))continue;
        $situ=strtoupper((string)mr_pick_ci($row,['situacao','status'],'ATIVO'));
        if(strpos($situ,'INAT')!==false||strpos($situ,'DESAT')!==false)continue;
        $road=mr_normalize_road_ref((string)mr_pick_ci($row,['rodovia','br']));
        $km=mr_decimal(mr_pick_ci($row,['km_m','km','quilometro']));
        $uf=strtoupper((string)mr_pick_ci($row,['uf','estado']));
        $con=(string)mr_pick_ci($row,['concessionaria','concessionária','concessionaria_nome','concessionaria_rodovia'],'ANTT');
        $speed=mr_parse_speed(mr_pick_ci($row,['velocidade_leve','velocidade','velocidade_regulamentada','velocidade_regulamentada_kmh','limite_velocidade','velocidade_maxima']));
        $id='antt-' . sha1($con.'|'.$road.'|'.$uf.'|'.$km.'|'.$lat.'|'.$lon);
        $items[]=['external_id'=>$id,'latitude'=>$lat,'longitude'=>$lon,'uf'=>$uf?:null,'cidade'=>mr_pick_ci($row,['municipio','município']),
            'rodovia'=>$road,'km'=>$km,'sentido'=>mr_pick_ci($row,['sentido']),'heading'=>null,'velocidade'=>$speed,
            'tipo'=>'RADAR_FIXO','situacao'=>'ATIVO','fonte'=>'ANTT_OFICIAL','data_fonte'=>date('Y-m-d'),'confiabilidade'=>'ALTA','quantidade_fontes'=>1,'ativo'=>1];
    }
    return ['ok'=>true,'items'=>$items,'resource'=>$url,'rows'=>count($rows)];
}

function mr_parse_html_rows(string $html): array
{
    $rows=[];if(!preg_match_all('/<tr\b[^>]*>(.*?)<\/tr>/isu',$html,$rr))return [];
    foreach($rr[1] as $r){if(!preg_match_all('/<t[dh]\b[^>]*>(.*?)<\/t[dh]>/isu',$r,$cc))continue;$cells=array_map('mr_clean_html_cell',$cc[1]);if($cells)$rows[]=$cells;}
    return $rows;
}

function mr_der_mg_rows_for_refs(array $refs): array
{
    $html=mr_cached_text('der_mg_radars','https://der.mg.gov.br/transportes/localizacao-de-radares-fixos-e-portateis',21600);
    if(!$html)return [];$set=array_fill_keys($refs,true);$out=[];
    foreach(mr_parse_html_rows($html) as $c){
        if(count($c)<5)continue;$road=mr_normalize_road_ref((string)($c[1]??''),'MG');$km=mr_decimal($c[2]??null);
        if(!$road||$km===null||($set&&!isset($set[$road])))continue;
        $speed=mr_parse_speed($c[4]??null);if(!$speed)continue;
        $kind=count($c)>=8?'FIXO':'PORTATIL';$city=(string)($c[3]??'');
        $out[]=['external_id'=>'der-mg-'.strtolower($kind).'-'.sha1($road.'|'.$km.'|'.$city.'|'.$speed),'rodovia'=>$road,'km'=>$km,'cidade'=>$city,'uf'=>'MG',
            'velocidade'=>$speed,'sentido'=>null,'tipo'=>$kind==='FIXO'?'RADAR_FIXO':'FISCALIZACAO_PORTATIL','situacao'=>'ATIVO',
            'fonte'=>$kind==='FIXO'?'DER_MG_OFICIAL':'DER_MG_PORTATIL','confiabilidade'=>'ALTA','quantidade_fontes'=>1,'ativo'=>1];
    }
    return $out;
}

function mr_der_rj_fixed_rows_for_refs(array $refs): array
{
    $html=mr_cached_text('der_rj_fixed','https://sgitp.der.rj.gov.br/radares-fixos',21600);if(!$html)return [];
    $marker=stripos($html,'Radares Fixos Fora de Opera');if($marker!==false)$html=substr($html,0,$marker);
    $set=array_fill_keys($refs,true);$out=[];
    foreach(mr_parse_html_rows($html) as $c){
        if(count($c)<6)continue;$road=mr_normalize_road_ref((string)($c[0]??''),'RJ');$km=mr_decimal($c[1]??null);$speed=mr_parse_speed($c[5]??null);
        if(!$road||$km===null||!$speed||($set&&!isset($set[$road])))continue;
        $out[]=['external_id'=>'der-rj-fixo-'.sha1($road.'|'.$km.'|'.($c[2]??'').'|'.($c[8]??'')),'rodovia'=>$road,'km'=>$km,'cidade'=>(string)($c[3]??''),'localidade'=>(string)($c[3]??''),'uf'=>'RJ',
            'sentido'=>(string)($c[2]??''),'velocidade'=>$speed,'equipamento'=>(string)($c[4]??''),'tipo'=>'RADAR_FIXO','situacao'=>'ATIVO','fonte'=>'DER_RJ_OFICIAL','confiabilidade'=>'ALTA','quantidade_fontes'=>1,'ativo'=>1];
    }return $out;
}

function mr_der_rj_portable_rows_for_refs(array $refs): array
{
    $html=mr_cached_text('der_rj_portable','https://sgitp.der.rj.gov.br/radares-portateis',21600);if(!$html)return [];$set=array_fill_keys($refs,true);$out=[];
    foreach(mr_parse_html_rows($html) as $c){
        if(count($c)<10)continue;$road=mr_normalize_road_ref((string)($c[2]??''),'RJ');$km=mr_decimal($c[3]??null);$speed=mr_parse_speed($c[9]??null);
        $type=(string)($c[6]??'');if(!$road||$km===null||!$speed||stripos($type,'veloc')===false||($set&&!isset($set[$road])))continue;
        $out[]=['external_id'=>'der-rj-portatil-'.sha1($road.'|'.$km.'|'.($c[4]??'').'|'.$speed),'rodovia'=>$road,'km'=>$km,'cidade'=>(string)($c[5]??''),'localidade'=>(string)($c[4]??''),'uf'=>'RJ',
            'sentido'=>trim((string)($c[7]??'').' / '.(string)($c[8]??''),' /'),'velocidade'=>$speed,'equipamento'=>'Medidor portátil','tipo'=>'FISCALIZACAO_PORTATIL','situacao'=>'ATIVO','fonte'=>'DER_RJ_PORTATIL','confiabilidade'=>'ALTA','quantidade_fontes'=>1,'ativo'=>1];
    }return $out;
}


function mr_der_rj_calibration_rows_for_refs(array $refs): array
{
    $html=mr_cached_text('der_rj_fixed_calibration','https://sgitp.der.rj.gov.br/radares-fixos',21600);
    if(!$html)return [];
    $set=array_fill_keys($refs,true);$out=[];
    foreach(mr_parse_html_rows($html) as $c){
        if(count($c)<4)continue;
        $road=mr_normalize_road_ref((string)($c[0]??''),'RJ');$km=mr_decimal($c[1]??null);
        if(!$road||$km===null||($set&&!isset($set[$road])))continue;
        $loc=trim((string)($c[3]??''));if($loc==='')continue;
        $out[]=['rodovia'=>$road,'km'=>$km,'localidade'=>$loc,'cidade'=>$loc,'uf'=>'RJ'];
    }
    return $out;
}

function mr_radar_lower(string $text): string
{
    return function_exists('mb_strtolower') ? mb_strtolower($text,'UTF-8') : strtolower($text);
}

function mr_radar_geocode_cache_dir(): string
{
    $dir=dirname(__DIR__).'/storage/cache/radar-geocode';
    if(!is_dir($dir))@mkdir($dir,0775,true);
    return $dir;
}

function mr_cached_radar_geocode(string $query): ?array
{
    $query=trim($query);if($query==='')return null;
    $file=mr_radar_geocode_cache_dir().'/'.sha1(mr_radar_lower($query)).'.json';
    if(is_file($file)&&filemtime($file)>time()-90*86400){
        $j=json_decode((string)@file_get_contents($file),true);
        if(is_array($j)&&isset($j['lat'],$j['lon']))return $j;
        if(is_array($j)&&array_key_exists('miss',$j))return null;
    }
    $g=geocode_place($query);
    if($g&&isset($g['lat'],$g['lon'])){
        $out=['lat'=>(float)$g['lat'],'lon'=>(float)$g['lon'],'label'=>(string)($g['label']??$query),'query'=>$query];
        $encoded=json_encode($out,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);if(is_string($encoded))write_runtime_file($file,$encoded);
        return $out;
    }
    $encoded=json_encode(['miss'=>true,'query'=>$query],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);if(is_string($encoded))write_runtime_file($file,$encoded);
    return null;
}

function mr_route_ref_bounds(string $ref,array $intervals): ?array
{
    $ref=mr_normalize_road_ref($ref)?:$ref;$lo=INF;$hi=-INF;
    foreach($intervals as $it){if(($it['ref']??null)!==$ref)continue;$lo=min($lo,(float)$it['start_m']);$hi=max($hi,(float)$it['end_m']);}
    return is_finite($lo)&&is_finite($hi)?[$lo,$hi]:null;
}

function mr_estimate_route_m_from_km_flexible(string $roadRef,float $km,array $calibs,array $intervals): ?float
{
    $normal=mr_estimate_route_m_from_km($roadRef,$km,$calibs);if($normal!==null)return $normal;
    $ref=mr_normalize_road_ref($roadRef)?:$roadRef;$p=$calibs[$ref]??[];$bounds=mr_route_ref_bounds($ref,$intervals);if(!$bounds||!$p)return null;
    usort($p,fn($a,$b)=>(float)$a['km']<=>(float)$b['km']);
    if(count($p)>=2){
        // O helper original evita extrapolar mais de 4 km. Para rodovias estaduais
        // isso descartava pontos oficiais quando os únicos marcos disponíveis
        // estavam alguns quilômetros antes/depois. Aqui permitimos extrapolação
        // curta, somente se a relação km físico / distância da rota for plausível.
        $pairs=[];
        for($i=1;$i<count($p);$i++){
            $a=$p[$i-1];$b=$p[$i];$dk=(float)$b['km']-(float)$a['km'];if(abs($dk)<.1)continue;
            $slope=((float)$b['route_m']-(float)$a['route_m'])/$dk;
            if(abs($slope)<550||abs($slope)>1700)continue;
            $lo=min((float)$a['km'],(float)$b['km']);$hi=max((float)$a['km'],(float)$b['km']);
            $outside=$km<$lo?$lo-$km:($km>$hi?$km-$hi:0.0);if($outside>22)continue;
            $est=(float)$a['route_m']+($km-(float)$a['km'])*$slope;
            if($est<$bounds[0]-1800||$est>$bounds[1]+1800)continue;
            $pairs[]=['est'=>$est,'score'=>$outside+abs(abs($slope)-1000)/1000];
        }
        if($pairs){usort($pairs,fn($a,$b)=>$a['score']<=>$b['score']);return max(0.0,(float)$pairs[0]['est']);}
    }
    if(count($p)!==1)return null;
    $a=$p[0];$delta=($km-(float)$a['km'])*1000.0;$cand=[(float)$a['route_m']+$delta,(float)$a['route_m']-$delta];$valid=[];
    foreach($cand as $v)if($v>=$bounds[0]-1800&&$v<=$bounds[1]+1800)$valid[]=$v;
    if(count($valid)===1)return max(0.0,$valid[0]);
    if(count($valid)>1){$mid=($bounds[0]+$bounds[1])/2;usort($valid,fn($x,$y)=>abs($x-$mid)<=>abs($y-$mid));return max(0.0,$valid[0]);}
    return null;
}

function mr_der_rj_geocoded_calibrations(array $activeRows,array $anchorRows,array $metricRoute,array $cum,array $intervals,array $baseCalibs): array
{
    $calibs=$baseCalibs;$need=[];
    foreach($activeRows as $r){$ref=mr_normalize_road_ref((string)($r['rodovia']??''));$km=mr_decimal($r['km']??null);if($ref&&$km!==null&&count($calibs[$ref]??[])<2)$need[$ref][]=$km;}
    if(!$need)return $calibs;
    $chosen=[];
    foreach($need as $ref=>$kms){
        $targets=$kms;$candidates=array_values(array_filter($anchorRows,fn($a)=>mr_normalize_road_ref((string)($a['rodovia']??''))===$ref));
        usort($candidates,function($a,$b)use($targets){$da=min(array_map(fn($k)=>abs((float)$a['km']-$k),$targets));$db=min(array_map(fn($k)=>abs((float)$b['km']-$k),$targets));return $da<=>$db;});
        foreach(array_slice($candidates,0,5) as $a)$chosen[]=$a;
    }
    $seen=[];$networkCalls=0;
    foreach($chosen as $a){
        $ref=mr_normalize_road_ref((string)($a['rodovia']??''));$km=mr_decimal($a['km']??null);$loc=trim((string)($a['localidade']??$a['cidade']??''));if(!$ref||$km===null||$loc==='')continue;
        $key=$ref.'|'.$km.'|'.$loc;if(isset($seen[$key]))continue;$seen[$key]=1;
        $queries=[];
        $city=trim((string)($a['cidade']??''));
        $queries[]=$loc.', RJ';
        if($city!==''&&mr_radar_lower($city)!==mr_radar_lower($loc))$queries[]=$loc.', '.$city.', RJ';
        $g=null;
        foreach($queries as $q){$g=mr_cached_radar_geocode($q);if($g)break;}
        if(!$g)continue;
        $m=mr_radar_position_on_route(['latitude'=>$g['lat'],'longitude'=>$g['lon']],$metricRoute,$cum);
        if((float)$m['distance_to_route_m']>7500)continue;
        $calibs[$ref][]=['ref'=>$ref,'km'=>$km,'route_m'=>(float)$m['route_m'],'lat'=>$g['lat'],'lon'=>$g['lon'],'source'=>'DER_RJ_LOCALIDADE'];
    }
    foreach($calibs as &$arr){usort($arr,fn($a,$b)=>(float)$a['km']<=>(float)$b['km']);$uniq=[];$clean=[];foreach($arr as $x){$k=round((float)$x['km'],2).'|'.round((float)$x['route_m']/50);if(isset($uniq[$k]))continue;$uniq[$k]=1;$clean[]=$x;}$arr=$clean;}unset($arr);
    return $calibs;
}

function mr_position_rows_with_route_fallback(array $rows,array $calibs,array $metricRoute,array $cum,array $intervals): array
{
    $out=[];$unmatched=0;$approx=0;
    foreach($rows as $r){
        $ref=mr_normalize_road_ref((string)($r['rodovia']??''));$km=mr_decimal($r['km']??null);if(!$ref||$km===null){$unmatched++;continue;}
        $rm=mr_estimate_route_m_from_km_flexible($ref,$km,$calibs,$intervals);
        if($rm!==null){$p=mr_point_at_route_m($metricRoute,$cum,$rm);if($p){$r['latitude']=$p['lat'];$r['longitude']=$p['lon'];$r['data_fonte']=date('Y-m-d');$r['position_method']=count($calibs[$ref]??[])>=2?'KM_CALIBRADO':'KM_ANCHOR_1';$r['position_accuracy']='RODOVIA_KM';$out[]=$r;continue;}}
        $loc=trim((string)($r['localidade']??''));$city=trim((string)($r['cidade']??''));$g=null;
        if($loc!==''){
            foreach([$loc.($city!==''&&mr_radar_lower($city)!==mr_radar_lower($loc)?', '.$city:'' ).', RJ',$ref.' km '.str_replace('.',',',(string)$km).', '.$loc.', RJ'] as $q){$g=mr_cached_radar_geocode($q);if($g)break;}
        }
        if($g){$m=mr_radar_position_on_route(['latitude'=>$g['lat'],'longitude'=>$g['lon']],$metricRoute,$cum);if((float)$m['distance_to_route_m']<=6000){$p=mr_point_at_route_m($metricRoute,$cum,(float)$m['route_m']);if($p){$r['latitude']=$p['lat'];$r['longitude']=$p['lon'];$r['data_fonte']=date('Y-m-d');$r['position_method']='LOCALIDADE_SNAP';$r['position_accuracy']='TRECHO_APROXIMADO';$r['alert_radius_m']=300;$out[]=$r;$approx++;continue;}}}
        $unmatched++;
    }
    return ['items'=>$out,'unmatched'=>$unmatched,'approximate'=>$approx];
}

function mr_route_hits_bbox(array $coords,float $minLat,float $maxLat,float $minLon,float $maxLon): bool
{
    $sample=mr_sample_route($coords,80);$hits=0;
    foreach($sample as $c){$lon=(float)$c[0];$lat=(float)$c[1];if($lat>=$minLat&&$lat<=$maxLat&&$lon>=$minLon&&$lon<=$maxLon)$hits++;}
    return $hits>=1;
}

function mr_position_rows_with_milestones(array $rows,array $calibs,array $metricRoute,array $cum,array $intervals=[]): array
{
    $out=[];$unmatched=0;
    foreach($rows as $r){$ref=mr_normalize_road_ref((string)($r['rodovia']??''));$km=mr_decimal($r['km']??null);if(!$ref||$km===null){$unmatched++;continue;}
        $rm=$intervals?mr_estimate_route_m_from_km_flexible($ref,$km,$calibs,$intervals):mr_estimate_route_m_from_km($ref,$km,$calibs);if($rm===null){$unmatched++;continue;}$p=mr_point_at_route_m($metricRoute,$cum,$rm);if(!$p){$unmatched++;continue;}
        $r['latitude']=$p['lat'];$r['longitude']=$p['lon'];$r['data_fonte']=date('Y-m-d');$r['position_method']=count($calibs[$ref]??[])>=2?'OSM_MILESTONE':'KM_ANCHOR_1';$r['position_accuracy']='RODOVIA_KM';$out[]=$r;
    }
    return ['items'=>$out,'unmatched'=>$unmatched];
}

function mr_xlsx_first_sheet_rows(string $bytes): array
{
    if (!class_exists('ZipArchive')) return [];
    $tmp = tempnam(sys_get_temp_dir(), 'mr-xlsx-');
    if ($tmp === false) return [];
    file_put_contents($tmp, $bytes);
    $zip = new ZipArchive();
    if ($zip->open($tmp) !== true) { @unlink($tmp); return []; }
    $shared=[];
    $sharedXml=$zip->getFromName('xl/sharedStrings.xml');
    if($sharedXml!==false){
        $sx=@simplexml_load_string($sharedXml,'SimpleXMLElement',LIBXML_NONET|LIBXML_COMPACT);
        if($sx){foreach($sx->si as $si){$parts=[];if(isset($si->t))$parts[]=(string)$si->t;foreach($si->r as $run)if(isset($run->t))$parts[]=(string)$run->t;$shared[]=implode('',$parts);}}
    }
    $sheet=$zip->getFromName('xl/worksheets/sheet1.xml');
    if($sheet===false){
        // tenta descobrir a primeira planilha no workbook
        $wb=$zip->getFromName('xl/workbook.xml');$rels=$zip->getFromName('xl/_rels/workbook.xml.rels');
        if($wb!==false&&$rels!==false){
            $w=@simplexml_load_string($wb,'SimpleXMLElement',LIBXML_NONET|LIBXML_COMPACT);$r=@simplexml_load_string($rels,'SimpleXMLElement',LIBXML_NONET|LIBXML_COMPACT);
            if($w&&$r){
                $w->registerXPathNamespace('r','http://schemas.openxmlformats.org/officeDocument/2006/relationships');
                $sheets=$w->sheets->sheet??[];$first=null;foreach($sheets as $sh){$a=$sh->attributes('http://schemas.openxmlformats.org/officeDocument/2006/relationships');$first=(string)($a['id']??'');break;}
                if($first!==''){foreach($r->Relationship as $rel){if((string)$rel['Id']===$first){$target=(string)$rel['Target'];$target=preg_replace('~^/xl/~','',$target);$target=ltrim($target,'/');if(strpos($target,'worksheets/')!==0)$target='worksheets/'.basename($target);$sheet=$zip->getFromName('xl/'.$target);break;}}}
            }
        }
    }
    $zip->close();@unlink($tmp);if($sheet===false||$sheet==='')return [];
    $x=@simplexml_load_string($sheet,'SimpleXMLElement',LIBXML_NONET|LIBXML_COMPACT);if(!$x)return [];$rows=[];
    foreach($x->sheetData->row as $row){$vals=[];$last=-1;foreach($row->c as $cell){
        $ref=(string)$cell['r'];if(preg_match('/^([A-Z]+)/',$ref,$m)){$col=0;foreach(str_split($m[1]) as $ch)$col=$col*26+(ord($ch)-64);$col--; }else $col=$last+1;
        while($last+1<$col){$vals[]='';$last++;}
        $type=(string)$cell['t'];$v=(string)($cell->v??'');
        if($type==='s'&&$v!==''&&isset($shared[(int)$v]))$v=$shared[(int)$v];
        elseif($type==='inlineStr'&&isset($cell->is->t))$v=(string)$cell->is->t;
        $vals[]=$v;$last=$col;
    }if($vals)$rows[]=$vals;}
    return $rows;
}

function mr_tabular_records_from_bytes(string $bytes, string $url): array
{
    $path=strtolower(parse_url($url,PHP_URL_PATH)??'');
    if(str_ends_with($path,'.xlsx')){
        $rows=mr_xlsx_first_sheet_rows($bytes);if(!$rows)return [];$header=array_shift($rows);$out=[];
        foreach($rows as $c){$row=[];foreach($header as $i=>$h){$h=trim((string)$h);if($h!=='')$row[$h]=$c[$i]??null;}if($row)$out[]=$row;}return $out;
    }
    $fh=fopen('php://temp','r+');fwrite($fh,$bytes);rewind($fh);$header=fgetcsv($fh,0,';');
    if(!$header||count($header)<3){rewind($fh);$header=fgetcsv($fh,0,',');$del=',';}else$del=';';
    if(!$header){fclose($fh);return [];}$out=[];
    while(($c=fgetcsv($fh,0,$del))!==false){if(count($c)<2)continue;$row=[];foreach($header as $i=>$h)$row[$h]=$c[$i]??null;$out[]=$row;}fclose($fh);return $out;
}

function mr_dnit_rows_from_discovered_resource(array $refs): array
{
    // O Portal Brasileiro de Dados Abertos pode mudar os URLs de recursos. O adaptador tenta descobrir
    // um CSV/XLSX público na página; se não houver, aceita override em config/env sem quebrar a rota.
    global $config;
    $url=getenv('MUSICROAD_DNIT_RADAR_URL') ?: ($config['radars']['dnit_url'] ?? '');
    if(!$url){
        $page=mr_cached_text('dnit_dataset_page','https://dados.gov.br/dados/conjuntos-dados/controle-de-velocidade1',86400);
        if($page){
            $decoded=html_entity_decode(str_replace('\\/','/',$page),ENT_QUOTES|ENT_HTML5,'UTF-8');
            if(preg_match_all('~https?://[^"\'<>\\s]+\.(?:csv|xlsx)(?:\?[^"\'<>\\s]*)?~i',$decoded,$m) && $m[0]) {
                $candidates=array_values(array_unique($m[0]));
                usort($candidates,function($a,$b){
                    $score=function($u){$u=strtolower($u);$v=0;if(strpos($u,'pncv')!==false)$v+=5;if(strpos($u,'veloc')!==false)$v+=3;$path=strtolower(parse_url($u,PHP_URL_PATH)??'');if(str_ends_with($path,'.xlsx'))$v+=2;return $v;};
                    return $score($b)<=>$score($a);
                });
                $url=$candidates[0]??'';
            }
        }
    }
    if(!$url)return ['ok'=>false,'rows'=>[],'error'=>'recurso_dnit_nao_descoberto'];
    $raw=mr_cached_text('dnit_radar_' . sha1($url),$url,86400);if(!$raw)return ['ok'=>false,'rows'=>[],'error'=>'download_dnit_falhou'];
    $records=mr_tabular_records_from_bytes($raw,$url);if(!$records)return ['ok'=>false,'rows'=>[],'error'=>'arquivo_dnit_invalido_ou_zip_nao_disponivel'];
    $out=[];$set=array_fill_keys($refs,true);
    foreach($records as $row){
        $road=mr_normalize_road_ref((string)mr_pick_ci($row,['rodovia','br']));$km=mr_decimal(mr_pick_ci($row,['km','quilometro','km do equipamento']));
        $uf=strtoupper((string)mr_pick_ci($row,['uf','estado']));$situ=strtoupper((string)mr_pick_ci($row,['situacao','status'],'ATIVO'));
        if(!$road||$km===null||($set&&!isset($set[$road]))||strpos($situ,'INAT')!==false||strpos($situ,'DESAT')!==false)continue;
        $speed=mr_parse_speed(mr_pick_ci($row,['velocidade regulamentada','velocidade','velocidade_leve','velocidade regulamentada (km/h)']));
        $out[]=['external_id'=>'dnit-'.sha1($uf.'|'.$road.'|'.$km.'|'.mr_pick_ci($row,['numero equipamento','equipamento','id'],'')),'rodovia'=>$road,'km'=>$km,'uf'=>$uf?:null,
            'cidade'=>mr_pick_ci($row,['municipio','município']),'sentido'=>mr_pick_ci($row,['sentido']),'velocidade'=>$speed,'tipo'=>'RADAR_FIXO','situacao'=>'ATIVO','fonte'=>'DNIT_PNCV_OFICIAL','confiabilidade'=>'ALTA','quantidade_fontes'=>1,'ativo'=>1];
    }return ['ok'=>true,'rows'=>$out,'resource'=>$url,'records'=>count($records)];
}

function mr_brazil_official_radars_for_route(array $coords,array $route): array
{
    [$metricRoute,$cum]=mr_route_metrics($coords);$refs=mr_route_refs_brazil($route);
    $mile=mr_osm_route_milestones($coords,$route,$metricRoute,$cum);$calibs=mr_calibrations_from_milestones($mile['items']??[]);
    $items=[];$diag=['refs'=>$refs,'milestones'=>count($mile['items']??[]),'sources'=>[]];

    $antt=mr_antt_radars_for_route($coords);$items=array_merge($items,$antt['items']??[]);$diag['sources']['antt']=['ok'=>$antt['ok']??false,'count'=>count($antt['items']??[]),'rows'=>$antt['rows']??null,'error'=>$antt['error']??null];

    $dnit=mr_dnit_rows_from_discovered_resource($refs);$dnPos=mr_position_rows_with_milestones($dnit['rows']??[],$calibs,$metricRoute,$cum);$items=array_merge($items,$dnPos['items']);$diag['sources']['dnit']=['ok'=>$dnit['ok']??false,'rows'=>count($dnit['rows']??[]),'positioned'=>count($dnPos['items']),'unmatched'=>$dnPos['unmatched'],'error'=>$dnit['error']??null];

    if(mr_route_is_in_es($coords)){
        try{$es=mr_official_der_es_radars_for_route($coords,$route);$items=array_merge($items,$es['items']??[]);$diag['sources']['der_es']=['ok'=>$es['ok']??false,'rows'=>$es['rows']??0,'positioned'=>$es['positioned']??0];}
        catch(Throwable $e){$diag['sources']['der_es']=['ok'=>false,'error'=>mr_radar_provider_exception('der_es',$e)];}
    }
    if(mr_route_hits_bbox($coords,-22.95,-14.0,-51.1,-39.7)){
        $rows=mr_der_mg_rows_for_refs($refs);$pos=mr_position_rows_with_milestones($rows,$calibs,$metricRoute,$cum);$items=array_merge($items,$pos['items']);$diag['sources']['der_mg']=['ok'=>true,'rows'=>count($rows),'positioned'=>count($pos['items']),'unmatched'=>$pos['unmatched']];
    }
    if(mr_route_hits_bbox($coords,-23.5,-20.5,-45.0,-40.7)){
        $rows=array_merge(mr_der_rj_fixed_rows_for_refs($refs),mr_der_rj_portable_rows_for_refs($refs));$pos=mr_position_rows_with_milestones($rows,$calibs,$metricRoute,$cum);$items=array_merge($items,$pos['items']);$diag['sources']['der_rj']=['ok'=>true,'rows'=>count($rows),'positioned'=>count($pos['items']),'unmatched'=>$pos['unmatched']];
    }
    return ['ok'=>true,'items'=>$items,'diagnostics'=>$diag];
}

function mr_sync_antt_national_to_db(): array
{
    // Sincronizacao nacional ANTT sem rota: usa caixa do Brasil para obter todos os pontos com coordenadas.
    $coords=[[-73.99,-33.75],[-34.79,5.28]];
    $r=mr_antt_radars_for_route($coords);$items=$r['items']??[];$n=0;$deactivated=0;$errors=0;$pdo=db();
    if(!($r['ok']??false)||count($items)<10)return ['ok'=>false,'imported'=>0,'deactivated'=>0,'error'=>$r['error']??'Base ANTT vazia; ciclo anterior preservado.','resource'=>$r['resource']??null];
    $pdo->beginTransaction();
    try{
        $deactivated=$pdo->exec("UPDATE radars SET ativo=0,situacao='INATIVO_NA_FONTE' WHERE fonte LIKE 'ANTT%'");
        foreach($items as $item){try{mr_upsert_official_radar($item);$n++;}catch(Throwable $e){$errors++;}}
        if($errors>max(5,(int)ceil(count($items)*.05)))throw new RuntimeException('Falhas excessivas ao gravar a base ANTT.');
        $pdo->commit();
    }catch(Throwable $e){if($pdo->inTransaction())$pdo->rollBack();return ['ok'=>false,'imported'=>0,'deactivated'=>0,'errors'=>$errors,'error'=>mr_radar_provider_exception('sync_antt',$e),'resource'=>$r['resource']??null];}
    return ['ok'=>true,'imported'=>$n,'deactivated'=>$deactivated,'errors'=>$errors,'error'=>null,'resource'=>$r['resource']??null];
}
