<?php
require __DIR__ . '/api/bootstrap.php';
ensure_default_users();
$user = require_client();
$message = '';
$error = '';
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    require_csrf();
    if (($_POST['action'] ?? '') === 'change_password') {
        $current = (string)($_POST['current_password'] ?? '');
        $new = (string)($_POST['new_password'] ?? '');
        $stmt = db()->prepare('SELECT password_hash FROM users WHERE id = ?');
        $stmt->execute([(int)$user['id']]);
        $hash = (string)$stmt->fetchColumn();
        if (!password_verify($current, $hash)) {
            $error = 'Senha atual incorreta.';
        } elseif (strlen($new) < 6) {
            $error = 'A nova senha precisa ter pelo menos 6 caracteres.';
        } else {
            $up = db()->prepare("UPDATE users SET password_hash = ?, updated_at = datetime('now') WHERE id = ?");
            $up->execute([password_hash($new, PASSWORD_DEFAULT), (int)$user['id']]);
            audit_log('client.password_change', ['user_id'=>(int)$user['id']]);
            $message = 'Senha alterada com sucesso.';
        }
    }
}
?>
<!doctype html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
  <meta name="theme-color" content="#08111f">
  <title>MusicRoad AI</title>
  <link rel="manifest" href="manifest.webmanifest?v=1.0.0">
  <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
  <link rel="stylesheet" href="assets/css/app.css?v=1.0.0">
