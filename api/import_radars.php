<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
require __DIR__ . '/../importers/RadarImporter.php';

require_admin();
if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'POST') json_response(['ok'=>false,'error'=>'Use POST.'],405);
require_csrf();
require_session_rate_limit('radar-import',10,3600);
session_write_close();

$source = strtoupper(trim((string)($_POST['source'] ?? 'MANUAL')));
$source = preg_replace('/[^A-Z0-9_-]/','',$source) ?: 'MANUAL';
$source = mb_substr($source,0,80);
$rows = [];

if (isset($_FILES['file'])) {
    $file = $_FILES['file'];
    if (($file['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_OK || !is_uploaded_file((string)($file['tmp_name'] ?? ''))) {
        json_response(['ok'=>false,'error'=>'Falha no upload do arquivo.'],422);
    }
    $size = (int)($file['size'] ?? 0);
    if ($size <= 0 || $size > 30 * 1024 * 1024) json_response(['ok'=>false,'error'=>'O arquivo precisa ter até 30 MB.'],422);
    $name = (string)($file['name'] ?? 'radares.csv');
    $ext = strtolower(pathinfo($name,PATHINFO_EXTENSION));
    if (!in_array($ext,['json','geojson','csv','txt'],true)) json_response(['ok'=>false,'error'=>'Use JSON, GeoJSON, CSV ou TXT.'],422);
    if (in_array($ext,['json','geojson'],true)) {
        $data = json_decode((string)file_get_contents((string)$file['tmp_name']),true,512,JSON_BIGINT_AS_STRING);
        if (!is_array($data)) json_response(['ok'=>false,'error'=>'JSON inválido.'],422);
        $rawRows = $data['features'] ?? $data;
        if (!is_array($rawRows)) json_response(['ok'=>false,'error'=>'Não encontrei registros no arquivo.'],422);
        foreach ($rawRows as $row) {
            if (!is_array($row)) continue;
            if (isset($row['geometry']) && is_array($row['geometry'])) {
                $coordinates = $row['geometry']['coordinates'] ?? [];
                if (!is_array($coordinates)) continue;
                $row = array_merge(is_array($row['properties'] ?? null) ? $row['properties'] : [],[
                    'longitude'=>$coordinates[0] ?? null,
                    'latitude'=>$coordinates[1] ?? null,
                ]);
            }
            $rows[] = $row;
            if (count($rows) > 100000) json_response(['ok'=>false,'error'=>'O limite é de 100 mil registros por importação.'],422);
        }
    } else {
        $handle = fopen((string)$file['tmp_name'],'rb');
        if ($handle === false) json_response(['ok'=>false,'error'=>'Não foi possível ler o arquivo.'],422);
        $first = fgets($handle);
        if ($first === false) json_response(['ok'=>false,'error'=>'Arquivo vazio.'],422);
        $delimiter = substr_count($first,';') >= substr_count($first,',') ? ';' : ',';
        rewind($handle);
        $headers = fgetcsv($handle,0,$delimiter) ?: [];
        $headers = array_map(static fn($header): string => trim((string)$header),$headers);
        while (($line = fgetcsv($handle,0,$delimiter)) !== false) {
            if (count($line) !== count($headers)) continue;
            $row = array_combine($headers,$line);
            if (is_array($row)) $rows[] = $row;
            if (count($rows) > 100000) { fclose($handle); json_response(['ok'=>false,'error'=>'O limite é de 100 mil registros por importação.'],422); }
        }
        fclose($handle);
    }
} else {
    $body = input_json();
    $rows = is_array($body['rows'] ?? null) ? $body['rows'] : [];
    if (count($rows) > 100000) json_response(['ok'=>false,'error'=>'O limite é de 100 mil registros por importação.'],422);
}

if (!$rows) json_response(['ok'=>false,'error'=>'Nenhum registro válido foi enviado.'],422);
$result = (new RadarImporter(db()))->importRows($source,$rows);
audit_log('radars.import',['source'=>$source,'result'=>$result]);
json_response(['ok'=>true,'result'=>$result]);
