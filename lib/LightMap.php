<?php
declare(strict_types=1);

// MusicRoad AI v1.2.0 — mapa vetorial leve sob demanda.
// Não baixa o PBF completo do Brasil. Cada município usado vira um pequeno pacote
// de vias navegáveis, comprimido no servidor e removido por LRU quando o limite
// de armazenamento é atingido.

function lightmap_storage_dir(): string
{
    $dir = dirname(__DIR__) . '/storage/map-light';
    if (!is_dir($dir)) @mkdir($dir, 0775, true);
    return $dir;
}

function lightmap_cache_limit_mb(): int
{
    $value = 256;
    try {
        if (function_exists('app_setting')) $value = (int)(app_setting('light_map_cache_mb', '256') ?? '256');
    } catch (Throwable $e) {}
    return max(32, min(2048, $value));
}

function lightmap_safe_code(string $code): string
{
    return preg_match('/^\d{7}$/', $code) ? $code : '';
}

function lightmap_paths(string $code): array
{
    $dir = lightmap_storage_dir();
    return [
        'data' => $dir . '/' . $code . '.geojson.gz',
        'meta' => $dir . '/' . $code . '.meta.json',
        'lock' => $dir . '/' . $code . '.lock',
    ];
}

function lightmap_read_meta(string $code): ?array
{
    $code = lightmap_safe_code($code);
    if ($code === '') return null;
    $paths = lightmap_paths($code);
    if (!is_file($paths['meta'])) return null;
    $raw = @file_get_contents($paths['meta']);
    $meta = is_string($raw) ? json_decode($raw, true) : null;
    return is_array($meta) ? $meta : null;
}

function lightmap_status(string $code): array
{
    $paths = lightmap_paths($code);
    $meta = lightmap_read_meta($code) ?? [];
    $ready = is_file($paths['data']) && filesize($paths['data']) > 20;
    return [
        'ready' => $ready,
        'status' => $ready ? 'ready' : (($meta['status'] ?? '') ?: 'new'),
        'city_id' => $code,
        'city' => (string)($meta['city'] ?? ''),
        'uf' => (string)($meta['uf'] ?? ''),
        'feature_count' => (int)($meta['feature_count'] ?? 0),
        'coordinate_count' => (int)($meta['coordinate_count'] ?? 0),
        'size_bytes' => $ready ? (int)filesize($paths['data']) : 0,
        'generated_at' => $meta['generated_at'] ?? null,
        'source' => $meta['source'] ?? null,
        'has_error' => !empty($meta['last_error']),
        'bbox' => $meta['bbox'] ?? null,
    ];
}

function lightmap_atomic_write(string $path, string $data): bool
{
    $tmp = $path . '.part-' . bin2hex(random_bytes(4));
    if (@file_put_contents($tmp, $data, LOCK_EX) === false) return false;
    @chmod($tmp, 0664);
    if (!@rename($tmp, $path)) { @unlink($tmp); return false; }
    return true;
}

function lightmap_http_json(string $url, string $body, int $timeout = 95): ?array
{
    if (function_exists('http_json')) {
        try { return http_json($url, $body, ['Content-Type: application/x-www-form-urlencoded'], $timeout); }
        catch (Throwable $e) { return null; }
    }
    if (!function_exists('curl_init')) return null;
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_CONNECTTIMEOUT => 12,
        CURLOPT_TIMEOUT => $timeout,
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => $body,
        CURLOPT_ENCODING => '',
        CURLOPT_HTTPHEADER => [
            'Content-Type: application/x-www-form-urlencoded',
            'Accept: application/json',
            'User-Agent: MusicRoad-AI/1.2.0 (+light-map-cache)'
        ],
    ]);
    $raw = curl_exec($ch);
    $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    curl_close($ch);
    if (!is_string($raw) || $status < 200 || $status >= 300) return null;
    $json = json_decode($raw, true);
    return is_array($json) ? $json : null;
}

function lightmap_overpass_query(string $code, bool $fallback = false): string
{
    $local = $fallback
        ? 'way(area.a)[highway~"^(motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|residential|unclassified|living_street)$"];'
        : 'way(area.a)[highway~"^(motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link)$"];way(area.a)[highway~"^(residential|unclassified|living_street|service)$"][name];';
    return '[out:json][timeout:80][maxsize:268435456];('
        . 'rel["boundary"="administrative"]["IBGE:GEOCODIGO"="' . $code . '"];'
        . 'rel["boundary"="administrative"]["ref:IBGE"="' . $code . '"];'
        . ');map_to_area->.a;(' . $local . ');out geom tags;';
}

