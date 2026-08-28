<?php
require __DIR__ . '/api/bootstrap.php';
ensure_default_users();
$user = require_admin();
$message = '';
$error = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    require_csrf();
    $action = (string)($_POST['action'] ?? '');

    if ($action === 'create_client') {
        $name = trim((string)($_POST['name'] ?? ''));
        $email = trim((string)($_POST['email'] ?? ''));
        $username = trim((string)($_POST['username'] ?? ''));
        $password = (string)($_POST['password'] ?? '');
        if ($name === '' || $username === '' || strlen($password) < 6) {
            $error = 'Informe nome, usuário e uma senha com pelo menos 6 caracteres.';
        } else {
            if ($email === '') $email = $username . '@cliente.musicroad.local';
            try {
                $stmt = db()->prepare("INSERT INTO users (name,email,username,password_hash,role,status,created_at,updated_at) VALUES (?,?,?,?, 'client','active',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
                $stmt->execute([$name, $email, $username, password_hash($password, PASSWORD_DEFAULT)]);
                audit_log('admin.client_create', ['client_id'=>(int)db()->lastInsertId(),'username'=>$username]);
                $message = 'Cliente criado com sucesso.';
            } catch (Throwable $e) {
                $error = 'Não foi possível criar. Verifique se usuário/e-mail já existem.';
            }
        }
    }

    if ($action === 'toggle_client') {
        $id = (int)($_POST['client_id'] ?? 0);
        $stmt = db()->prepare("UPDATE users SET status = CASE WHEN status='active' THEN 'inactive' ELSE 'active' END, updated_at=CURRENT_TIMESTAMP WHERE id=? AND role='client'");
        $stmt->execute([$id]);
        audit_log('admin.client_toggle', ['client_id'=>$id]);
        $message = 'Status do cliente atualizado.';
    }

    if ($action === 'reset_client_password') {
        $id = (int)($_POST['client_id'] ?? 0);
        $password = (string)($_POST['new_password'] ?? '');
        if (strlen($password) < 6) {
            $error = 'A nova senha do cliente precisa ter pelo menos 6 caracteres.';
        } else {
            $stmt = db()->prepare("UPDATE users SET password_hash=?, updated_at=CURRENT_TIMESTAMP WHERE id=? AND role='client'");
            $stmt->execute([password_hash($password, PASSWORD_DEFAULT), $id]);
            audit_log('admin.client_password_reset', ['client_id'=>$id]);
            $message = 'Senha do cliente redefinida.';
        }
    }

    if ($action === 'delete_client') {
        $id = (int)($_POST['client_id'] ?? 0);
        db()->beginTransaction();
        try {
            $stmt = db()->prepare("DELETE FROM client_device_state WHERE user_id=?"); $stmt->execute([$id]);
            $stmt = db()->prepare("DELETE FROM user_music_state WHERE user_id=?"); $stmt->execute([$id]);
            $stmt = db()->prepare("DELETE FROM users WHERE id=? AND role='client'"); $stmt->execute([$id]);
            db()->commit();
            audit_log('admin.client_delete', ['client_id'=>$id]);
            $message = 'Cliente removido.';
        } catch (Throwable $e) {
            db()->rollBack();
            $error = 'Não foi possível remover o cliente.';
        }
    }

    if ($action === 'add_drive_folder') {
        $folderName = trim((string)($_POST['folder_name'] ?? '')) ?: 'Google Drive';
        $folderLink = trim((string)($_POST['folder_link'] ?? ''));
        $folderId = drive_folder_id_from_link($folderLink);
        if (!$folderId) {
            $error = 'Link de pasta do Google Drive inválido.';
        } else {
            $exists = db()->prepare('SELECT id FROM drive_folders WHERE folder_id=?');
            $exists->execute([$folderId]);
            if ($exists->fetchColumn()) {
                $stmt = db()->prepare('UPDATE drive_folders SET name=?,folder_link=?,active=1 WHERE folder_id=?');
                $stmt->execute([$folderName,$folderLink,$folderId]);
            } else {
                $stmt = db()->prepare("INSERT INTO drive_folders (name,folder_id,folder_link,active,created_at) VALUES (?,?,?,1,CURRENT_TIMESTAMP)");
                $stmt->execute([$folderName,$folderId,$folderLink]);
            }
            audit_log('admin.drive_folder_save', ['folder_id'=>$folderId]);
            $message = 'Pasta do Drive salva.';
        }
    }

    if ($action === 'toggle_drive_folder') {
        $id = (int)($_POST['folder_db_id'] ?? 0);
        $stmt = db()->prepare('UPDATE drive_folders SET active = CASE WHEN active=1 THEN 0 ELSE 1 END WHERE id=?');
        $stmt->execute([$id]);
        $message = 'Status da pasta atualizado.';
    }

    if ($action === 'delete_drive_folder') {
        $id = (int)($_POST['folder_db_id'] ?? 0);
        $stmt = db()->prepare('DELETE FROM drive_folders WHERE id=?');
        $stmt->execute([$id]);
        $message = 'Pasta removida da configuração.';
    }

    if ($action === 'delete_track') {
        $id = (int)($_POST['track_id'] ?? 0);
        $stmt = db()->prepare('DELETE FROM music_library WHERE id=?');
        $stmt->execute([$id]);
        $message = 'Música removida da biblioteca do servidor.';
    }

    if ($action === 'change_password') {
        $password = (string)($_POST['new_password'] ?? '');
        if (strlen($password) < 6) {
            $error = 'A nova senha precisa ter pelo menos 6 caracteres.';
        } else {
            $stmt = db()->prepare("UPDATE users SET password_hash=?,updated_at=CURRENT_TIMESTAMP WHERE id=?");
            $stmt->execute([password_hash($password,PASSWORD_DEFAULT),(int)$user['id']]);
            $message = 'Senha do administrador alterada.';
        }
    }
}

