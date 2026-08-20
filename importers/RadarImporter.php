<?php
declare(strict_types=1);

final class RadarImporter
{
    public function __construct(private PDO $db) {}

    public function importRows(string $source, array $rows): array
    {
        $stats = ['total' => count($rows), 'inserted' => 0, 'updated' => 0, 'duplicates' => 0, 'error_count'=>0, 'errors' => []];
        $ownTransaction = !$this->db->inTransaction();
        if ($ownTransaction) $this->db->beginTransaction();
        try {
            foreach ($rows as $row) {
                try {
                    if (!is_array($row)) throw new RuntimeException('Registro em formato inválido.');
                    $radar = $this->normalize($source, $row);
                    if (!$radar) throw new RuntimeException('Registro sem coordenadas válidas.');
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
                    $stats['error_count']++;
                    if (count($stats['errors']) < 100) $stats['errors'][] = $e->getMessage();
                }
            }
            $stmt = $this->db->prepare('INSERT INTO import_logs (source, format, total_rows, inserted_rows, updated_rows, duplicate_rows, errors, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)');
            $stmt->execute([$source, 'AUTO', $stats['total'], $stats['inserted'], $stats['updated'], $stats['duplicates'], json_encode(['count'=>$stats['error_count'],'samples'=>$stats['errors']], JSON_UNESCAPED_UNICODE)]);
            if ($ownTransaction) $this->db->commit();
        } catch (Throwable $e) {
            if ($ownTransaction && $this->db->inTransaction()) $this->db->rollBack();
            throw $e;
        }
        return $stats;
    }

