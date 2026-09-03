<?php
declare(strict_types=1);

require_once __DIR__ . '/api/bootstrap.php';
require_once __DIR__ . '/api/radar_db.php';
require_once __DIR__ . '/api/road_hazard_db.php';
require_once __DIR__ . '/api/server_intelligent.php';
require_once __DIR__ . '/api/official_data_policy.php';

$admin = require_admin();
radar_ensure_tables();
road_hazard_ensure_tables();
intelligent_server_ensure_schema();
ep7_ensure_schema();

function epc_source_breakdown(string $table, string $column): array
{
    if (!epc_table_exists($table)) return [];
    $tableSafe = preg_replace('/[^A-Za-z0-9_]/', '', $table);
    $columnSafe = preg_replace('/[^A-Za-z0-9_]/', '', $column);
    try {
        $rows = db()->query("SELECT COALESCE(NULLIF(TRIM({$columnSafe}),''),'(SEM FONTE)') source, COUNT(*) total FROM {$tableSafe} GROUP BY {$columnSafe} ORDER BY total DESC, source ASC")->fetchAll() ?: [];
        return array_map(static fn(array $r): array => [
            'source'=>(string)$r['source'],
            'total'=>(int)$r['total'],
            'official'=>epc_source_is_official((string)$r['source']),
        ], $rows);
    } catch (Throwable $e) {
        return [];
    }
}

function epc_cleanup_preview(): array
{
    $radarOfficialSql = epc_official_radar_sql('r');
    $hazardOfficialSql = epc_official_source_sql('source');
    $radarSourceOfficialSql = epc_official_source_sql('source_name');
    $importOfficialSql = epc_official_source_sql('source');

    $radarsTotal = epc_scalar_int('SELECT COUNT(*) FROM radars');
    $radarsOfficial = epc_scalar_int("SELECT COUNT(*) FROM radars r WHERE {$radarOfficialSql}");
    $hazardsTotal = epc_scalar_int('SELECT COUNT(*) FROM road_hazards');
    $hazardsOfficial = epc_scalar_int("SELECT COUNT(*) FROM road_hazards WHERE {$hazardOfficialSql}");
    $radarSourcesTotal = epc_scalar_int('SELECT COUNT(*) FROM radar_sources');
    $radarSourcesOfficial = epc_scalar_int("SELECT COUNT(*) FROM radar_sources WHERE {$radarSourceOfficialSql}");
    $importsTotal = epc_scalar_int('SELECT COUNT(*) FROM import_logs');
    $importsOfficial = epc_scalar_int("SELECT COUNT(*) FROM import_logs WHERE {$importOfficialSql}");

    return [
        'mode'=>(string)app_setting('road_data_mode','OFFICIAL'),
        'official_markers'=>epc_official_source_markers(),
        'radars'=>['total'=>$radarsTotal,'keep'=>$radarsOfficial,'remove'=>max(0,$radarsTotal-$radarsOfficial)],
        'radar_sources'=>['total'=>$radarSourcesTotal,'keep'=>$radarSourcesOfficial,'remove'=>max(0,$radarSourcesTotal-$radarSourcesOfficial)],
        'road_hazards'=>['total'=>$hazardsTotal,'keep'=>$hazardsOfficial,'remove'=>max(0,$hazardsTotal-$hazardsOfficial)],
        'import_logs'=>['total'=>$importsTotal,'keep'=>$importsOfficial,'remove'=>max(0,$importsTotal-$importsOfficial)],
        'community'=>[
            'road_live_events'=>epc_scalar_int('SELECT COUNT(*) FROM road_live_events'),
            'road_live_votes'=>epc_scalar_int('SELECT COUNT(*) FROM road_live_votes'),
            'road_traffic_samples'=>epc_scalar_int('SELECT COUNT(*) FROM road_traffic_samples'),
            'fuel_price_reports'=>epc_scalar_int('SELECT COUNT(*) FROM fuel_price_reports'),
            'road_reports'=>epc_scalar_int('SELECT COUNT(*) FROM road_reports'),
        ],
        'cache'=>['road_weather_cache'=>epc_scalar_int('SELECT COUNT(*) FROM road_weather_cache')],
        'sources'=>[
            'radars'=>epc_source_breakdown('radars','fonte'),
            'radar_sources'=>epc_source_breakdown('radar_sources','source_name'),
            'road_hazards'=>epc_source_breakdown('road_hazards','source'),
            'import_logs'=>epc_source_breakdown('import_logs','source'),
        ],
    ];
}

