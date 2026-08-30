<?php
declare(strict_types=1);
require __DIR__ . '/api/bootstrap.php';
require_once __DIR__ . '/api/native_library_sync.php';
ensure_default_users();
$user = require_admin();

$message = '';
$error = '';

function center_sync_message(array $r): string
{
    if (!empty($r['skipped'])) {
        $reason = (string)($r['reason'] ?? '');
        return $reason === 'sync_already_running' ? 'Já existe uma sincronização em andamento.' : 'Catálogo ainda está recente; nenhuma varredura desnecessária foi executada.';
    }
    return (int)($r['tracks'] ?? 0) . ' músicas verificadas · +' . (int)($r['inserted'] ?? 0) . ' novas · ' . (int)($r['updated'] ?? 0) . ' alteradas · ' . (int)($r['removed'] ?? 0) . ' removidas · ' . count($r['errors'] ?? []) . ' aviso(s).';
}

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    require_csrf();
    $action = (string)($_POST['action'] ?? '');
    try {
        if ($action === 'sync_all') {
            $message = center_sync_message(server_sync_active_drive_folders(true, [], 'admin'));
        } elseif ($action === 'sync_folder') {
            $id = (int)($_POST['folder_id'] ?? 0);
            if ($id <= 0) throw new RuntimeException('Pasta inválida.');
            $message = center_sync_message(server_sync_active_drive_folders(true, [$id], 'admin'));
        } elseif ($action === 'housekeeping') {
            server_housekeeping(true);
            server_rotate_logs();
            $message = 'Limpeza e rotação executadas.';
        } elseif ($action === 'rotate_cron_key') {
            server_rotate_cron_key();
            $message = 'Chave do cron alterada. Atualize a URL configurada na hospedagem.';
        }
    } catch (Throwable $e) {
        error_log('ADMIN_SERVER: ' . $e->getMessage());
        $error = 'A operação falhou. Consulte o diagnóstico abaixo e o histórico de sincronizações.';
    }
}

$health = server_health_snapshot();
$folders = server_folder_health();
$history = server_sync_history(50);
$diag = server_diagnostic_snapshot();

if (($_GET['format'] ?? '') === 'json') {
    $safeDiag = $diag;
    if (isset($safeDiag['health']) && is_array($safeDiag['health'])) unset($safeDiag['health']['cron_url']);
    $safeFolders = array_map(static function(array $row): array {
        unset($row['folder_id']);
        return $row;
    }, $folders);
    json_response(['ok'=>true,'diagnostic'=>$safeDiag,'folders'=>$safeFolders,'history'=>$history]);
}

