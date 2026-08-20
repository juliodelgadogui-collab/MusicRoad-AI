<?php
require_once __DIR__ . '/radar_route_helpers.php';
require_once __DIR__ . '/radar_official_es.php';
require_once __DIR__ . '/radar_brazil_sources.php';

function mr_route_clean_coords(array $coords, int $maxPoints = 900): array
{
    $clean=[];
    foreach ($coords as $c) {
        if (!is_array($c) || !isset($c[0],$c[1]) || !is_numeric($c[0]) || !is_numeric($c[1])) continue;
        $lon=(float)$c[0]; $lat=(float)$c[1];
        if ($lat < -90 || $lat > 90 || $lon < -180 || $lon > 180) continue;
        $clean[]=[$lon,$lat];
    }
    if (count($clean) > $maxPoints) $clean=mr_sample_route($clean,$maxPoints);
    return $clean;
}

function mr_route_radar_bbox(array $coords, float $pad=.10): array
{
    $lats=[];$lons=[];
    foreach($coords as $c){if(isset($c[0],$c[1])){$lons[]=(float)$c[0];$lats[]=(float)$c[1];}}
    if(!$lats||!$lons)return [0,0,0,0];
    return [min($lats)-$pad,max($lats)+$pad,min($lons)-$pad,max($lons)+$pad];
}

function mr_route_radar_bboxes(array $coords, int $maxBoxes=10, float $pad=.022): array
{
    $n=count($coords);if($n<2)return [];
    $boxes=max(1,min($maxBoxes,(int)ceil(($n-1)/70)));
    $step=max(1,(int)ceil(($n-1)/$boxes));$out=[];
    for($start=0;$start<$n-1;$start+=$step){
        $segment=array_slice($coords,$start,min($step+1,$n-$start));
        $out[]=mr_route_radar_bbox($segment,$pad);
    }
    return $out;
}

function mr_route_match_limit(array $radar): float
{
    $kind=mr_route_item_kind($radar);$source=strtoupper((string)($radar['fonte']??''));$method=strtoupper((string)($radar['position_method']??''));
    if($kind==='bump')return 45.0;
    if($kind==='signal')return 65.0;
    if($kind==='video')return 100.0;
    if(str_contains($method,'ROUTE_FALLBACK'))return 90.0;
    if(str_contains($method,'KM')||str_contains($method,'GEOB'))return 650.0;
    if(str_contains($source,'ANTT')||str_contains($source,'DNIT')||str_contains($source,'DER_'))return 350.0;
    if(str_contains($source,'OPENSTREETMAP'))return 140.0;
    if(str_contains($source,'COMUN'))return 120.0;
    return 180.0;
}

function mr_route_radar_source_counts(array $items): array
{
    $out=[];
    foreach ($items as $r) {$f=(string)($r['fonte']??'BASE_LOCAL');$out[$f]=($out[$f]??0)+1;}
    arsort($out);return $out;
}

function mr_filter_radars_on_route(array $items, array $coords): array
{
    [$metricRoute,$cum]=mr_route_metrics($coords);$out=[];
    foreach (mr_dedupe_radars($items) as $r) {
        if (!isset($r['latitude'],$r['longitude']) || !is_numeric($r['latitude']) || !is_numeric($r['longitude'])) continue;
        $m=mr_radar_position_on_route($r,$metricRoute,$cum);
        $source=strtoupper((string)($r['fonte']??''));$limit=mr_route_match_limit($r);
        if ($m['distance_to_route_m'] > $limit) continue;
        $direction=mr_traffic_direction_heading(isset($r['sentido'])?(string)$r['sentido']:null);
        if($direction!==null&&$m['route_heading']!==null&&!str_contains($source,'OPENSTREETMAP')){
            $delta=mr_heading_delta($direction,(float)$m['route_heading']);
            if($delta>80.0)continue;
            $r['direction_delta_deg']=(int)round($delta);
        }
        $r['distance_to_route_m']=(int)round($m['distance_to_route_m']);
        $r['route_m']=(int)round($m['route_m']);
        $r['route_heading']=$m['route_heading']!==null?(int)round((float)$m['route_heading']):null;
        $r['velocidade']=mr_parse_speed($r['velocidade']??null);
        $out[]=$r;
    }
    usort($out,fn($a,$b)=>((int)($a['route_m']??0))<=>((int)($b['route_m']??0)));
    return $out;
}

