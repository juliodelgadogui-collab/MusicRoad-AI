<?php
declare(strict_types=1);

// EPC_OFFICIAL_DATA_V250: strict provenance policy for server-side road intelligence.
// The current radar importer already treats DNIT and DER as high-confidence official sources.
// Community/live sources stay isolated from the official alert base.

function epc_official_source_markers(): array
{
    return ['DNIT', 'DER'];
}

function epc_official_data_only(): bool
{
    $mode = strtoupper(trim((string)app_setting('road_data_mode', 'OFFICIAL')));
    return $mode !== 'COMMUNITY';
}

function epc_source_is_official(?string $source): bool
{
    $source = strtoupper(trim((string)$source));
    if ($source === '') return false;
    foreach (epc_official_source_markers() as $marker) {
        if (str_contains($source, $marker)) return true;
    }
    return false;
}

function epc_official_source_sql(string $expression): string
{
    $parts = [];
    foreach (epc_official_source_markers() as $marker) {
        $safe = str_replace("'", "''", $marker);
        $parts[] = "UPPER(COALESCE({$expression},'')) LIKE '%{$safe}%'";
    }
    return '(' . implode(' OR ', $parts) . ')';
}

function epc_official_radar_sql(string $alias = 'r'): string
{
    $alias = preg_replace('/[^A-Za-z0-9_]/', '', $alias) ?: 'r';
    $direct = epc_official_source_sql($alias . '.fonte');
    $linked = epc_official_source_sql('rs.source_name');
    return "({$direct} OR EXISTS (SELECT 1 FROM radar_sources rs WHERE rs.radar_id={$alias}.id AND {$linked}))";
}

function epc_table_exists(string $table): bool
{
    return schema_columns($table) !== [];
}

function epc_scalar_int(string $sql, array $params = []): int
{
    try {
        $s = db()->prepare($sql);
        $s->execute($params);
        return (int)($s->fetchColumn() ?: 0);
    } catch (Throwable $e) {
        return 0;
    }
}