$quotaClass = ($health['quota_level'] ?? 'ok') === 'critical' ? 'error' : ((($health['quota_level'] ?? 'ok') === 'danger') ? 'error' : 'ok');
$percent = (float)($health['quota_percent'] ?? 0);
?>
<!doctype html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <meta name="robots" content="noindex,nofollow,noarchive">
  <title>Central do Servidor - EstradaPlay</title>
  <link rel="stylesheet" href="assets/css/app.css?v=1.0.0">
  <style>
    .server-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px}.server-metric{padding:16px}.server-metric strong{display:block;font-size:1.55rem;margin-top:5px}.server-bar{height:12px;background:#17202b;border-radius:999px;overflow:hidden;margin:10px 0}.server-bar span{display:block;height:100%;background:#ff6b2c}.server-actions{display:flex;gap:8px;flex-wrap:wrap}.server-table td,.server-table th{vertical-align:top}.server-code{display:block;word-break:break-all;white-space:normal;padding:10px;border-radius:8px;background:#0c121a}.server-ok{color:#45d483}.server-warn{color:#ffb45c}.server-error{color:#ff646b}.server-small{font-size:.85rem}.server-title-row{display:flex;gap:12px;justify-content:space-between;align-items:center;flex-wrap:wrap}
  </style>
</head>
<body data-role="admin">
<main class="admin-main">
  <div class="toolbar admin-toolbar">
    <div><p class="eyebrow">ESTRADAPLAY · SERVIDOR 500 MB V4</p><h1>Central do Servidor</h1><p class="muted">Diagnóstico, espaço, catálogo e sincronização do Google Drive público.</p></div>
    <div class="row"><a class="button secondary" href="admin_reports.php">Reportes da Estrada</a> <a class="button secondary" href="admin_collective.php">Inteligência Coletiva</a><a class="button secondary" href="admin.php">Painel Admin</a><a class="button secondary" href="logout.php">Sair</a></div>
  </div>

  <?php if ($message): ?><div class="panel success"><?= htmlspecialchars($message) ?></div><?php endif; ?>
  <?php if ($error): ?><div class="alert"><?= htmlspecialchars($error) ?></div><?php endif; ?>

  <section class="panel">
    <div class="server-title-row"><div><h2>Uso dos 500 MB</h2><p class="muted">Áudios não entram neste cálculo porque permanecem no Google Drive.</p></div><span class="status-badge <?= htmlspecialchars($quotaClass) ?>"><?= htmlspecialchars((string)$health['quota_label']) ?></span></div>
    <div class="server-grid">
      <article class="panel server-metric"><span class="muted">Uso gerenciado</span><strong><?= htmlspecialchars((string)$health['managed_h']) ?></strong><small><?= number_format($percent,1,',','.') ?>% de <?= htmlspecialchars((string)$health['quota_h']) ?></small></article>
      <article class="panel server-metric"><span class="muted">MariaDB</span><strong><?= htmlspecialchars((string)$health['db_h']) ?></strong></article>
      <article class="panel server-metric"><span class="muted">Arquivos do site</span><strong><?= htmlspecialchars((string)$health['files_h']) ?></strong></article>
      <article class="panel server-metric"><span class="muted">Logs</span><strong><?= htmlspecialchars((string)$health['logs_h']) ?></strong></article>
    </div>
    <div class="server-bar"><span style="width:<?= min(100,max(0,$percent)) ?>%"></span></div>
    <p class="muted">Alertas automáticos: <strong>350 MB</strong> atenção · <strong>400 MB</strong> alerta · <strong>450 MB</strong> crítico. No nível crítico, a retenção de auditoria é reduzida e logs antigos são eliminados primeiro.</p>
    <form method="post"><input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>"><input type="hidden" name="action" value="housekeeping"><button class="button secondary" type="submit">Executar limpeza agora</button></form>
  </section>

  <section class="panel">
    <div class="server-title-row"><div><h2>Catálogo</h2><p class="muted">A versão só muda quando uma música entra, muda ou é removida de verdade.</p></div><form method="post"><input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>"><input type="hidden" name="action" value="sync_all"><button type="submit">Sincronizar todas agora</button></form></div>
    <div class="server-grid">
      <article class="panel server-metric"><span class="muted">Músicas</span><strong><?= (int)$health['tracks'] ?></strong></article>
      <article class="panel server-metric"><span class="muted">Pastas ativas</span><strong><?= (int)$health['active_roots'] ?></strong></article>
      <article class="panel server-metric"><span class="muted">Versão do catálogo</span><strong>#<?= (int)$health['catalog_version'] ?></strong></article>
      <article class="panel server-metric"><span class="muted">Última sincronização</span><strong style="font-size:1rem"><?= htmlspecialchars((string)$health['last_sync']) ?></strong><small><?= (int)$health['last_sync_duration_ms'] ?> ms</small></article>
    </div>
  </section>

  <section class="panel">
    <h2>Status por pasta pública</h2>
    <p class="muted">Cada raiz pode ser sincronizada separadamente. Uma leitura com erro nunca apaga músicas antigas daquela raiz.</p>
    <div class="table-wrap"><table class="server-table"><thead><tr><th>Pasta</th><th>Estado</th><th>Última leitura</th><th>Mudanças</th><th>Duração</th><th>Ação</th></tr></thead><tbody>
      <?php if (!$folders): ?><tr><td colspan="6">Nenhuma pasta cadastrada.</td></tr><?php endif; ?>
      <?php foreach ($folders as $f): $hasErr=(int)($f['last_sync_errors']??0)>0 || str_starts_with((string)($f['last_status']??''),'Erro'); ?>
      <tr>
        <td><strong><?= htmlspecialchars((string)($f['name'] ?: 'Google Drive')) ?></strong><br><small><?= (int)$f['last_sync_tracks'] ?> músicas verificadas</small></td>
        <td><span class="<?= $hasErr?'server-error':'server-ok' ?>"><?= $hasErr?'AVISO/ERRO':'OK' ?></span><br><small><?= htmlspecialchars((string)($f['last_status'] ?: 'Ainda não sincronizada')) ?></small></td>
        <td><?= htmlspecialchars((string)($f['last_import_at'] ?: 'Nunca')) ?></td>
        <td>+<?= (int)($f['last_sync_inserted']??0) ?> novas<br><?= (int)($f['last_sync_updated']??0) ?> alteradas<br><?= (int)($f['last_sync_removed']??0) ?> removidas</td>
        <td><?= (int)($f['last_sync_duration_ms']??0) ?> ms</td>
        <td><?php if ((int)$f['active']===1): ?><form method="post"><input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>"><input type="hidden" name="action" value="sync_folder"><input type="hidden" name="folder_id" value="<?= (int)$f['id'] ?>"><button class="button secondary" type="submit">Sincronizar</button></form><?php else: ?><span class="muted">Pausada</span><?php endif; ?></td>
      </tr>
      <?php endforeach; ?>
    </tbody></table></div>
  </section>

  <section class="panel">
    <h2>Histórico de sincronizações</h2>
    <p class="muted">São mantidas no máximo 100 execuções recentes e até 60 dias.</p>
    <div class="table-wrap"><table class="server-table"><thead><tr><th>Quando</th><th>Origem</th><th>Status</th><th>Faixas</th><th>Mudanças</th><th>Duração</th><th>Catálogo</th></tr></thead><tbody>
      <?php if (!$history): ?><tr><td colspan="7">O histórico começará a ser preenchido na próxima sincronização.</td></tr><?php endif; ?>
      <?php foreach ($history as $h): ?>
      <tr><td><?= htmlspecialchars((string)$h['finished_at']) ?></td><td><?= htmlspecialchars((string)$h['source']) ?></td><td><span class="<?= $h['status']==='ok'?'server-ok':($h['status']==='warning'?'server-warn':'server-error') ?>"><?= htmlspecialchars(strtoupper((string)$h['status'])) ?></span><?php if (!empty($h['detail'])): ?><br><small><?= htmlspecialchars((string)$h['detail']) ?></small><?php endif; ?></td><td><?= (int)$h['tracks'] ?></td><td>+<?= (int)$h['inserted_count'] ?> / ~<?= (int)$h['updated_count'] ?> / -<?= (int)$h['removed_count'] ?></td><td><?= (int)$h['duration_ms'] ?> ms</td><td>#<?= (int)$h['catalog_version'] ?></td></tr>
      <?php endforeach; ?>
    </tbody></table></div>
  </section>

  <section class="panel">
    <div class="server-title-row"><div><h2>Diagnóstico permanente</h2><p class="muted">Protegido pela sessão de administrador. Não exibe senhas, tokens de dispositivo nem IDs do Drive.</p></div><a class="button secondary" href="admin_server.php?format=json" target="_blank" rel="noopener">Abrir JSON</a></div>
    <div class="server-grid">
      <article class="panel server-metric"><span class="muted">Servidor</span><strong><?= htmlspecialchars((string)$diag['status']) ?></strong></article>
      <article class="panel server-metric"><span class="muted">PHP</span><strong><?= htmlspecialchars((string)$diag['php']) ?></strong></article>
      <article class="panel server-metric"><span class="muted">Banco</span><strong><?= htmlspecialchars((string)$diag['db_driver']) ?></strong><small><?= htmlspecialchars((string)$diag['db_version']) ?></small></article>
      <article class="panel server-metric"><span class="muted">Pastas</span><strong><?= (int)$diag['folders']['ok'] ?> OK</strong><small><?= (int)$diag['folders']['warning'] ?> com aviso</small></article>
    </div>
  </section>

  <section class="panel">
    <h2>Cron automático</h2>
    <p class="muted">Use a cada 5 ou 10 minutos na hospedagem. O lock impede duas varreduras simultâneas e o throttle evita repetir uma leitura recente.</p>
    <code class="server-code"><?= htmlspecialchars((string)$health['cron_url']) ?></code>
    <form method="post" style="margin-top:12px" onsubmit="return confirm('Trocar a chave? A URL de cron atual deixará de funcionar.')"><input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>"><input type="hidden" name="action" value="rotate_cron_key"><button class="button secondary" type="submit">Trocar chave do cron</button></form>
  </section>
</main>
</body>
</html>
