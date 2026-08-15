<?php
declare(strict_types=1);

final class RadarImporter
{
    public function __construct(private PDO $db) {}

    public function importRows(string $source, array $rows): array
    {
        $stats = ['total' => count($rows), 'inserted' => 0, 'updated' => 0, 'duplicates' => 0, 'errors' => []];
        foreach ($rows as $row) {
            try {
                $radar = $this->normalize($source, $row);
                if (!$radar) {
                    $stats['errors'][] = 'Registro sem coordenadas válidas.';
                    continue;
                }
                $existing = $this->findDuplicate($radar);
                if ($existing) {
                    $this->merge($existing, $radar, $row);
                    $stats['updated']++;
                    $stats['duplicates']++;
                } else {
                    $this->insert($radar, $row);
                    $stats['inserted']++;
                }
            } catch (Throwable $e) {
                $stats['errors'][] = $e->getMessage();
            }
        }
        $stmt = $this->db->prepare('INSERT INTO import_logs (source, format, total_rows, inserted_rows, updated_rows, duplicate_rows, errors, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, datetime("now"))');
        $stmt->execute([$source, 'AUTO', $stats['total'], $stats['inserted'], $stats['updated'], $stats['duplicates'], json_encode($stats['errors'], JSON_UNESCAPED_UNICODE)]);
        return $stats;
    }

    private function normalize(string $source, array $row): ?array
    {
        $lat = $this->pick($row, ['latitude', 'lat', 'Latitude', 'LATITUDE']);
        $lon = $this->pick($row, ['longitude', 'lon', 'lng', 'Longitude', 'LONGITUDE']);
        if (!is_numeric($lat) || !is_numeric($lon) || abs((float)$lat) > 90 || abs((float)$lon) > 180) {
            return null;
        }
        $sources = strtoupper($source);
        return [
            'external_id' => $this->pick($row, ['id', 'external_id', 'codigo', 'Código']),
            'latitude' => (float)$lat,
            'longitude' => (float)$lon,
            'uf' => $this->pick($row, ['uf', 'UF', 'estado']),
            'cidade' => $this->pick($row, ['cidade', 'municipio', 'município', 'Municipio']),
            'rodovia' => $this->pick($row, ['rodovia', 'br', 'Rodovia']),
            'km' => $this->pick($row, ['km', 'KM']),
            'sentido' => $this->pick($row, ['sentido', 'Sentido']),
            'heading' => $this->pick($row, ['heading', 'direcao', 'direção']),
            'velocidade' => $this->pick($row, ['velocidade', 'limite', 'speed']),
            'tipo' => $this->pick($row, ['tipo', 'type']) ?: 'RADAR_FIXO',
            'situacao' => $this->pick($row, ['situacao', 'situação', 'status']) ?: 'ATIVO',
            'fonte' => $sources,
            'data_fonte' => $this->pick($row, ['data_fonte', 'atualizacao', 'atualização']),
            'confiabilidade' => $this->confidence($sources),
        ];
    }

    private function pick(array $row, array $keys): mixed
    {
        foreach ($keys as $key) {
            if (array_key_exists($key, $row) && $row[$key] !== '') {
                return $row[$key];
            }
        }
        return null;
    }

    private function findDuplicate(array $radar): ?array
    {
        $box = 0.0005;
        $stmt = $this->db->prepare('SELECT * FROM radars WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?');
        $stmt->execute([$radar['latitude'] - $box, $radar['latitude'] + $box, $radar['longitude'] - $box, $radar['longitude'] + $box]);
        foreach ($stmt->fetchAll(PDO::FETCH_ASSOC) as $item) {
            $sameRoad = !$radar['rodovia'] || !$item['rodovia'] || mb_strtolower((string)$radar['rodovia']) === mb_strtolower((string)$item['rodovia']);
            $sameKm = !$radar['km'] || !$item['km'] || abs((float)$radar['km'] - (float)$item['km']) <= 0.2;
            if ($sameRoad && $sameKm) {
                return $item;
            }
        }
        return null;
    }

    private function merge(array $existing, array $radar, array $raw): void
    {
        $sourceCount = (int)$existing['quantidade_fontes'] + 1;
        $hasOfficial = str_contains(($existing['fonte'] ?? '') . $radar['fonte'], 'DNIT') || str_contains(($existing['fonte'] ?? '') . $radar['fonte'], 'DER');
        $confidence = ($sourceCount >= 3 || ($hasOfficial && $sourceCount >= 2)) ? 'CONFIRMADA' : $this->bestConfidence((string)$existing['confiabilidade'], (string)$radar['confiabilidade']);
        $stmt = $this->db->prepare('UPDATE radars SET velocidade = COALESCE(velocidade, ?), sentido = COALESCE(sentido, ?), situacao = COALESCE(?, situacao), confiabilidade = ?, quantidade_fontes = ?, ultima_confirmacao = datetime("now") WHERE id = ?');
        $stmt->execute([$radar['velocidade'], $radar['sentido'], $radar['situacao'], $confidence, $sourceCount, $existing['id']]);
        $this->insertSource((int)$existing['id'], $radar, $raw);
    }

    private function insert(array $radar, array $raw): void
    {
        $stmt = $this->db->prepare('INSERT INTO radars (external_id, latitude, longitude, uf, cidade, rodovia, km, sentido, heading, velocidade, tipo, situacao, fonte, data_fonte, data_importacao, confiabilidade, quantidade_fontes, ativo) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime("now"), ?, 1, 1)');
        $stmt->execute([$radar['external_id'], $radar['latitude'], $radar['longitude'], $radar['uf'], $radar['cidade'], $radar['rodovia'], $radar['km'], $radar['sentido'], $radar['heading'], $radar['velocidade'], $radar['tipo'], $radar['situacao'], $radar['fonte'], $radar['data_fonte'], $radar['confiabilidade']]);
        $this->insertSource((int)$this->db->lastInsertId(), $radar, $raw);
    }

    private function insertSource(int $radarId, array $radar, array $raw): void
    {
        $stmt = $this->db->prepare('INSERT INTO radar_sources (radar_id, source_name, external_id, raw_payload, created_at) VALUES (?, ?, ?, ?, datetime("now"))');
        $stmt->execute([$radarId, $radar['fonte'], $radar['external_id'], json_encode($raw, JSON_UNESCAPED_UNICODE)]);
    }

    private function confidence(string $source): string
    {
        return match (true) {
            str_contains($source, 'DNIT'), str_contains($source, 'DER') => 'ALTA',
            str_contains($source, 'OSM'), str_contains($source, 'OPENSTREETMAP') => 'MÉDIA',
            default => 'BAIXA',
        };
    }

    private function bestConfidence(string $a, string $b): string
    {
        $rank = ['BAIXA' => 1, 'MÉDIA' => 2, 'MEDIA' => 2, 'ALTA' => 3, 'CONFIRMADA' => 4];
        return ($rank[$b] ?? 1) > ($rank[$a] ?? 1) ? $b : $a;
    }
}