function epc_exec_delete(string $sql): int
{
    $s = db()->prepare($sql);
    $s->execute();
    return $s->rowCount();
}

function epc_execute_official_cleanup(): array
{
    $deleted = [];
    $radarOfficialSql = epc_official_radar_sql('radars');
    $hazardOfficialSql = epc_official_source_sql('source');
    $radarSourceOfficialSql = epc_official_source_sql('source_name');
    $importOfficialSql = epc_official_source_sql('source');

    db()->beginTransaction();
    try {
        foreach (['road_live_votes','road_live_events','road_traffic_samples','fuel_price_reports','road_reports'] as $table) {
            $deleted[$table] = epc_table_exists($table) ? epc_exec_delete("DELETE FROM {$table}") : 0;
        }

        $deleted['road_hazards'] = epc_table_exists('road_hazards')
            ? epc_exec_delete("DELETE FROM road_hazards WHERE NOT {$hazardOfficialSql}") : 0;
        $deleted['road_hazard_sync'] = epc_table_exists('road_hazard_sync')
            ? epc_exec_delete('DELETE FROM road_hazard_sync') : 0;

        $deleted['radars'] = epc_table_exists('radars')
            ? epc_exec_delete("DELETE FROM radars WHERE NOT {$radarOfficialSql}") : 0;
        $deleted['radar_sources_nonofficial'] = epc_table_exists('radar_sources')
            ? epc_exec_delete("DELETE FROM radar_sources WHERE NOT {$radarSourceOfficialSql}") : 0;
        $deleted['radar_sources_orphan'] = epc_table_exists('radar_sources')
            ? epc_exec_delete('DELETE FROM radar_sources WHERE radar_id NOT IN (SELECT id FROM radars)') : 0;

        $deleted['import_logs'] = epc_table_exists('import_logs')
            ? epc_exec_delete("DELETE FROM import_logs WHERE NOT {$importOfficialSql}") : 0;
        $deleted['road_weather_cache'] = epc_table_exists('road_weather_cache')
            ? epc_exec_delete('DELETE FROM road_weather_cache') : 0;

        if (epc_table_exists('radars')) {
            $rows = db()->query('SELECT id,fonte FROM radars ORDER BY id')->fetchAll() ?: [];
            $sourceSql = epc_official_source_sql('source_name');
            $q = db()->prepare("SELECT source_name FROM radar_sources WHERE radar_id=? AND {$sourceSql} ORDER BY id ASC");
            $u = db()->prepare('UPDATE radars SET fonte=?, confiabilidade=?, quantidade_fontes=? WHERE id=?');
            foreach ($rows as $row) {
                $sources = [];
                $direct = strtoupper(trim((string)($row['fonte'] ?? '')));
                if (epc_source_is_official($direct)) $sources[$direct] = true;
                $q->execute([(int)$row['id']]);
                foreach ($q->fetchAll() ?: [] as $sr) {
                    $name = strtoupper(trim((string)($sr['source_name'] ?? '')));
                    if (epc_source_is_official($name)) $sources[$name] = true;
                }
                $names = array_keys($sources);
                if (!$names) continue;
                $count = count($names);
                $u->execute([$names[0], $count >= 2 ? 'CONFIRMADA' : 'ALTA', $count, (int)$row['id']]);
            }
        }

        set_app_setting('road_data_mode','OFFICIAL');
        set_app_setting('official_data_cleanup_at',date(DATE_ATOM));
        db()->commit();
    } catch (Throwable $e) {
        if (db()->inTransaction()) db()->rollBack();
        throw $e;
    }

    audit_log('official_data.cleanup', ['deleted'=>$deleted,'markers'=>epc_official_source_markers()]);
    return ['deleted'=>$deleted,'preview_after'=>epc_cleanup_preview()];
}