function lightmap_fetch_osm(string $code): array
{
    $endpoints = [
        'https://overpass-api.de/api/interpreter',
        'https://overpass.kumi.systems/api/interpreter',
    ];
    $attempts = [];
    foreach ([false, true] as $fallback) {
        $query = lightmap_overpass_query($code, $fallback);
        $body = 'data=' . rawurlencode($query);
        foreach ($endpoints as $endpoint) {
            $data = lightmap_http_json($endpoint, $body, $fallback ? 72 : 92);
            $attempts[] = basename(parse_url($endpoint, PHP_URL_HOST) ?: $endpoint) . ($fallback ? ':fallback' : ':full');
            if (is_array($data) && isset($data['elements']) && is_array($data['elements'])) {
                return ['data' => $data, 'source' => $endpoint, 'fallback' => $fallback, 'attempts' => $attempts];
            }
        }
    }
    throw new RuntimeException('Não foi possível preparar o mapa local agora. Os servidores públicos do OpenStreetMap/Overpass não responderam. Tente novamente mais tarde.');
}

function lightmap_point_segment_distance_sq(array $p, array $a, array $b): float
{
    $x = (float)$p[0]; $y = (float)$p[1];
    $x1 = (float)$a[0]; $y1 = (float)$a[1]; $x2 = (float)$b[0]; $y2 = (float)$b[1];
    $dx = $x2 - $x1; $dy = $y2 - $y1;
    if ($dx == 0.0 && $dy == 0.0) { $dx = $x - $x1; $dy = $y - $y1; return $dx*$dx + $dy*$dy; }
    $t = (($x-$x1)*$dx + ($y-$y1)*$dy) / ($dx*$dx + $dy*$dy);
    $t = max(0.0, min(1.0, $t));
    $px = $x1 + $t*$dx; $py = $y1 + $t*$dy;
    $dx = $x - $px; $dy = $y - $py;
    return $dx*$dx + $dy*$dy;
}

function lightmap_simplify_line(array $points, float $tolerance): array
{
    $count = count($points);
    if ($count <= 2 || $tolerance <= 0) return $points;
    $keep = array_fill(0, $count, false); $keep[0] = true; $keep[$count-1] = true;
    $stack = [[0, $count-1]]; $tolSq = $tolerance*$tolerance;
    while ($stack) {
        [$first, $last] = array_pop($stack);
        $maxSq = 0.0; $index = -1;
        for ($i=$first+1; $i<$last; $i++) {
            $d = lightmap_point_segment_distance_sq($points[$i], $points[$first], $points[$last]);
            if ($d > $maxSq) { $maxSq = $d; $index = $i; }
        }
        if ($index > 0 && $maxSq > $tolSq) {
            $keep[$index] = true;
            $stack[] = [$first, $index]; $stack[] = [$index, $last];
        }
    }
    $out = [];
    foreach ($points as $i => $p) if ($keep[$i]) $out[] = $p;
    return count($out) >= 2 ? $out : [$points[0], $points[$count-1]];
}

function lightmap_road_rank(string $highway): int
{
    $h = strtolower($highway);
    if (preg_match('/^(motorway|trunk)/', $h)) return 1;
    if (preg_match('/^primary/', $h)) return 2;
    if (preg_match('/^secondary/', $h)) return 3;
    if (preg_match('/^tertiary/', $h)) return 4;
    if ($h === 'residential' || $h === 'living_street') return 5;
    if ($h === 'unclassified') return 6;
    return 7;
}

function lightmap_tolerance(string $highway): float
{
    $rank = lightmap_road_rank($highway);
    if ($rank <= 2) return 0.000025;
    if ($rank <= 4) return 0.000045;
    if ($rank <= 5) return 0.000075;
    return 0.00011;
}