$clients = db()->query("SELECT u.*, (SELECT COUNT(*) FROM client_device_state d WHERE d.user_id=u.id) devices FROM users u WHERE u.role='client' ORDER BY u.id DESC")->fetchAll();
$deviceStates = db()->query("SELECT d.*,u.name user_name,u.username FROM client_device_state d JOIN users u ON u.id=d.user_id ORDER BY d.last_seen_at DESC LIMIT 100")->fetchAll();
$driveFolders = db()->query('SELECT * FROM drive_folders ORDER BY active DESC,id DESC')->fetchAll();
$tracks = db()->query("SELECT id,title,artist,album,origin,origin_ref,mime_type,file_size,created_at FROM music_library ORDER BY id DESC LIMIT 150")->fetchAll();
$logs = db()->query('SELECT action,payload,created_at FROM audit_logs ORDER BY id DESC LIMIT 30')->fetchAll();
$summary = [
    'Clientes' => (int)db()->query("SELECT COUNT(*) FROM users WHERE role='client'")->fetchColumn(),
    'Clientes ativos' => (int)db()->query("SELECT COUNT(*) FROM users WHERE role='client' AND status='active'")->fetchColumn(),
    'Músicas Drive' => (int)db()->query("SELECT COUNT(*) FROM music_library WHERE origin='Google Drive'")->fetchColumn(),
    'Radares' => (int)db()->query('SELECT COUNT(*) FROM radars')->fetchColumn(),
];
$health = server_health_snapshot();
?>
<!doctype html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
  <title>Admin - MusicRoad AI</title>
  <link rel="stylesheet" href="assets/css/app.css?v=1.0.0">
