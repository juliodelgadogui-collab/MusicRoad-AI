<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
require_admin();
require_csrf();
require __DIR__ . '/radar_db.php';
radar_ensure_tables();
require __DIR__ . '/../importers/RadarImporter.php';

$source = strtoupper(trim((string)($_POST['source'] ?? $_GET['source'] ?? 'MANUAL')));
$rows = [];

if (!empty($_FILES['file']['tmp_name'])) {
    $ext = strtolower(pathinfo((string)$_FILES['file']['name'], PATHINFO_EXTENSION));
    if ($ext === 'json' || $ext === 'geojson') {
        $data = json_decode((string)file_get_contents($_FILES['file']['tmp_name']), true);
        if(!is_array($data))json_response(['ok'=>false,'error'=>'JSON inválido.'],422);
        $rows = $data['features'] ?? $data;
        $rows = array_map(fn($r) => is_array($r)&&isset($r['geometry']) ? array_merge($r['properties'] ?? [], ['longitude' => $r['geometry']['coordinates'][0] ?? null, 'latitude' => $r['geometry']['coordinates'][1] ?? null]) : $r, is_array($rows)?$rows:[]);
    } else {
        $fh = fopen($_FILES['file']['tmp_name'], 'r');
        if(!$fh)json_response(['ok'=>false,'error'=>'Não foi possível ler o arquivo.'],422);
        $headers = fgetcsv($fh, 0, ';') ?: [];
        while (($line = fgetcsv($fh, 0, ';')) !== false) {
            if(count($headers)!==count($line))continue;
            $row=array_combine($headers, $line);
            if(is_array($row))$rows[]=$row;
        }
        fclose($fh);
    }
} else {
    $body = input_json();
    $rows = $body['rows'] ?? [];
}

$result = (new RadarImporter(db()))->importRows($source, is_array($rows) ? $rows : []);
audit_log('radars.import', ['source' => $source, 'result' => $result]);
json_response(['ok' => true, 'result' => $result]);