function lightmap_feature_from_way(array $e): ?array
{
    if (($e['type'] ?? '') !== 'way' || empty($e['geometry']) || !is_array($e['geometry'])) return null;
    $tags = is_array($e['tags'] ?? null) ? $e['tags'] : [];
    $highway = strtolower(trim((string)($tags['highway'] ?? '')));
    if ($highway === '') return null;
    if (preg_match('/^(footway|path|cycleway|steps|pedestrian|construction|proposed|raceway|bridleway|corridor|elevator|platform)$/', $highway)) return null;
    $coords = [];
    foreach ($e['geometry'] as $g) {
        if (!isset($g['lat'],$g['lon']) || !is_numeric($g['lat']) || !is_numeric($g['lon'])) continue;
        $coords[] = [round((float)$g['lon'], 6), round((float)$g['lat'], 6)];
    }
    if (count($coords) < 2) return null;
    $coords = lightmap_simplify_line($coords, lightmap_tolerance($highway));
    $props = [
        'id' => isset($e['id']) ? (string)$e['id'] : null,
        'highway' => $highway,
        'rank' => lightmap_road_rank($highway),
    ];
    foreach (['name','ref','maxspeed','oneway','surface'] as $k) if (isset($tags[$k]) && trim((string)$tags[$k]) !== '') $props[$k] = (string)$tags[$k];
    return ['type'=>'Feature','properties'=>$props,'geometry'=>['type'=>'LineString','coordinates'=>$coords]];
}

function lightmap_build_geojson(array $osm, string $code, string $uf, string $city, string $source): array
{
    $features = []; $coordCount = 0; $minLon = 180.0; $minLat = 90.0; $maxLon = -180.0; $maxLat = -90.0;
    foreach (($osm['elements'] ?? []) as $e) {
        if (!is_array($e)) continue;
        $f = lightmap_feature_from_way($e); if (!$f) continue;
        $features[] = $f;
    }
    usort($features, static function(array $a,array $b): int {
        $ra=(int)($a['properties']['rank']??9);$rb=(int)($b['properties']['rank']??9);
        if($ra!==$rb)return $ra<=>$rb;
        return strcmp((string)($a['properties']['name']??''),(string)($b['properties']['name']??''));
    });
    if (count($features) > 30000) $features = array_slice($features, 0, 30000);
    $trimmed=[];
    foreach($features as $f){
        $coords=$f['geometry']['coordinates']??[];$n=count($coords);if($n<2)continue;
        if($coordCount+$n>240000 && (int)($f['properties']['rank']??9)>4)continue;
        $coordCount+=$n;
        foreach($coords as $p){$lon=(float)$p[0];$lat=(float)$p[1];$minLon=min($minLon,$lon);$minLat=min($minLat,$lat);$maxLon=max($maxLon,$lon);$maxLat=max($maxLat,$lat);}
        $trimmed[]=$f;
    }
    $bbox = $trimmed ? [round($minLon,6),round($minLat,6),round($maxLon,6),round($maxLat,6)] : null;
    return [
        'type'=>'FeatureCollection',
        'musicRoad'=>['version'=>1,'mode'=>'city-light','city_id'=>$code,'city'=>$city,'uf'=>$uf,'generated_at'=>gmdate('c'),'source'=>$source],
        'bbox'=>$bbox,
        'features'=>$trimmed,
        '_stats'=>['feature_count'=>count($trimmed),'coordinate_count'=>$coordCount],
    ];
}

function lightmap_write_meta(string $code, array $meta): void
{
    $paths = lightmap_paths($code);
    lightmap_atomic_write($paths['meta'], json_encode($meta, JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES|JSON_PRETTY_PRINT));
}

function lightmap_prune(?int $limitMb = null): array
{
    $dir = lightmap_storage_dir(); $limit = max(32, $limitMb ?? lightmap_cache_limit_mb()) * 1024 * 1024;
    $files = glob($dir . '/*.geojson.gz') ?: []; $total = 0; $rows=[];
    foreach($files as $f){$size=(int)@filesize($f);$total+=$size;$rows[]=['path'=>$f,'size'=>$size,'mtime'=>(int)@filemtime($f)];}
    usort($rows, fn($a,$b)=>$a['mtime']<=>$b['mtime']); $removed=[];
    foreach($rows as $row){
        if($total <= $limit)break;
        $code=basename($row['path'],'.geojson.gz');
        @unlink($row['path']);@unlink(lightmap_paths($code)['meta']);$total-=$row['size'];$removed[]=$code;
    }
    return ['limit_bytes'=>$limit,'size_bytes'=>max(0,$total),'removed'=>$removed];
}

