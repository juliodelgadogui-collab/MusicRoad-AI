<?php

function mr_import_decimal($value): ?float
{
    if ($value === null) return null;
    $s = trim((string)$value);
    if ($s === '') return null;
    $s = str_replace(['\xC2\xA0',' '], '', $s);
    // Coordenadas de bases GPS normalmente usam ponto. Aceita vírgula decimal quando não há outro separador.
    if (substr_count($s, ',') === 1 && strpos($s, '.') === false) $s = str_replace(',', '.', $s);
    $s = preg_replace('/[^0-9+\-.]/', '', $s) ?? '';
    return $s !== '' && is_numeric($s) ? (float)$s : null;
}

function mr_import_in_brazil(float $lat, float $lon): bool
{
    return $lat >= -34.8 && $lat <= 6.5 && $lon >= -74.8 && $lon <= -32.5;
}

function mr_import_lower(string $s): string { return function_exists('mb_strtolower') ? mb_strtolower($s,'UTF-8') : strtolower($s); }
function mr_import_cut(string $s,int $n): string { return function_exists('mb_substr') ? mb_substr($s,0,$n,'UTF-8') : substr($s,0,$n); }

function mr_import_type($raw, string $text = ''): string
{
    $n = is_numeric($raw) ? (int)$raw : null;
    if ($n === 1) return 'RADAR_FIXO';
    if ($n === 2) return 'SEMAFORO_RADAR';
    if ($n === 3) return 'SEMAFORO_CAMERA';
    if ($n === 5) return 'RADAR_MOVEL';
    $t = mr_import_lower($text);
    if (str_contains($t, 'móvel') || str_contains($t, 'movel')) return 'RADAR_MOVEL';
    if (str_contains($t, 'semáforo') || str_contains($t, 'semaforo')) {
        return str_contains($t, 'radar') ? 'SEMAFORO_RADAR' : 'SEMAFORO_CAMERA';
    }
    if (str_contains($t, 'lombada')) return 'LOMBADA_ELETRONICA';
    return 'RADAR_FIXO';
}

function mr_import_speed($raw, string $text = ''): ?int
{
    if ($raw !== null && is_numeric($raw)) {
        $n = (int)round((float)$raw);
        if ($n >= 10 && $n <= 180) return $n;
    }
    if (preg_match('/\b(20|30|40|50|60|70|80|90|100|110|120|130|140|150|160)\s*(?:km\s*\/?\s*h|kmh|km\\h)?\b/iu', $text, $m)) return (int)$m[1];
    return null;
}

function mr_import_direction($raw): ?float
{
    if ($raw === null || !is_numeric($raw)) return null;
    $n = fmod(((float)$raw + 3600.0), 360.0);
    return $n;
}

function mr_import_split_line(string $line): array
{
    $line = trim($line);
    if ($line === '') return [];
    foreach (["\t", '|', ';'] as $sep) {
        if (substr_count($line, $sep) >= 2) return array_map('trim', str_getcsv($line, $sep));
    }
    // CSV com vírgula é o formato comum do iGO/MapaRadar.
    if (substr_count($line, ',') >= 2) return array_map('trim', str_getcsv($line, ','));
    // Formato TXT antigo: longitude latitude descrição...
    if (preg_match('/^\s*(-?\d{2,3}\.\d+)\s+(-?\d{1,2}\.\d+)\s+(.+)$/u', $line, $m)) return [$m[1],$m[2],$m[3]];
    return [];
}

function mr_import_row_to_radar(array $cols, string $source): ?array
{
    if (count($cols) < 2) return null;
    $first = mr_import_decimal($cols[0] ?? null);
    $second = mr_import_decimal($cols[1] ?? null);
    if ($first === null || $second === null) return null;

    // MapaRadar/iGO: X=longitude, Y=latitude. Também aceita arquivo invertido.
    $lon = $first; $lat = $second;
    if (!mr_import_in_brazil($lat,$lon) && mr_import_in_brazil($first,$second)) { $lat=$first; $lon=$second; }
    if (!mr_import_in_brazil($lat,$lon)) return null;

    $text = trim(implode(' ', array_slice($cols,2)));
    $typeRaw = $cols[2] ?? null;
    $speedRaw = $cols[3] ?? null;
    $dirRaw = $cols[5] ?? ($cols[4] ?? null);
    $type = mr_import_type($typeRaw,$text);
    $speed = mr_import_speed($speedRaw,$text);
    $heading = mr_import_direction($dirRaw);

    $key = strtolower($source).'|'.number_format($lat,6,'.','').'|'.number_format($lon,6,'.','').'|'.$type;
    return [
        'external_id' => 'import-'.sha1($key),
        'latitude' => $lat,
        'longitude' => $lon,
        'velocidade' => $speed,
        'heading' => $heading,
        'tipo' => $type,
        'situacao' => 'ATIVO',
        'fonte' => $source,
        'confiabilidade' => $source === 'MAPARADAR_USUARIO' ? 'MÉDIA' : 'MÉDIA',
        'quantidade_fontes' => 1,
        'ativo' => 1,
        'descricao' => mr_import_cut($text,240),
    ];
}

function mr_import_parse_kml(string $raw, string $source): array
{
    $out=[];
    if (!class_exists('DOMDocument')) return $out;
    $doc=new DOMDocument();$prev=libxml_use_internal_errors(true);@$doc->loadXML($raw,LIBXML_NONET|LIBXML_COMPACT|LIBXML_NOERROR|LIBXML_NOWARNING);libxml_clear_errors();libxml_use_internal_errors($prev);
    $xp=new DOMXPath($doc);$xp->registerNamespace('k','http://www.opengis.net/kml/2.2');
    foreach ($xp->query('//*[local-name()="Placemark"]') as $pm) {
        $name='';$nameNode=$xp->query('.//*[local-name()="name"]',$pm)->item(0);if($nameNode)$name=trim($nameNode->textContent);
        $coordNode=$xp->query('.//*[local-name()="Point"]/*[local-name()="coordinates"]',$pm)->item(0);if(!$coordNode)continue;
        $parts=preg_split('/\s*,\s*/',trim($coordNode->textContent));if(count($parts)<2)continue;
        $r=mr_import_row_to_radar([$parts[0],$parts[1],$name],$source);if($r)$out[]=$r;
    }
    return $out;
}

function mr_import_parse_bytes(string $raw, string $filename, string $source): array
{
    $raw=preg_replace('/^\xEF\xBB\xBF/','',$raw)??$raw;
    $ext=strtolower(pathinfo($filename,PATHINFO_EXTENSION));
    if ($ext==='kml' || stripos($raw,'<kml')!==false) return mr_import_parse_kml($raw,$source);
    $out=[];
    foreach (preg_split('/\R/u',$raw) ?: [] as $line) {
        $line=trim($line);if($line===''||preg_match('/^(?:X\s*[,;|\t]\s*Y|longitude|lon\b)/iu',$line))continue;
        $cols=mr_import_split_line($line);if(!$cols)continue;
        $r=mr_import_row_to_radar($cols,$source);if($r)$out[]=$r;
    }
    return $out;
}
