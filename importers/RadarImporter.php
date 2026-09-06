<?php
declare(strict_types=1);

/**
 * Historical class name kept for compatibility. It now imports every permanent
 * road-safety point, not only speed radars.
 */
final class RadarImporter
{
    public function __construct(private PDO $db) {}

    public function importRows(string $source, array $rows): array
    {
        $stats = ['total' => count($rows), 'inserted' => 0, 'updated' => 0, 'duplicates' => 0, 'errors' => []];
        foreach ($rows as $row) {
            try {
                $radar = $this->normalize($source, $row);
                if (!$radar) { if(count($stats['errors'])<100)$stats['errors'][]='Registro sem coordenadas válidas.'; continue; }
                $existing = $this->findDuplicate($radar);
                if ($existing) {
                    $this->merge($existing, $radar, $row);
                    $stats['updated']++; $stats['duplicates']++;
                } else {
                    $this->insert($radar, $row); $stats['inserted']++;
                }
            } catch (Throwable $e) {
                if(count($stats['errors'])<100)$stats['errors'][] = $e->getMessage();
            }
        }
        $stmt = $this->db->prepare('INSERT INTO import_logs (source, format, total_rows, inserted_rows, updated_rows, duplicate_rows, errors, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)');
        $stmt->execute([$source, 'AUTO', $stats['total'], $stats['inserted'], $stats['updated'], $stats['duplicates'], json_encode($stats['errors'], JSON_UNESCAPED_UNICODE), $this->now()]);
        return $stats;
    }

    private function normalize(string $source, array $row): ?array
    {
        $lat = $this->pick($row, ['latitude','lat','Latitude','LATITUDE']);
        $lon = $this->pick($row, ['longitude','lon','lng','Longitude','LONGITUDE']);
        if (!is_numeric($lat) || !is_numeric($lon) || abs((float)$lat) > 90 || abs((float)$lon) > 180) return null;
        $lat=(float)$lat;$lon=(float)$lon;$sources=strtoupper(trim($source));
        $rawUf=$this->pick($row,['uf','UF','estado']);
        $uf=function_exists('radar_normalize_uf')?radar_normalize_uf($rawUf,$lat,$lon):($rawUf?strtoupper(trim((string)$rawUf)):null);
        return [
            'external_id'=>$this->pick($row,['id','external_id','codigo','Código']),
            'latitude'=>$lat,'longitude'=>$lon,'uf'=>$uf,
            'cidade'=>$this->pick($row,['cidade','municipio','município','Municipio']),
            'rodovia'=>$this->pick($row,['rodovia','br','Rodovia','road','ref']),
            'km'=>$this->pick($row,['km','KM']),
            'sentido'=>$this->pick($row,['sentido','Sentido','direction']),
            'heading'=>$this->pick($row,['heading','direcao','direção','camera:direction']),
            'velocidade'=>$this->pick($row,['velocidade','limite','speed','maxspeed']),
            'tipo'=>$this->normalizeType($this->pick($row,['tipo','type','equipment_type','enforcement']) ?: 'RADAR'),
            'situacao'=>$this->pick($row,['situacao','situação','status']) ?: 'ATIVO',
            'fonte'=>$sources!==''?$sources:'MANUAL',
            'data_fonte'=>$this->pick($row,['data_fonte','atualizacao','atualização','updated_at']),
            'confiabilidade'=>$this->confidence($sources),
        ];
    }

    private function normalizeType(mixed $raw): string
    {
        $v=strtoupper(trim((string)$raw));
        $v=strtr($v,['Á'=>'A','À'=>'A','Ã'=>'A','Â'=>'A','É'=>'E','Ê'=>'E','Í'=>'I','Ó'=>'O','Ô'=>'O','Õ'=>'O','Ú'=>'U','Ç'=>'C','-'=>'_',' '=>'_']);
        if(str_contains($v,'QUEBRA')||str_contains($v,'LOMB')||str_contains($v,'SPEED_BUMP')||str_contains($v,'SPEED_HUMP'))return 'QUEBRA_MOLAS';
        if((str_contains($v,'SEMAFOR')||str_contains($v,'TRAFFIC_SIGNAL')||str_contains($v,'REDLIGHT'))&&(str_contains($v,'RADAR')||str_contains($v,'CAMERA')||str_contains($v,'ENFORCEMENT')||str_contains($v,'REDLIGHT')))return 'SEMAFORO_RADAR';
        if(str_contains($v,'SEMAFOR')||str_contains($v,'TRAFFIC_SIGNAL'))return 'SEMAFORO';
        if(str_contains($v,'MONITOR')||str_contains($v,'CCTV')||str_contains($v,'SURVEILLANCE')||str_contains($v,'VIDEO'))return 'CAMERA_MONITORAMENTO';
        if(str_contains($v,'CAMERA')&&!str_contains($v,'SPEED'))return 'CAMERA_MONITORAMENTO';
        if(str_contains($v,'PEDAG')||str_contains($v,'TOLL'))return 'PEDAGIO';
        if(str_contains($v,'PASSAGEM_NIVEL')||str_contains($v,'LEVEL_CROSSING'))return 'PASSAGEM_NIVEL';
        if(str_contains($v,'CONTROLADOR')||str_contains($v,'REDUTOR')||str_contains($v,'SPEED_CONTROL'))return 'RADAR';
        return 'RADAR';
    }