function mr_local_radars_for_route(array $coords): array
{
    $started=microtime(true);
    $stmt=db()->prepare('SELECT * FROM radars WHERE ativo=1 AND (expires_at IS NULL OR expires_at>=CURRENT_TIMESTAMP) AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT 12000');
    $items=[];$seen=[];$truncated=0;$boxes=mr_route_radar_bboxes($coords);
    foreach($boxes as [$minLat,$maxLat,$minLon,$maxLon]){
        $stmt->execute([$minLat,$maxLat,$minLon,$maxLon]);$rows=$stmt->fetchAll()?:[];
        if(count($rows)>=12000)$truncated++;
        foreach($rows as $row){$key=(string)($row['id']??sha1(json_encode($row)));if(isset($seen[$key]))continue;$seen[$key]=true;$items[]=$row;}
    }
    foreach($items as &$r)if(empty($r['fonte']))$r['fonte']='BASE_LOCAL';unset($r);
    $on=mr_filter_radars_on_route($items,$coords);
    return ['ok'=>true,'radars'=>$on,'coverage'=>['candidates'=>count($items),'on_route'=>count($on),'boxes'=>count($boxes),'truncated_boxes'=>$truncated,'sources'=>mr_route_radar_source_counts($on)],'timing_ms'=>(int)round((microtime(true)-$started)*1000)];
}

function mr_official_radars_for_route_direct(array $coords): array
{
    global $config;
    $started=microtime(true);$items=[];$diag=[];
    try{
        $antt=mr_antt_radars_for_route($coords);$a=$antt['items']??[];$items=array_merge($items,$a);
        $diag['antt']=['ok'=>(bool)($antt['ok']??false),'candidates'=>count($a),'rows'=>$antt['rows']??0,'resource'=>$antt['resource']??null,'error'=>$antt['error']??null];
    }catch(Throwable $e){$diag['antt']=['ok'=>false,'candidates'=>0,'error'=>mr_radar_provider_exception('route_antt',$e)];}

    if(mr_route_is_in_es($coords)){
        try{
            $es=mr_official_der_es_radars_for_coords($coords);$e=$es['items']??[];$items=array_merge($items,$e);
            $diag['der_es']=['ok'=>(bool)($es['ok']??false),'rows'=>$es['rows']??0,'positioned'=>$es['positioned']??0,'roads'=>$es['roads']??[],'geobases_ok'=>$es['geobases_ok']??false,'error'=>$es['error']??null];
        }catch(Throwable $e){$diag['der_es']=['ok'=>false,'rows'=>0,'positioned'=>0,'error'=>mr_radar_provider_exception('route_der_es',$e)];}
    }

    // DNIT, DER-MG e DER-RJ publicam diversos registros por rodovia+km. Obtemos
    // as referências da rota e, quando disponíveis, marcos km OSM para posicioná-los.
    try{
        $first=$coords[0];$last=$coords[count($coords)-1];
        $url=rtrim($config['routing']['osrm_base_url'],'/').'/route/v1/driving/'.$first[0].','.$first[1].';'.$last[0].','.$last[1].'?overview=false&steps=true';
        $details=http_json($url);$routeDetails=$details['routes'][0]??null;
        if(is_array($routeDetails)){
            $refs=mr_route_refs_brazil($routeDetails);$diag['route_refs']=$refs;
            [$mr,$mc]=mr_route_metrics($coords);$mile=mr_osm_route_milestones($coords,$routeDetails,$mr,$mc);$calibs=mr_calibrations_from_milestones($mile['items']??[]);$intervals=$mile['intervals']??mr_route_ref_intervals($routeDetails);$diag['milestones']=count($mile['items']??[]);
            try{$dnit=mr_dnit_rows_from_discovered_resource($refs);$pos=mr_position_rows_with_milestones($dnit['rows']??[],$calibs,$mr,$mc,$intervals);$items=array_merge($items,$pos['items']??[]);$diag['dnit']=['ok'=>(bool)($dnit['ok']??false),'rows'=>count($dnit['rows']??[]),'positioned'=>count($pos['items']??[]),'unmatched'=>$pos['unmatched']??0,'error'=>$dnit['error']??null];}catch(Throwable $e){$diag['dnit']=['ok'=>false,'error'=>mr_radar_provider_exception('route_dnit',$e)];}
            if(mr_route_hits_bbox($coords,-22.95,-14.0,-51.1,-39.7)){$rows=mr_der_mg_rows_for_refs($refs);$pos=mr_position_rows_with_milestones($rows,$calibs,$mr,$mc,$intervals);$items=array_merge($items,$pos['items']??[]);$diag['der_mg']=['ok'=>true,'rows'=>count($rows),'positioned'=>count($pos['items']??[]),'unmatched'=>$pos['unmatched']??0];}
            if(mr_route_hits_bbox($coords,-23.5,-20.5,-45.0,-40.7)){
                $rows=array_merge(mr_der_rj_fixed_rows_for_refs($refs),mr_der_rj_portable_rows_for_refs($refs));
                $anchors=mr_der_rj_calibration_rows_for_refs($refs);
                $rjCalibs=mr_der_rj_geocoded_calibrations($rows,$anchors,$mr,$mc,$intervals,$calibs);
                $pos=mr_position_rows_with_route_fallback($rows,$rjCalibs,$mr,$mc,$intervals);
                $items=array_merge($items,$pos['items']??[]);
                $diag['der_rj']=['ok'=>true,'rows'=>count($rows),'positioned'=>count($pos['items']??[]),'approximate'=>$pos['approximate']??0,'unmatched'=>$pos['unmatched']??0,'calibrations'=>array_map('count',$rjCalibs)];
            }
        }else{$diag['road_details']=['ok'=>false,'error'=>'osrm_sem_steps'];}
    }catch(Throwable $e){$diag['road_details']=['ok'=>false,'error'=>mr_radar_provider_exception('route_details',$e)];}

    $on=mr_filter_radars_on_route($items,$coords);
    foreach($on as $r){try{mr_upsert_official_radar($r);}catch(Throwable $e){}}
    return ['ok'=>true,'radars'=>$on,'coverage'=>['candidates'=>count($items),'on_route'=>count($on),'sources'=>mr_route_radar_source_counts($on),'diagnostics'=>$diag],'timing_ms'=>(int)round((microtime(true)-$started)*1000)];
}