</head>
<body data-role="admin">
<main class="admin-main">
  <div class="toolbar admin-toolbar">
    <div><p class="eyebrow">MusicRoad AI v1.0.0</p><h1>Painel Administrativo</h1><p class="muted">Gerencie clientes, biblioteca do Drive e radares.</p></div>
    <div class="row"><a class="button secondary" href="admin_radares.php">Central de Radares</a><a class="button secondary" href="logout.php">Sair</a></div>
  </div>

  <?php if ($message): ?><div class="panel success"><?= htmlspecialchars($message) ?></div><?php endif; ?>
  <?php if ($error): ?><div class="alert"><?= htmlspecialchars($error) ?></div><?php endif; ?>

  <div class="grid admin-summary">
    <?php foreach ($summary as $label=>$value): ?><article class="panel"><h2><?= htmlspecialchars($label) ?></h2><p class="big-number"><?= (int)$value ?></p></article><?php endforeach; ?>
  </div>

  <section class="panel">
    <div class="section-heading"><div><h2>Saúde do servidor · modo 500 MB</h2><p class="muted">O servidor guarda somente metadados. Áudio permanece no Google Drive e vai direto para o aparelho.</p></div></div>
    <div class="grid admin-summary">
      <article class="panel"><h2>Espaço livre</h2><p class="big-number"><?= htmlspecialchars((string)$health['disk_free_h']) ?></p></article>
      <article class="panel"><h2>MariaDB</h2><p class="big-number"><?= htmlspecialchars((string)$health['db_h']) ?></p></article>
      <article class="panel"><h2>Logs</h2><p class="big-number"><?= htmlspecialchars((string)$health['logs_h']) ?></p></article>
      <article class="panel"><h2>Catálogo</h2><p class="big-number"><?= (int)$health['tracks'] ?></p></article>
    </div>
    <p class="muted"><strong>Última sincronização:</strong> <?= htmlspecialchars((string)$health['last_sync']) ?> · <strong>Pastas ativas:</strong> <?= (int)$health['active_roots'] ?></p>
    <details><summary>Sincronização automática</summary><p class="muted">Configure o cron da hospedagem para chamar esta URL a cada 5 ou 10 minutos. Chamadas extras não repetem a varredura se o catálogo ainda estiver recente.</p><code style="word-break:break-all"><?= htmlspecialchars((string)$health['cron_url']) ?></code></details>
  </section>

  <section class="panel">
    <div class="section-heading"><div><h2>Clientes</h2><p class="muted">O cliente entra no app e recebe a solicitação para autorizar pastas de música e localização.</p></div></div>
    <form method="post" class="form-grid client-create-form">
      <input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>"><input type="hidden" name="action" value="create_client">
      <label>Nome<input name="name" required placeholder="Nome do cliente"></label>
      <label>Usuário<input name="username" required placeholder="ex: joao"></label>
      <label>E-mail<input name="email" type="email" placeholder="opcional"></label>
      <label>Senha inicial<input name="password" type="password" minlength="6" required></label>
      <button type="submit">Criar cliente</button>
    </form>
    <div class="table-wrap">
      <table><thead><tr><th>Cliente</th><th>Usuário</th><th>Status</th><th>Último acesso</th><th>Dispositivos</th><th>Ações</th></tr></thead><tbody>
      <?php foreach ($clients as $client): ?>
        <tr>
          <td><strong><?= htmlspecialchars((string)$client['name']) ?></strong><br><small><?= htmlspecialchars((string)$client['email']) ?></small></td>
          <td><?= htmlspecialchars((string)$client['username']) ?></td>
          <td><span class="status-badge <?= $client['status']==='active'?'ok':'error' ?>"><?= $client['status']==='active'?'Ativo':'Desativado' ?></span></td>
          <td><?= htmlspecialchars((string)($client['last_login_at'] ?: 'Nunca')) ?></td>
          <td><?= (int)$client['devices'] ?></td>
          <td><div class="inline-actions">
            <form method="post"><input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>"><input type="hidden" name="action" value="toggle_client"><input type="hidden" name="client_id" value="<?= (int)$client['id'] ?>"><button class="button secondary" type="submit"><?= $client['status']==='active'?'Desativar':'Ativar' ?></button></form>
            <form method="post" class="reset-password-form"><input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>"><input type="hidden" name="action" value="reset_client_password"><input type="hidden" name="client_id" value="<?= (int)$client['id'] ?>"><input name="new_password" type="password" minlength="6" placeholder="Nova senha" required><button type="submit">Redefinir</button></form>
            <?php if ($client['username'] !== 'cliente'): ?><form method="post" onsubmit="return confirm('Remover este cliente?')"><input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>"><input type="hidden" name="action" value="delete_client"><input type="hidden" name="client_id" value="<?= (int)$client['id'] ?>"><button class="button danger-button" type="submit">Remover</button></form><?php endif; ?>
          </div></td>
        </tr>
      <?php endforeach; ?>
      </tbody></table>
    </div>
  </section>

  <section class="panel">
    <h2>Acessos dos clientes por dispositivo</h2>
    <p class="muted">Mostra o último estado informado pelo navegador. A permissão real continua sendo controlada pelo Android/iPhone/Windows e pelo navegador do cliente.</p>
    <div class="table-wrap"><table><thead><tr><th>Cliente</th><th>Dispositivo</th><th>Músicas</th><th>Localização</th><th>Pastas</th><th>Faixas</th><th>Última atividade</th></tr></thead><tbody>
    <?php if (!$deviceStates): ?><tr><td colspan="7">Nenhum cliente informou permissões ainda.</td></tr><?php endif; ?>
    <?php foreach ($deviceStates as $d): ?><tr>
      <td><?= htmlspecialchars((string)$d['user_name']) ?> <small>(<?= htmlspecialchars((string)$d['username']) ?>)</small></td>
      <td><?= htmlspecialchars((string)($d['device_name'] ?: substr((string)$d['device_id'],0,12))) ?></td>
      <td><?= htmlspecialchars((string)$d['music_permission']) ?></td>
      <td><?= htmlspecialchars((string)$d['location_permission']) ?></td>
      <td><?= (int)$d['music_folder_count'] ?></td><td><?= (int)$d['music_track_count'] ?></td><td><?= htmlspecialchars((string)$d['last_seen_at']) ?></td>
    </tr><?php endforeach; ?>
    </tbody></table></div>
  </section>

  <section class="panel">
    <div class="section-heading"><div><h2>Google Drive público</h2><p class="muted">Cadastre pastas públicas. O servidor salva apenas metadados/IDs; o arquivo permanece no Drive.</p></div><button id="adminImportAllDrive" type="button">Processar todas</button></div>
    <div id="adminImportStatus" class="device-status">Aguardando.</div>
    <form method="post" class="form-grid admin-settings">
      <input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>"><input type="hidden" name="action" value="add_drive_folder">
      <label>Nome<input name="folder_name" placeholder="Ex: Viagem"></label>
      <label>Link da pasta pública<input name="folder_link" required placeholder="https://drive.google.com/drive/folders/..."></label>
      <button type="submit">Adicionar pasta</button>
    </form>
    <div class="table-wrap"><table><thead><tr><th>Nome</th><th>ID</th><th>Status</th><th>Última importação</th><th>Ações</th></tr></thead><tbody>
      <?php foreach ($driveFolders as $folder): ?><tr>
        <td><?= htmlspecialchars((string)$folder['name']) ?></td><td><?= htmlspecialchars((string)$folder['folder_id']) ?></td>
        <td><?= $folder['active']?'Ativa':'Pausada' ?><br><small><?= htmlspecialchars((string)$folder['last_status']) ?></small></td>
        <td><?= htmlspecialchars((string)$folder['last_import_at']) ?></td>
        <td><div class="inline-actions"><button type="button" class="import-drive-folder" data-folder-id="<?= (int)$folder['id'] ?>">Importar</button>
          <form method="post"><input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>"><input type="hidden" name="action" value="toggle_drive_folder"><input type="hidden" name="folder_db_id" value="<?= (int)$folder['id'] ?>"><button class="button secondary" type="submit"><?= $folder['active']?'Pausar':'Ativar' ?></button></form>
          <form method="post"><input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>"><input type="hidden" name="action" value="delete_drive_folder"><input type="hidden" name="folder_db_id" value="<?= (int)$folder['id'] ?>"><button class="button secondary" type="submit">Remover</button></form>
        </div></td>
      </tr><?php endforeach; ?>
    </tbody></table></div>
  </section>

  <section class="panel">
    <h2>Biblioteca no servidor</h2>
    <div class="table-wrap"><table><thead><tr><th>ID</th><th>Título</th><th>Artista/Pasta</th><th>Origem</th><th>Tamanho</th><th>Ações</th></tr></thead><tbody>
      <?php foreach ($tracks as $track): ?><tr><td><?= (int)$track['id'] ?></td><td><?= htmlspecialchars((string)$track['title']) ?></td><td><?= htmlspecialchars((string)$track['artist']) ?></td><td><?= htmlspecialchars((string)$track['origin']) ?></td><td><?= $track['file_size'] ? number_format(((int)$track['file_size'])/1048576,1,',','.') . ' MB' : '-' ?></td><td><div class="inline-actions">
        <?php if ((string)$track['origin']==='Google Drive' && !empty($track['origin_ref'])): ?><a class="button secondary" target="_blank" rel="noopener" href="api/drive_stream.php?id=<?= rawurlencode((string)$track['origin_ref']) ?>">Testar áudio</a><?php endif; ?>
        <form method="post"><input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>"><input type="hidden" name="action" value="delete_track"><input type="hidden" name="track_id" value="<?= (int)$track['id'] ?>"><button type="submit" class="button secondary">Remover</button></form>
      </div></td></tr><?php endforeach; ?>
    </tbody></table></div>
  </section>

  <section class="panel"><h2>Segurança do ADM</h2><form method="post" class="form-grid"><input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>"><input type="hidden" name="action" value="change_password"><label>Nova senha<input name="new_password" type="password" minlength="6" required></label><button type="submit">Alterar senha</button></form></section>

  <section class="panel"><h2>Últimos logs</h2><div class="table-wrap"><table><thead><tr><th>Data</th><th>Ação</th><th>Resumo</th></tr></thead><tbody><?php foreach ($logs as $log): ?><tr><td><?= htmlspecialchars((string)$log['created_at']) ?></td><td><?= htmlspecialchars((string)$log['action']) ?></td><td><?= htmlspecialchars(mb_substr((string)$log['payload'],0,160)) ?></td></tr><?php endforeach; ?></tbody></table></div></section>
</main>
<script>
window.adminCsrf = <?= json_encode(csrf_token()) ?>;
async function importDrive(payload){
 const status=document.getElementById('adminImportStatus'); status.textContent='Processando pasta e subpastas...';
 const res=await fetch('api/google_drive_public.php',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':window.adminCsrf},body:JSON.stringify(payload)}).then(r=>r.json()).catch(e=>({ok:false,error:e.message}));
 if(!res.ok){status.textContent=res.error||'Falha na importação.';return;}
 const s=res.stats||{};status.textContent=`Concluído: ${s.tracks_found||0} músicas; ${s.inserted||0} novas; ${s.updated||0} atualizadas; ${s.folders_scanned||0} pastas.`;setTimeout(()=>location.reload(),1000);
}
document.getElementById('adminImportAllDrive')?.addEventListener('click',()=>importDrive({import_all:true}));
document.querySelectorAll('.import-drive-folder').forEach(b=>b.addEventListener('click',()=>importDrive({folder_db_id:Number(b.dataset.folderId)})));
</script>
</body></html>
