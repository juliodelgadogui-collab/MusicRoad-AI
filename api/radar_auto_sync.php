<?php
/**
 * MusicRoad - sincronização automática da base de fiscalização.
 *
 * - banco vazio: sincroniza imediatamente;
 * - base existente: atualiza a cada 12 horas;
 * - evita sincronizações concorrentes;
 * - em falha, preserva os registros já existentes.
 */
function mr_auto_sync_radars_if_due(bool $force = false): array
{
    ensure_schema();

    try { $total = (int)db()->query('SELECT COUNT(*) FROM radars')->fetchColumn(); }
    catch (Throwable $e) { return ['ok'=>false,'ran'=>false,'error'=>'db: '.$e->getMessage()]; }

    $lastSuccess = (int)(app_setting('radar_auto_sync_last_success', '0') ?? 0);
    $lastAttempt = (int)(app_setting('radar_auto_sync_last_attempt', '0') ?? 0);
    $now = time();
    $due = $force || $total === 0 || $lastSuccess <= 0 || ($now - $lastSuccess) >= 43200;
    if (!$due) return ['ok'=>true,'ran'=>false,'reason'=>'fresh','total'=>$total];
    if (!$force && $total > 0 && $lastAttempt > 0 && ($now - $lastAttempt) < 900) {
        return ['ok'=>true,'ran'=>false,'reason'=>'retry_backoff','total'=>$total];
    }

    $lockDir = __DIR__ . '/../storage/cache';
    if (!is_dir($lockDir)) @mkdir($lockDir, 0775, true);
    $fh = @fopen($lockDir . '/radar-auto-sync.lock', 'c+');
    if (!$fh || !@flock($fh, LOCK_EX | LOCK_NB)) {
        if ($fh) @fclose($fh);
        return ['ok'=>true,'ran'=>false,'reason'=>'locked','total'=>$total];
    }

    set_app_setting('radar_auto_sync_last_attempt', (string)$now);
    $result = ['ok'=>false,'ran'=>true,'before'=>$total,'antt'=>['ok'=>false,'imported'=>0],'der_es'=>['ok'=>false,'imported'=>0]];

    try {
        require_once __DIR__ . '/radar_route_helpers.php';
        require_once __DIR__ . '/radar_official_es.php';
        require_once __DIR__ . '/radar_brazil_sources.php';

        try { $result['antt'] = mr_sync_antt_national_to_db(); }
        catch (Throwable $e) { $result['antt']=['ok'=>false,'imported'=>0,'error'=>$e->getMessage()]; }

        try { $result['der_es'] = mr_sync_der_es_to_db(); }
        catch (Throwable $e) { $result['der_es']=['ok'=>false,'imported'=>0,'error'=>$e->getMessage()]; }

        $after = (int)db()->query('SELECT COUNT(*) FROM radars')->fetchColumn();
        $result['after'] = $after;
        $result['ok'] = $after > 0 || !empty($result['antt']['ok']) || !empty($result['der_es']['ok']);
        if ($result['ok']) set_app_setting('radar_auto_sync_last_success', (string)time());
        set_app_setting('radar_auto_sync_last_result', json_encode($result, JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES));
    } finally {
        @flock($fh, LOCK_UN);
        @fclose($fh);
    }

    return $result;
}