function mr_osm_radars_filtered_for_route(array $coords): array
{
    $started=microtime(true);$osm=mr_osm_radars_for_route($coords,1200);$items=$osm['items']??[];$on=mr_filter_radars_on_route($items,$coords);
    foreach($on as $r){try{mr_upsert_osm_radar($r);}catch(Throwable $e){}}
    [$metricRoute,$cum]=mr_route_metrics($coords);$limits=[];
    foreach(($osm['speed_limits']??[]) as $limit){
        if(!isset($limit['latitude'],$limit['longitude'],$limit['velocidade']))continue;
        $p=mr_radar_position_on_route($limit,$metricRoute,$cum);if(($p['distance_to_route_m']??INF)>140)continue;
        $limit['distance_to_route_m']=(int)round($p['distance_to_route_m']);$limit['route_m']=(int)round($p['route_m']);$limits[]=$limit;
    }
    usort($limits,fn($a,$b)=>((int)($a['route_m']??0))<=>((int)($b['route_m']??0)));
    return ['ok'=>true,'radars'=>$on,'speed_limits'=>$limits,'coverage'=>['osm_ok'=>(bool)($osm['ok']??false),'candidates'=>count($items),'on_route'=>count($on),'speed_limits'=>count($limits),'endpoint'=>$osm['endpoint']??null,'attempts'=>$osm['attempts']??[],'error'=>$osm['error']??null],'timing_ms'=>(int)round((microtime(true)-$started)*1000)];
}

function mr_antt_radars_filtered_for_route(array $coords): array
{
    $started=microtime(true);
    try{$r=mr_antt_radars_for_route($coords);$items=$r['items']??[];$on=mr_filter_radars_on_route($items,$coords);foreach($on as $x){try{mr_upsert_official_radar($x);}catch(Throwable $e){}}
        return ['ok'=>(bool)($r['ok']??false),'radars'=>$on,'coverage'=>['candidates'=>count($items),'on_route'=>count($on),'sources'=>mr_route_radar_source_counts($on),'diagnostics'=>['antt'=>['ok'=>(bool)($r['ok']??false),'candidates'=>count($items),'rows'=>$r['rows']??0,'resource'=>$r['resource']??null,'error'=>$r['error']??null]]],'timing_ms'=>(int)round((microtime(true)-$started)*1000)];
    }catch(Throwable $e){$error=mr_radar_provider_exception('route_antt',$e);return ['ok'=>false,'radars'=>[],'coverage'=>['candidates'=>0,'on_route'=>0,'diagnostics'=>['antt'=>['ok'=>false,'error'=>$error]]],'error'=>$error,'timing_ms'=>(int)round((microtime(true)-$started)*1000)];}
}

