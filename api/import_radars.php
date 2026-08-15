<?php
require __DIR__ . '/bootstrap.php';
require __DIR__ . '/../importers/RadarImporter.php';
require_admin();
require_csrf();

$source = strtoupper(trim((string)($_POST['source'] ?? $_GET['source'] ?? 'MANUAL')));
$rows = [];

if (!empty($_FILES['file']['tmp_name'])) {
    $ext = strtolower(pathinfo($_FILES['file']['name'], PATHINFO_EXTENSION));
    if ($ext === 'json' || $ext === 'geojson') {
        $data = json_decode(file_get_contents($_FILES['file']['tmp_name']), true);
        $rows = $data['features'] ?? $data;
        $rows = array_map(fn($r) => isset($r['geometry']) ? array_merge($r['properties'] ?? [], ['longitude' => $r['geometry']['coordinates'][0] ?? null, 'latitude' => $r['geometry']['coordinates'][1] ?? null]) : $r, $rows);
    } else {
        $fh = fopen($_FILES['file']['tmp_name'], 'r');
        $headers = fgetcsv($fh, 0, ';') ?: [];
        while (($line = fgetcsv($fh, 0, ';')) !== false) {
            $rows[] = array_combine($headers, $line);
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
