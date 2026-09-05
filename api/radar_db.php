<?php
declare(strict_types=1);
require_once __DIR__.'/road_safety_pack_helpers.php';

function radar_now(): string { return gmdate('Y-m-d H:i:s'); }

function radar_ensure_tables(): void {
    static $done=false;
    if($done)return;
    $done=true;
    $driver=(string)db()->getAttribute(PDO::ATTR_DRIVER_NAME);
    if($driver==='mysql'){
        db()->exec("CREATE TABLE IF NOT EXISTS radars (
            id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
            external_id VARCHAR(190) NULL,
            latitude DOUBLE NOT NULL,
            longitude DOUBLE NOT NULL,
            uf VARCHAR(2) NULL,
            cidade VARCHAR(190) NULL,
            rodovia VARCHAR(120) NULL,
            km VARCHAR(40) NULL,
            sentido VARCHAR(80) NULL,
            heading DOUBLE NULL,
            velocidade INT NULL,
            tipo VARCHAR(60) NULL,
            situacao VARCHAR(60) NULL,
            fonte VARCHAR(120) NULL,
            data_fonte VARCHAR(40) NULL,
            data_importacao DATETIME NOT NULL,
            ultima_confirmacao DATETIME NULL,
            confiabilidade VARCHAR(30) NOT NULL DEFAULT 'BAIXA',
            quantidade_fontes INT NOT NULL DEFAULT 1,
            ativo TINYINT(1) NOT NULL DEFAULT 1,
            INDEX idx_radars_geo (latitude,longitude),
            INDEX idx_radars_uf (uf),
            INDEX idx_radars_external (external_id),
            INDEX idx_radars_active (ativo)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        db()->exec("CREATE TABLE IF NOT EXISTS radar_sources (
            id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
            radar_id BIGINT UNSIGNED NOT NULL,
            source_name VARCHAR(120) NOT NULL,
            external_id VARCHAR(190) NULL,
            raw_payload LONGTEXT NULL,
            created_at DATETIME NOT NULL,
            INDEX idx_radar_sources_radar (radar_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        db()->exec("CREATE TABLE IF NOT EXISTS import_logs (
            id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
            source VARCHAR(120) NOT NULL,
            format VARCHAR(60) NOT NULL,
            total_rows INT NOT NULL DEFAULT 0,
            inserted_rows INT NOT NULL DEFAULT 0,
            updated_rows INT NOT NULL DEFAULT 0,
            duplicate_rows INT NOT NULL DEFAULT 0,
            errors LONGTEXT NULL,
            created_at DATETIME NOT NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
    }else{
        db()->exec("CREATE TABLE IF NOT EXISTS radars (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            external_id TEXT,
            latitude REAL NOT NULL,
            longitude REAL NOT NULL,
            uf TEXT,
            cidade TEXT,
            rodovia TEXT,
            km TEXT,
            sentido TEXT,
            heading REAL,
            velocidade INTEGER,
            tipo TEXT,
            situacao TEXT,
            fonte TEXT,
            data_fonte TEXT,
            data_importacao TEXT NOT NULL,
            ultima_confirmacao TEXT,
            confiabilidade TEXT NOT NULL DEFAULT 'BAIXA',
            quantidade_fontes INTEGER NOT NULL DEFAULT 1,
            ativo INTEGER NOT NULL DEFAULT 1
        )");
        db()->exec("CREATE TABLE IF NOT EXISTS radar_sources (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            radar_id INTEGER NOT NULL,
            source_name TEXT NOT NULL,
            external_id TEXT,
            raw_payload TEXT,
            created_at TEXT NOT NULL
        )");
        db()->exec("CREATE TABLE IF NOT EXISTS import_logs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            source TEXT NOT NULL,
            format TEXT NOT NULL,
            total_rows INTEGER DEFAULT 0,
            inserted_rows INTEGER DEFAULT 0,
            updated_rows INTEGER DEFAULT 0,
            duplicate_rows INTEGER DEFAULT 0,
            errors TEXT,
            created_at TEXT NOT NULL
        )");
        db()->exec('CREATE INDEX IF NOT EXISTS idx_radars_geo ON radars(latitude,longitude)');
        db()->exec('CREATE INDEX IF NOT EXISTS idx_radars_uf ON radars(uf)');
    }
}

function radar_normalize_uf($raw,float $lat,float $lon): ?string {
    $uf=strtoupper(trim((string)$raw));
    if(in_array($uf,['SP','RJ','MG','ES'],true))return $uf;
    $guess=ep2_guess_uf($lat,$lon);
    return $guess!==''?$guess:null;
}