function mr_regional_radars_for_route(array $coords): array
{
    global $config;
    $started=microtime(true);$items=[];$diag=[];
    if(mr_route_is_in_es($coords)){
        try{$es=mr_official_der_es_radars_for_coords($coords);$e=$es['items']??[];$items=array_merge($items,$e);$diag['der_es']=['ok'=>(bool)($es['ok']??false),'rows'=>$es['rows']??0,'positioned'=>$es['positioned']??0,'roads'=>$es['roads']??[],'geobases_ok'=>$es['geobases_ok']??false,'error'=>$es['error']??null];}catch(Throwable $e){$diag['der_es']=['ok'=>false,'rows'=>0,'positioned'=>0,'error'=>mr_radar_provider_exception('regional_der_es',$e)];}
    }
    try{
        $first=$coords[0];$last=$coords[count($coords)-1];$url=rtrim($config['routing']['osrm_base_url'],'/').'/route/v1/driving/'.$first[0].','.$first[1].';'.$last[0].','.$last[1].'?overview=false&steps=true';$details=http_json($url);$routeDetails=$details['routes'][0]??null;
        if(is_array($routeDetails)){
            $refs=mr_route_refs_brazil($routeDetails);$diag['route_refs']=$refs;[$mr,$mc]=mr_route_metrics($coords);$mile=mr_osm_route_milestones($coords,$routeDetails,$mr,$mc);$calibs=mr_calibrations_from_milestones($mile['items']??[]);$intervals=$mile['intervals']??mr_route_ref_intervals($routeDetails);$diag['milestones']=count($mile['items']??[]);
            try{$dnit=mr_dnit_rows_from_discovered_resource($refs);$pos=mr_position_rows_with_milestones($dnit['rows']??[],$calibs,$mr,$mc,$intervals);$items=array_merge($items,$pos['items']??[]);$diag['dnit']=['ok'=>(bool)($dnit['ok']??false),'rows'=>count($dnit['rows']??[]),'positioned'=>count($pos['items']??[]),'unmatched'=>$pos['unmatched']??0,'error'=>$dnit['error']??null];}catch(Throwable $e){$diag['dnit']=['ok'=>false,'error'=>mr_radar_provider_exception('regional_dnit',$e)];}
            if(mr_route_hits_bbox($coords,-22.95,-14.0,-51.1,-39.7)){$rows=mr_der_mg_rows_for_refs($refs);$pos=mr_position_rows_with_milestones($rows,$calibs,$mr,$mc,$intervals);$items=array_merge($items,$pos['items']??[]);$diag['der_mg']=['ok'=>true,'rows'=>count($rows),'positioned'=>count($pos['items']??[]),'unmatched'=>$pos['unmatched']??0];}
            if(mr_route_hits_bbox($coords,-23.5,-20.5,-45.0,-40.7)){
                $rows=array_merge(mr_der_rj_fixed_rows_for_refs($refs),mr_der_rj_portable_rows_for_refs($refs));
                $anchors=mr_der_rj_calibration_rows_for_refs($refs);
                $rjCalibs=mr_der_rj_geocoded_calibrations($rows,$anchors,$mr,$mc,$intervals,$calibs);
                $pos=mr_position_rows_with_route_fallback($rows,$rjCalibs,$mr,$mc,$intervals);
                $items=array_merge($items,$pos['items']??[]);
                $diag['der_rj']=['ok'=>true,'rows'=>count($rows),'positioned'=>count($pos['items']??[]),'approximate'=>$pos['approximate']??0,'unmatched'=>$pos['unmatched']??0,'calibrations'=>array_map('count',$rjCalibs)];
            }
        }else{$diag['road_details']=['ok'=>false,'error'=>'osrm_sem_steps'];}
    }catch(Throwable $e){$diag['road_details']=['ok'=>false,'error'=>mr_radar_provider_exception('regional_details',$e)];}
    $on=mr_filter_radars_on_route($items,$coords);foreach($on as $r){try{mr_upsert_official_radar($r);}catch(Throwable $e){}}
    return ['ok'=>true,'radars'=>$on,'coverage'=>['candidates'=>count($items),'on_route'=>count($on),'sources'=>mr_route_radar_source_counts($on),'diagnostics'=>$diag],'timing_ms'=>(int)round((microtime(true)-$started)*1000)];
}