function lightmap_prepare(string $code, string $uf, string $city, bool $force=false): array
{
    $code = lightmap_safe_code($code); $uf = strtoupper(trim($uf)); $city = trim($city);
    if ($code === '' || !preg_match('/^[A-Z]{2}$/',$uf) || $city === '') throw new InvalidArgumentException('Município inválido para o mapa local.');
    $paths = lightmap_paths($code); $ttl = 45*86400;
    if (!$force && is_file($paths['data']) && filesize($paths['data']) > 20 && filemtime($paths['data']) > time()-$ttl) {
        @touch($paths['data']); return lightmap_status($code);
    }
    $lock = @fopen($paths['lock'],'c+'); if(!$lock)throw new RuntimeException('Não foi possível bloquear o cache do mapa local.');
    if(!flock($lock,LOCK_EX)){fclose($lock);throw new RuntimeException('Não foi possível bloquear o cache do mapa local.');}
    try{
        if (!$force && is_file($paths['data']) && filesize($paths['data']) > 20 && filemtime($paths['data']) > time()-$ttl) { @touch($paths['data']); return lightmap_status($code); }
        lightmap_write_meta($code,['status'=>'preparing','city_id'=>$code,'city'=>$city,'uf'=>$uf,'updated_at'=>gmdate('c')]);
        $fetched = lightmap_fetch_osm($code);
        $geo = lightmap_build_geojson($fetched['data'],$code,$uf,$city,(string)$fetched['source']);
        $stats=$geo['_stats'];unset($geo['_stats']);
        if(empty($geo['features']))throw new RuntimeException('A fonte de mapa respondeu, mas nenhuma via navegável foi encontrada para este município.');
        $json=json_encode($geo,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);
        if(!is_string($json)||strlen($json)<50)throw new RuntimeException('Falha ao montar o pacote vetorial do município.');
        $gz=gzencode($json,6);if(!is_string($gz)||strlen($gz)<20)throw new RuntimeException('Falha ao compactar o mapa local.');
        if(!lightmap_atomic_write($paths['data'],$gz))throw new RuntimeException('Não foi possível gravar o mapa local no servidor.');
        $meta=[
            'status'=>'ready','city_id'=>$code,'city'=>$city,'uf'=>$uf,
            'feature_count'=>(int)$stats['feature_count'],'coordinate_count'=>(int)$stats['coordinate_count'],
            'size_bytes'=>(int)filesize($paths['data']),'bbox'=>$geo['bbox']??null,
            'generated_at'=>gmdate('c'),'source'=>$fetched['source'],'fallback'=>(bool)$fetched['fallback'],
            'attempts'=>$fetched['attempts'],'updated_at'=>gmdate('c'),'last_error'=>null,
        ];
        lightmap_write_meta($code,$meta);lightmap_prune();@touch($paths['data']);return lightmap_status($code);
    } catch(Throwable $e){
        lightmap_write_meta($code,['status'=>'failed','city_id'=>$code,'city'=>$city,'uf'=>$uf,'last_error'=>$e->getMessage(),'updated_at'=>gmdate('c')]);
        throw $e;
    } finally { flock($lock,LOCK_UN);fclose($lock);@unlink($paths['lock']); }
}

function lightmap_stats(): array
{
    $dir=lightmap_storage_dir();$files=glob($dir.'/*.geojson.gz')?:[];$total=0;$recent=[];
    foreach($files as $f){$size=(int)@filesize($f);$total+=$size;$code=basename($f,'.geojson.gz');$m=lightmap_read_meta($code)??[];$recent[]=['city_id'=>$code,'city'=>$m['city']??'','uf'=>$m['uf']??'','size_bytes'=>$size,'feature_count'=>(int)($m['feature_count']??0),'generated_at'=>$m['generated_at']??null,'last_access'=>@filemtime($f)?:null];}
    usort($recent,fn($a,$b)=>(int)$b['last_access']<=>(int)$a['last_access']);
    $limit=lightmap_cache_limit_mb()*1024*1024;
    return ['city_count'=>count($files),'size_bytes'=>$total,'limit_bytes'=>$limit,'usage_percent'=>$limit>0?round(($total/$limit)*100,1):0,'recent'=>array_slice($recent,0,8)];
}