</head>
<body data-role="client">
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">MR</span><strong>MusicRoad AI</strong></div>
      <div class="client-profile"><strong><?= htmlspecialchars($user['name']) ?></strong><span>Cliente</span></div>
      <nav>
        <button class="nav active" data-screen="home">Início</button>
        <button class="nav" data-screen="music">Música</button>
        <button class="nav" data-screen="ai">Smart Mix</button>
        <button class="nav" data-screen="trip">Viagem</button>
        <button class="nav" data-screen="permissions">Acessos</button>
        <button class="nav" data-screen="settings">Configurações</button>
        <a class="nav link-nav" href="logout.php">Sair</a>
      </nav>
    </aside>

    <main>
      <?php if ($message): ?><div class="panel success flash-message"><?= htmlspecialchars($message) ?></div><?php endif; ?>
      <?php if ($error): ?><div class="alert flash-message"><?= htmlspecialchars($error) ?></div><?php endif; ?>

      <section id="home" class="screen active">
        <div class="hero">
          <div>
            <p class="eyebrow">Biblioteca pessoal + viagem</p>
            <h1>Olá, <?= htmlspecialchars($user['name']) ?>.</h1>
            <p>O MusicRoad pede acesso às suas <strong>pastas de música</strong> e à <strong>localização</strong>. Os arquivos do dispositivo não são enviados ao servidor.</p>
            <div class="row hero-actions">
              <button data-screen-jump="permissions">Revisar acessos</button>
              <button class="button secondary" data-screen-jump="music">Abrir músicas</button>
            </div>
          </div>
          <div class="speed-card">
            <span>Próximo radar</span>
            <strong id="homeRadar">--</strong>
            <small>Ative a localização para alertas durante a viagem.</small>
          </div>
        </div>
        <div class="grid">
          <article class="panel"><h2>Biblioteca</h2><p id="mixCount">0 músicas</p><button data-screen-jump="music">Abrir</button></article>
          <article class="panel"><h2>Smart Mix</h2><p>Crie mix com as músicas disponíveis neste aparelho e no Drive.</p><button data-screen-jump="ai">Criar mix</button></article>
          <article class="panel"><h2>Acessos</h2><p id="homePermissionSummary">Verificando permissões...</p><button data-screen-jump="permissions">Configurar</button></article>
        </div>
      </section>

      <section id="music" class="screen">
        <div class="toolbar">
          <div>
            <h1>Minha Biblioteca</h1>
            <p class="muted">Você não adiciona música uma por uma. Autorize uma pasta e o sistema encontra automaticamente os áudios nela e nas subpastas.</p>
          </div>
          <div class="device-actions">
            <button id="scanDeviceMusic" type="button">Autorizar pasta de músicas</button>
            <button id="syncDeviceMusic" class="button secondary" type="button">Sincronizar pastas</button>
            <input id="folderInput" class="hidden-folder-input" type="file" webkitdirectory directory multiple accept="audio/*">
          </div>
        </div>
        <div id="deviceStatus" class="device-status">Verificando pastas autorizadas...</div>
        <div id="folderList" class="folder-list"></div>
        <div class="tabs">
          <button class="active" data-filter="all">Todas</button>
          <button data-filter="favorite">Favoritas</button>
          <button data-filter="device">No dispositivo</button>
          <button data-filter="drive">Google Drive</button>
          <button data-filter="recent">Recentes</button>
        </div>
        <div id="tracks" class="track-list"></div>
      </section>

      <section id="ai" class="screen">
        <h1>Smart Mix</h1>
        <p class="muted">Funciona localmente com a sua biblioteca disponível. Não depende de chave de IA para montar mixes.</p>
        <div class="ai-box">
          <input id="aiPrompt" placeholder="Ex: Filipe Ret por 1 hora, favoritas, esquecidas...">
          <button id="askAi">Montar mix</button>
        </div>
        <div class="chips">
          <button data-prompt="Monte um mix variado para uma hora">1 hora</button>
          <button data-prompt="Monte uma playlist de três horas">3 horas</button>
          <button data-prompt="Minhas favoritas">Favoritas</button>
          <button data-prompt="Encontre músicas repetidas">Repetidas</button>
          <button data-prompt="Encontre músicas que não escuto há muito tempo">Esquecidas</button>
        </div>
        <div id="aiSummary" class="ai-summary">Escolha um atalho ou escreva seu pedido.</div>
        <div id="aiResult" class="track-list"></div>
      </section>

      <section id="trip" class="screen">
        <div class="trip-layout">
          <div class="trip-panel">
            <h1>Viagem</h1>
            <p class="muted">A localização é usada apenas para rota, velocidade e alertas de proximidade.</p>
            <input id="origin" placeholder="Origem ou use sua localização">
            <input id="destination" placeholder="Destino. Ex: Vitória ES">
            <div class="row"><button id="useLocation">Usar minha localização</button><button id="calcRoute">Calcular rota</button></div>
            <div id="gpsStatus" class="status-badge">GPS parado</div>
            <div class="dashboard">
              <div><span>Velocidade</span><strong id="speedNow">-- km/h</strong></div>
              <div><span>Limite</span><strong id="speedLimit">--</strong></div>
              <div><span>Radar</span><strong id="nextRadar">--</strong></div>
            </div>
            <div id="routeInfo" class="muted">Autorize a localização para usar a viagem.</div>
          </div>
          <div id="map"></div>
        </div>
      </section>

      <section id="permissions" class="screen">
        <h1>Central de Acessos</h1>
        <p class="muted">O navegador exige que você confirme cada tipo de acesso. O sistema nunca consegue liberar isso sozinho.</p>
        <div class="permissions-grid">
          <article class="permission-card">
            <div class="permission-icon">🎵</div>
            <div><h2>Pastas de música</h2><p>Autorize uma ou mais pastas. O sistema lê MP3, M4A, AAC, OGG, OPUS, FLAC e WAV e também procura nas subpastas.</p><strong id="musicPermissionLabel">Verificando...</strong></div>
            <button id="permissionMusicButton">Autorizar pasta</button>
          </article>
          <article class="permission-card">
            <div class="permission-icon">📍</div>
            <div><h2>Localização</h2><p>Necessária para GPS, rota e alertas de radar. O site precisa estar em HTTPS.</p><strong id="locationPermissionLabel">Verificando...</strong></div>
            <button id="permissionLocationButton">Permitir localização</button>
          </article>
        </div>
        <div class="panel"><h2>Privacidade</h2><p>As músicas selecionadas ficam no próprio navegador/dispositivo. No modo compatível, o navegador pode criar uma cópia local no armazenamento do PWA para continuar reproduzindo. Nenhum arquivo local é enviado ao servidor.</p></div>
      </section>

      <section id="settings" class="screen">
        <h1>Configurações</h1>
        <div class="settings-grid">
          <article class="panel"><h2>Instalar PWA</h2><p>Instale para abrir o MusicRoad como aplicativo.</p><button id="installApp" type="button">Instalar MusicRoad AI</button><small id="installStatus" class="muted"></small></article>
          <article class="panel"><h2>Alertas de radar</h2><label class="check"><input id="voiceAlerts" type="checkbox" checked> Avisos por voz</label><label>Volume<select id="alertVolume"><option value="0.45">Baixo</option><option value="0.75">Normal</option><option value="1">Alto</option></select></label></article>
          <article class="panel"><h2>Dados locais</h2><p>Remove apenas vínculos, índices e cópias locais deste usuário neste aparelho.</p><button id="clearOffline" class="button secondary" type="button">Desconectar todas as pastas</button></article>
          <article class="panel"><h2>Senha</h2><form method="post" class="form-grid"><input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>"><input type="hidden" name="action" value="change_password"><label>Senha atual<input type="password" name="current_password" required></label><label>Nova senha<input type="password" name="new_password" minlength="6" required></label><button type="submit">Alterar senha</button></form></article>
          <article class="panel diagnostics-panel"><h2>Diagnóstico</h2><p id="diagnostics" class="muted">Verificando...</p><small class="muted">MusicRoad AI v1.0.0</small></article>
        </div>
      </section>
    </main>
  </div>

  <footer class="mini-player">
    <img id="cover" alt="" src="assets/img-icon.svg">
    <div class="now"><strong id="nowTitle">Nada tocando</strong><span id="nowArtist">Autorize uma pasta de músicas</span><input id="progress" type="range" min="0" max="100" value="0"></div>
    <div class="controls"><button id="prev">⏮</button><button id="play">▶</button><button id="next">⏭</button><button id="shuffle">Aleatório</button></div>
    <audio id="audio" preload="metadata"></audio>
  </footer>

  <div id="permissionOnboarding" class="permission-onboarding" hidden>
    <div class="permission-modal">
      <div class="brand"><span class="brand-mark">MR</span><strong>Primeiro acesso</strong></div>
      <h1>Permita o que o MusicRoad precisa</h1>
      <p>Para o perfil Cliente funcionar, o aplicativo precisa pedir acesso às suas pastas de música e à sua localização.</p>
      <div class="onboarding-permissions">
        <button id="onboardingMusic" class="permission-step"><span>🎵</span><div><strong>1. Pastas de músicas</strong><small id="onboardingMusicState">Toque para autorizar</small></div></button>
        <button id="onboardingLocation" class="permission-step"><span>📍</span><div><strong>2. Localização</strong><small id="onboardingLocationState">Toque para permitir</small></div></button>
      </div>
      <button id="finishOnboarding" class="wide-button" disabled>Continuar para o MusicRoad</button>
      <button id="skipOnboarding" class="text-button">Continuar sem concluir agora</button>
      <small class="muted">Você poderá liberar os acessos depois em “Acessos”.</small>
    </div>
  </div>

  <script>
    window.MR_USER = <?= json_encode(['id'=>(int)$user['id'],'name'=>$user['name'],'role'=>$user['role']], JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES) ?>;
    window.MR_CSRF = <?= json_encode(csrf_token()) ?>;
  </script>
  <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
  <script src="assets/js/db.js?v=1.0.0"></script>
  <script src="assets/js/player.js?v=1.0.0"></script>
  <script src="assets/js/maps.js?v=1.0.0"></script>
  <script src="assets/js/smart.js?v=1.0.0"></script>
  <script src="assets/js/app.js?v=1.0.0"></script>
</body>
</html>