    private function normalize(string $source, array $row): ?array
    {
        $lat = $this->pick($row, ['latitude', 'lat', 'Latitude', 'LATITUDE']);
        $lon = $this->pick($row, ['longitude', 'lon', 'lng', 'Longitude', 'LONGITUDE']);
        if (!is_numeric($lat) || !is_numeric($lon) || abs((float)$lat) > 90 || abs((float)$lon) > 180) {
            return null;
        }
        $sources = mb_substr(strtoupper($source),0,80);
        $status = mb_substr(strtoupper(trim((string)($this->pick($row, ['situacao', 'situação', 'status']) ?: 'ATIVO'))),0,100);
        $active = preg_match('/DESATIV|INATIV|EXPIR|CANCEL|RETIR|SUBSTITU|REMANEJ|FORA DE OPERA/u',$status) ? 0 : 1;
        return [
            'external_id' => mb_substr(trim((string)($this->pick($row, ['id', 'external_id', 'codigo', 'Código']) ?? '')),0,190) ?: null,
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
            'situacao' => $status,
            'fonte' => $sources,
            'data_fonte' => $this->pick($row, ['data_fonte', 'atualizacao', 'atualização']),
            'confiabilidade' => $this->confidence($sources),
            'ativo' => $active,
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
        if (!empty($radar['external_id'])) {
            $exact=$this->db->prepare('SELECT * FROM radars WHERE external_id=? LIMIT 1');
            $exact->execute([$radar['external_id']]);$row=$exact->fetch(PDO::FETCH_ASSOC);if($row)return $row;
        }
        $box = 0.0005;
        $stmt = $this->db->prepare('SELECT * FROM radars WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?');
        $stmt->execute([$radar['latitude'] - $box, $radar['latitude'] + $box, $radar['longitude'] - $box, $radar['longitude'] + $box]);
        foreach ($stmt->fetchAll(PDO::FETCH_ASSOC) as $item) {
            $sameRoad = !$radar['rodovia'] || !$item['rodovia'] || mb_strtolower((string)$radar['rodovia']) === mb_strtolower((string)$item['rodovia']);
            $sameKm = !$radar['km'] || !$item['km'] || abs((float)$radar['km'] - (float)$item['km']) <= 0.2;
            $sameKind = $this->kind((string)$radar['tipo']) === $this->kind((string)($item['tipo'] ?? ''));
            if ($sameRoad && $sameKm && $sameKind && $this->distance((float)$radar['latitude'],(float)$radar['longitude'],(float)$item['latitude'],(float)$item['longitude']) <= 55) {
                return $item;
            }
        }
        return null;
    }

    private function merge(array $existing, array $radar, array $raw): void
    {
        $known=$this->db->prepare('SELECT id FROM radar_sources WHERE radar_id=? AND source_name=? AND (external_id<=>?) LIMIT 1');
        $known->execute([(int)$existing['id'],$radar['fonte'],$radar['external_id']]);
        $samePrimary=strcasecmp((string)($existing['fonte']??''),(string)$radar['fonte'])===0;
        $newSource=!$samePrimary&&!$known->fetchColumn();
        $sourceCount=max(1,(int)$existing['quantidade_fontes'])+($newSource?1:0);
        $hasOfficial = str_contains(($existing['fonte'] ?? '') . $radar['fonte'], 'DNIT') || str_contains(($existing['fonte'] ?? '') . $radar['fonte'], 'DER');
        $confidence = ($sourceCount >= 3 || ($hasOfficial && $sourceCount >= 2)) ? 'CONFIRMADA' : $this->bestConfidence((string)$existing['confiabilidade'], (string)$radar['confiabilidade']);
        $stmt = $this->db->prepare('UPDATE radars SET velocidade=COALESCE(?,velocidade),sentido=COALESCE(?,sentido),heading=COALESCE(?,heading),situacao=?,confiabilidade=?,quantidade_fontes=?,ativo=?,data_importacao=CURRENT_TIMESTAMP WHERE id=?');
        $stmt->execute([$radar['velocidade'],$radar['sentido'],$radar['heading'],$radar['situacao'],$confidence,$sourceCount,$radar['ativo'],$existing['id']]);
        if($newSource)$this->insertSource((int)$existing['id'],$radar,$raw);
    }

    private function insert(array $radar, array $raw): void
    {
        $stmt = $this->db->prepare('INSERT INTO radars (external_id, latitude, longitude, uf, cidade, rodovia, km, sentido, heading, velocidade, tipo, situacao, fonte, data_fonte, data_importacao, confiabilidade, quantidade_fontes, ativo) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, 1, ?)');
        $stmt->execute([$radar['external_id'], $radar['latitude'], $radar['longitude'], $radar['uf'], $radar['cidade'], $radar['rodovia'], $radar['km'], $radar['sentido'], $radar['heading'], $radar['velocidade'], $radar['tipo'], $radar['situacao'], $radar['fonte'], $radar['data_fonte'], $radar['confiabilidade'],$radar['ativo']]);
        $this->insertSource((int)$this->db->lastInsertId(), $radar, $raw);
    }

    private function insertSource(int $radarId, array $radar, array $raw): void
    {
        $stmt = $this->db->prepare('INSERT INTO radar_sources (radar_id, source_name, external_id, raw_payload, created_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)');
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

    private function kind(string $type): string
    {
        $type=strtoupper($type);
        if(str_contains($type,'QUEBRA')||str_contains($type,'LOMBADA')||str_contains($type,'BUMP'))return 'bump';
        if(str_contains($type,'SEMAFOR')||str_contains($type,'SIGNAL'))return 'signal';
        if(str_contains($type,'VIDEO')||str_contains($type,'OCR'))return 'video';
        return 'speed';
    }

    private function distance(float $lat1,float $lon1,float $lat2,float $lon2): float
    {
        $r=6371000.0;$dLat=deg2rad($lat2-$lat1);$dLon=deg2rad($lon2-$lon1);
        $a=sin($dLat/2)**2+cos(deg2rad($lat1))*cos(deg2rad($lat2))*sin($dLon/2)**2;
        return $r*2*atan2(sqrt($a),sqrt(1-$a));
    }
}