    private function pick(array $row, array $keys): mixed
    {
        foreach ($keys as $key) if (array_key_exists($key,$row) && $row[$key] !== '') return $row[$key];
        return null;
    }

    private function findDuplicate(array $radar): ?array
    {
        if(!empty($radar['external_id'])){
            $stmt=$this->db->prepare('SELECT * FROM radars WHERE external_id=? LIMIT 1');$stmt->execute([$radar['external_id']]);
            $hit=$stmt->fetch(PDO::FETCH_ASSOC);
            if($hit && $this->normalizeType($hit['tipo']??'RADAR')===$radar['tipo'])return $hit;
        }
        // ~55 m box, then type + road/km validation. Different equipment at the same
        // intersection is intentionally kept as separate records.
        $box=0.0005;
        $stmt=$this->db->prepare('SELECT * FROM radars WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?');
        $stmt->execute([$radar['latitude']-$box,$radar['latitude']+$box,$radar['longitude']-$box,$radar['longitude']+$box]);
        foreach($stmt->fetchAll(PDO::FETCH_ASSOC) as $item){
            if($this->normalizeType($item['tipo']??'RADAR')!==$radar['tipo'])continue;
            $sameRoad=!$radar['rodovia']||!$item['rodovia']||mb_strtolower((string)$radar['rodovia'])===mb_strtolower((string)$item['rodovia']);
            $sameKm=!$radar['km']||!$item['km']||abs((float)$radar['km']-(float)$item['km'])<=0.2;
            if($sameRoad&&$sameKm)return $item;
        }
        return null;
    }

    private function merge(array $existing,array $radar,array $raw): void
    {
        $sourceCount=max(1,(int)$existing['quantidade_fontes'])+1;
        $official=$this->isOfficial((string)($existing['fonte']??''))||$this->isOfficial((string)$radar['fonte']);
        $confidence=($sourceCount>=3||($official&&$sourceCount>=2))?'CONFIRMADA':$this->bestConfidence((string)$existing['confiabilidade'],(string)$radar['confiabilidade']);
        $stmt=$this->db->prepare("UPDATE radars SET velocidade=COALESCE(velocidade,?), sentido=COALESCE(NULLIF(sentido,''),?), uf=COALESCE(NULLIF(uf,''),?), cidade=COALESCE(NULLIF(cidade,''),?), rodovia=COALESCE(NULLIF(rodovia,''),?), heading=COALESCE(heading,?), tipo=?, situacao=COALESCE(?,situacao), confiabilidade=?, quantidade_fontes=?, ultima_confirmacao=?, ativo=1 WHERE id=?");
        $stmt->execute([$radar['velocidade'],$radar['sentido'],$radar['uf'],$radar['cidade'],$radar['rodovia'],$radar['heading'],$radar['tipo'],$radar['situacao'],$confidence,$sourceCount,$this->now(),$existing['id']]);
        $this->insertSource((int)$existing['id'],$radar,$raw);
    }

    private function insert(array $radar,array $raw): void
    {
        $stmt=$this->db->prepare('INSERT INTO radars (external_id, latitude, longitude, uf, cidade, rodovia, km, sentido, heading, velocidade, tipo, situacao, fonte, data_fonte, data_importacao, confiabilidade, quantidade_fontes, ativo) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)');
        $stmt->execute([$radar['external_id'],$radar['latitude'],$radar['longitude'],$radar['uf'],$radar['cidade'],$radar['rodovia'],$radar['km'],$radar['sentido'],$radar['heading'],$radar['velocidade'],$radar['tipo'],$radar['situacao'],$radar['fonte'],$radar['data_fonte'],$this->now(),$radar['confiabilidade'],1,1]);
        $this->insertSource((int)$this->db->lastInsertId(),$radar,$raw);
    }

    private function insertSource(int $radarId,array $radar,array $raw): void
    {
        $stmt=$this->db->prepare('INSERT INTO radar_sources (radar_id, source_name, external_id, raw_payload, created_at) VALUES (?, ?, ?, ?, ?)');
        $stmt->execute([$radarId,$radar['fonte'],$radar['external_id'],json_encode($raw,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES),$this->now()]);
    }

    private function isOfficial(string $source): bool
    {
        $s=strtoupper($source);
        foreach(['DNIT','DER','ANTT','PRF','CET','PREFEITURA','DETRAN','GOVERNO','OFICIAL'] as $token)if(str_contains($s,$token))return true;
        return false;
    }

    private function confidence(string $source): string
    {
        if($this->isOfficial($source))return 'ALTA';
        $s=strtoupper($source);if(str_contains($s,'OSM')||str_contains($s,'OPENSTREETMAP'))return 'MÉDIA';
        return 'BAIXA';
    }

    private function bestConfidence(string $a,string $b): string
    {
        $rank=['BAIXA'=>1,'MÉDIA'=>2,'MEDIA'=>2,'ALTA'=>3,'CONFIRMADA'=>4];
        return ($rank[$b]??1)>($rank[$a]??1)?$b:$a;
    }

    private function now(): string { return function_exists('radar_now')?radar_now():gmdate('Y-m-d H:i:s'); }
}