$message = '';
$error = '';
if (($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'POST') {
    require_csrf();
    $confirm = strtoupper(trim((string)($_POST['confirm'] ?? '')));
    if ($confirm !== 'LIMPAR_BASE_OFICIAL') {
        $error = 'Confirmação inválida. Nenhum dado foi apagado.';
    } else {
        try {
            epc_execute_official_cleanup();
            $message = 'Limpeza concluída. A base rodoviária ficou em modo OFICIAL.';
        } catch (Throwable $e) {
            $error = 'Falha na limpeza: ' . $e->getMessage();
        }
    }
}
$preview = epc_cleanup_preview();
$csrf = csrf_token();

function h(mixed $v): string { return htmlspecialchars((string)$v, ENT_QUOTES, 'UTF-8'); }
?>
<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Estrada Play · Base Oficial</title>
<style>
body{font-family:system-ui,-apple-system,sans-serif;background:#101010;color:#eee;margin:0;padding:24px}.wrap{max-width:980px;margin:auto}.card{background:#181818;border:1px solid #383838;border-radius:16px;padding:20px;margin:14px 0}h1,h2{margin-top:0}.ok{color:#6ee7a0}.bad{color:#ff8585}.warn{color:#ffd56a}table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:9px;border-bottom:1px solid #333}code{background:#222;padding:2px 6px;border-radius:6px}input{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #555;background:#111;color:#fff}button{margin-top:12px;padding:12px 18px;border:0;border-radius:10px;background:#b41228;color:#fff;font-weight:700}small{color:#aaa}.pill{display:inline-block;padding:4px 8px;border-radius:999px;background:#282828;margin-right:6px}
</style>
</head>
<body><div class="wrap">
<h1>Base Rodoviária · Somente Oficial</h1>
<p>Administrador: <?=h($admin['name'] ?? $admin['username'] ?? 'admin')?> · modo atual: <strong><?=h($preview['mode'])?></strong></p>
<?php if($message!==''):?><div class="card ok"><strong><?=h($message)?></strong></div><?php endif;?>
<?php if($error!==''):?><div class="card bad"><strong><?=h($error)?></strong></div><?php endif;?>

<div class="card"><h2>O que será preservado</h2>
<p>Fontes oficiais reconhecidas pelo código atual: <?php foreach($preview['official_markers'] as $m):?><span class="pill"><?=h($m)?></span><?php endforeach;?></p>
<table><tr><th>Base</th><th>Total</th><th class="ok">Manter</th><th class="bad">Remover</th></tr>
<tr><td>Radares</td><td><?=h($preview['radars']['total'])?></td><td><?=h($preview['radars']['keep'])?></td><td><?=h($preview['radars']['remove'])?></td></tr>
<tr><td>Fontes de radar</td><td><?=h($preview['radar_sources']['total'])?></td><td><?=h($preview['radar_sources']['keep'])?></td><td><?=h($preview['radar_sources']['remove'])?></td></tr>
<tr><td>Outros perigos de pista</td><td><?=h($preview['road_hazards']['total'])?></td><td><?=h($preview['road_hazards']['keep'])?></td><td><?=h($preview['road_hazards']['remove'])?></td></tr>
<tr><td>Logs de importação</td><td><?=h($preview['import_logs']['total'])?></td><td><?=h($preview['import_logs']['keep'])?></td><td><?=h($preview['import_logs']['remove'])?></td></tr>
</table></div>

<div class="card"><h2>Dados comunitários/temporários</h2>
<p>Esses registros serão zerados: eventos LIVE, votos, amostras colaborativas de trânsito, preços enviados por usuários e reportes do aplicativo.</p>
<table><?php foreach($preview['community'] as $name=>$total):?><tr><td><?=h($name)?></td><td><?=h($total)?></td></tr><?php endforeach;?></table>
<p><small>O cache meteorológico também será limpo e poderá ser recriado pelo provedor de clima. Usuários, autenticação, credenciais de aparelho, músicas, Comboio, configurações e histórico administrativo não são apagados.</small></p></div>

<div class="card"><h2>Fontes encontradas</h2>
<?php foreach($preview['sources'] as $group=>$rows):?><h3><?=h($group)?></h3><table><tr><th>Fonte</th><th>Total</th><th>Classificação</th></tr><?php foreach($rows as $r):?><tr><td><?=h($r['source'])?></td><td><?=h($r['total'])?></td><td class="<?=$r['official']?'ok':'bad'?>"><?=$r['official']?'OFICIAL':'REMOVER'?></td></tr><?php endforeach;?></table><?php endforeach;?>
</div>

<div class="card"><h2>Executar limpeza</h2>
<p class="warn">Faça backup do banco antes. Esta ação é destrutiva para dados rodoviários não oficiais.</p>
<form method="post"><input type="hidden" name="csrf" value="<?=h($csrf)?>"><label>Digite <code>LIMPAR_BASE_OFICIAL</code>:</label><input name="confirm" autocomplete="off"><button type="submit">Limpar base não oficial</button></form>
</div>
</div></body></html>
