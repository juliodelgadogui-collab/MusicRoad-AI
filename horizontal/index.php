<?php
header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
header('Pragma: no-cache');
require dirname(__DIR__) . '/api/bootstrap.php';
$user = current_user();
if (!$user) { header('Location: ../login.php'); exit; }
if (($user['role'] ?? '') !== 'admin' && !user_has_access($user)) { header('Location: ../license.php'); exit; }
$csrf = csrf_token();
$isAdmin = (($user['role'] ?? '') === 'admin');
$mapbox = mapbox_client_config();
?>
<!doctype html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover,maximum-scale=1,user-scalable=no">
  <meta name="theme-color" content="#05070a">
  <meta name="mobile-web-app-capable" content="yes">
  <title>MusicRoad Drive · Central Multimídia</title>
  <link rel="stylesheet" href="../assets/vendor/leaflet/leaflet.css?v=1.9.4">
  <?php if($mapbox['enabled']): ?><link rel="stylesheet" href="https://api.mapbox.com/mapbox-gl-js/v3.26.0/mapbox-gl.css"><?php endif; ?>
  <link rel="stylesheet" href="assets/auto.css?v=1.2.0">
  <link rel="stylesheet" href="assets/driveos.css?v=1.2.0">
  <link rel="stylesheet" href="assets/premium-driveos.css?v=1.2.0">
<style id="mr-driveos-inline-css"><?php
  foreach ([__DIR__.'/assets/auto.css', __DIR__.'/assets/driveos.css', __DIR__.'/assets/premium-driveos.css'] as $__css) { if (is_file($__css)) echo "\n" . file_get_contents($__css); }
?></style>
  <style id="mr-critical-fallback">html,body{margin:0;min-height:100%;background:#05070a;color:#f5f8fc;font-family:Inter,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}button,input,select,a{font:inherit}button{color:inherit;background:#101923;border:1px solid #273544;border-radius:12px}input,select{background:#09121c;color:#f5f8fc;border:1px solid #273544;border-radius:12px}.auto-screen{display:none}.auto-screen.active{display:block}.drive-screen.active{display:grid}.map-stage{min-height:1px}.auto-app{min-height:100vh}.rotate-lock{display:none}.boot-screen{display:none}.app-tile-form{margin:0}.app-tile-form .app-tile{width:100%}.settings-card form{margin:0}.settings-card form .settings-action{width:100%}</style>
</head>
<body>
  <div id="rotate" class="rotate-lock">
    <div class="rotate-icon">↻</div>
    <strong>MusicRoad Auto</strong>
    <span>Gire a central para a posição horizontal.</span>
  </div>

  <div id="boot" class="boot-screen">
    <div class="boot-mark">MR</div>
    <strong>MusicRoad <em>DRIVE OS</em></strong>
    <span>CENTRAL MULTIMÍDIA AUTOMOTIVA</span>
    <div class="boot-line"><i></i></div>
  </div>

  <div id="autoApp" class="auto-app">
    <header class="auto-topbar">
      <button id="brandHome" class="brand" type="button" aria-label="Início">
        <span class="brand-mark"><img src="../assets/mr-one.svg" alt=""></span>
        <span class="brand-copy"><b>MusicRoad</b><small>DRIVE OS</small></span>
      </button>
      <div id="topGuidance" class="top-guidance"><span class="guidance-arrow">↑</span><div><small id="guidanceDistance">PRONTO</small><b id="guidanceText">Central multimídia pronta</b></div></div>
      <div class="top-status">
        <span class="cockpit-pill">COCKPIT</span><span id="netBadge" class="status-chip online">ONLINE</span>
        <span id="gpsBadge" class="status-chip">GPS --</span>
        <button id="fullscreenBtn" class="top-icon" type="button" aria-label="Tela cheia" title="Tela cheia">⛶</button>
        <button class="top-icon" data-screen="settings" type="button" aria-label="Ajustes" title="Ajustes">⚙</button>
        <time id="clock">--:--</time>
      </div>
    </header>

    <aside class="auto-dock" aria-label="Menu principal">
      <button class="dock-btn active" data-drive-mode="home" type="button"><span>⌂</span><small>HOME</small></button>
      <button class="dock-btn" data-drive-mode="nav" type="button"><span>➤</span><small>NAV</small></button>
      <button class="dock-btn" data-screen="music" type="button"><span>♫</span><small>MEDIA</small></button>
      <button class="dock-btn" data-screen="alerts" type="button"><span>!</span><small>ASSIST</small></button>
      <button class="dock-btn" data-screen="apps" type="button"><span>▦</span><small>APPS</small></button>
    </aside>

    <main class="auto-main">
      <section id="screenDrive" class="auto-screen drive-screen active" data-screen-id="drive">
        <div class="map-stage">
          <div id="map"></div>

          <div id="navCue" class="nav-cue hidden">
            <span id="navCueArrow" class="nav-cue-arrow">↑</span>
            <div><strong id="navCueDistance">--</strong><small id="navCueText">Siga em frente</small></div>
          </div>

          <div id="roadAlert" class="road-alert" aria-live="assertive" aria-atomic="true">
            <span id="roadAlertIcon" class="road-alert-icon">!</span>
            <div><small id="roadAlertType">DRIVE ASSIST</small><strong id="roadAlertTitle">Atenção</strong><span id="roadAlertText"></span></div>
            <b id="roadAlertDistance"></b>
          </div>

          <div class="speed-cluster" id="speedCluster">
            <small>km/h</small>
            <strong id="speedNow">0</strong>
            <div class="speed-limit"><span>LIMITE</span><b id="speedLimit">--</b></div>
          </div>

          <div class="map-actions">
            <button id="recenterMap" type="button" title="Seguir veículo">◎</button>
            <button id="fitRoute" type="button" title="Ver rota">◇</button>
            <button id="reportPointQuick" type="button" class="report" title="Registrar ponto">＋</button>
          </div>

          <div id="routeProgressCard" class="route-progress-card hidden">
            <div><small>DESTINO</small><b id="routeDestination">--</b></div>
            <div class="route-metrics"><span><b id="routeDistance">--</b><small>restante</small></span><span><b id="routeEta">--</b><small>chegada</small></span></div>
          </div>
        </div>

        <aside id="drivePanel" class="drive-panel home-mode">
          <div class="drive-panel-head">
            <div><small id="driveModeKicker">COCKPIT PRONTO</small><h1 id="driveModeTitle"><?= htmlspecialchars((string)($user['name'] ?? 'Motorista')) ?></h1></div>
            <span class="vehicle-dot"><i></i> DRIVE OS</span>
          </div>

          <section id="homeCards" class="home-cards">
            <article class="auto-card destination-card">
              <div class="card-label"><span>➤</span><small>DESTINO RÁPIDO</small></div>
              <h2>Para onde vamos?</h2>
              <div class="address-box"><input id="destination" autocomplete="off" placeholder="Rua, número, cidade ou local"><button id="go" type="button">IR</button></div>
              <div id="suggestions" class="suggestions"></div>
              <div class="favorite-row"><button data-favorite="home" type="button">⌂ Casa</button><button data-favorite="work" type="button">▣ Trabalho</button><button id="openNav" type="button">Mais opções ›</button></div>
            </article>

            <article class="auto-card now-card">
              <div id="miniCover" class="cover">♫</div>
              <div class="now-copy"><small>TOCANDO AGORA</small><strong id="boardNowTitle">Nenhuma música</strong><span id="boardNowArtist">Abra Música para escolher</span></div>
              <button class="round-play" data-player="toggle" type="button">▶</button>
            </article>

            <article class="auto-card assist-card">
              <div class="card-label"><span>!</span><small>DRIVE ASSIST</small></div>
              <div class="assist-main"><div><strong id="nextAlertTitle">Via livre</strong><span id="nextAlertText">Nenhum alerta à frente</span></div><b id="nextAlertDistance">--</b></div>
              <div class="assist-stats"><span><b id="radarCountHome">0</b> radares</span><span><b id="bumpCountHome">0</b> quebra-molas</span><span id="sequenceBadge">sequência pronta</span></div>
            </article>
          </section>

          <section id="navPanel" class="nav-panel">
            <article class="auto-card nav-search-card">
              <div class="card-label"><span>➤</span><small>MUSICROAD NAV</small></div>
              <h2>Escolha o destino</h2>
              <div class="address-box"><input id="navDestination" autocomplete="off" placeholder="Endereço, cidade ou coordenada"><button id="navGo" type="button">IR</button></div>
              <div id="navSuggestions" class="suggestions"></div>
              <div class="nav-favorites"><button data-favorite="home" type="button"><span>⌂</span><div><b>Casa</b><small id="homeFavoriteLabel">Definir endereço</small></div></button><button data-favorite="work" type="button"><span>▣</span><div><b>Trabalho</b><small id="workFavoriteLabel">Definir endereço</small></div></button></div>
            </article>

            <article class="auto-card trip-card">
              <div class="trip-state"><div><small>VIAGEM</small><strong id="tripState">Sem rota ativa</strong></div><span id="coverageState">GPS</span></div>
              <div class="trip-grid"><div><small>DISTÂNCIA</small><b id="distance">--</b></div><div><small>TEMPO</small><b id="duration">--</b></div><div><small>FISCALIZAÇÃO</small><b id="routeHazardCount">0</b></div></div>
              <button id="stopTrip" class="danger-btn" type="button" disabled>ENCERRAR NAVEGAÇÃO</button>
            </article>
          </section>
        </aside>
      </section>

      <section id="screenMusic" class="auto-screen page-screen" data-screen-id="music">
        <div class="page-head"><div><small>ENTRETENIMENTO</small><h1>Música</h1><p id="musicStatus">Biblioteca do MusicRoad</p></div><div class="page-actions"><div class="search-mini"><span>⌕</span><input id="musicSearch" placeholder="Buscar música, artista ou álbum"></div><button id="reloadMusic" type="button">↻ Atualizar</button></div></div>
        <div class="music-layout">
          <article class="music-now auto-card">
            <div id="bigCover" class="big-cover">♫</div>
            <small>TOCANDO AGORA</small>
            <h2 id="nowTitle">Nenhuma música</h2>
            <p id="nowArtist">Selecione uma faixa</p>
            <input id="playerProgress" type="range" min="0" max="100" value="0">
            <div class="large-controls"><button data-player="prev" type="button">⏮</button><button class="primary-play" data-player="toggle" type="button">▶</button><button data-player="next" type="button">⏭</button></div>
          </article>
          <div class="library-pane"><div class="library-head"><strong>Sua biblioteca</strong><span id="trackCount">0 faixas</span></div><div id="tracks" class="track-list"></div></div>
        </div>
      </section>

      <section id="screenAlerts" class="auto-screen page-screen" data-screen-id="alerts">
        <div class="page-head"><div><small>COPILOTO</small><h1>Drive Assist</h1><p>Alertas da rota e colaboração do motorista</p></div><div class="page-actions"><button id="reportPoint" class="primary-action" type="button">＋ REGISTRAR PONTO</button></div></div>
        <div class="alert-summary">
          <article class="summary-card"><span class="summary-icon radar">60</span><div><small>RADARES / VELOCIDADE</small><strong id="radarCount">0</strong><p>pontos carregados</p></div></article>
          <article class="summary-card"><span class="summary-icon bump">⌁</span><div><small>QUEBRA-MOLAS</small><strong id="bumpCount">0</strong><p>na rota / proximidade</p></div></article>
          <article class="summary-card"><span class="summary-icon voice">◖</span><div><small>ALERTA POR VOZ</small><strong id="voiceState">ATIVO</strong><p>copiloto em português</p></div></article>
          <article class="summary-card"><span class="summary-icon sequence">≋</span><div><small>MODO SEQUÊNCIA</small><strong>ATIVO</strong><p>agrupa lombadas próximas</p></div></article>
        </div>
        <div class="alerts-layout"><article class="auto-card alerts-list-card"><div class="list-head"><strong>Pontos carregados</strong><span id="alertListHint">mais próximos primeiro</span></div><div id="alertList" class="hazard-list"></div></article><article class="auto-card alert-config-card"><small>AVISOS</small><h2>Como o Auto avisa</h2><label class="switch-row"><input id="prefVoice" type="checkbox" checked><span><b>Falar alertas</b><small>radar, quebra-mola e manobras</small></span></label><label class="switch-row"><input id="prefSpeeding" type="checkbox" checked><span><b>Excesso de velocidade</b><small>quando o limite for conhecido</small></span></label><label class="switch-row"><input id="prefManeuvers" type="checkbox" checked><span><b>Orientação da rota</b><small>direita, esquerda, retorno</small></span></label><div class="distance-pills"><label><input id="pref300" type="checkbox" checked><span>300 m</span></label><label><input id="pref200" type="checkbox" checked><span>200 m</span></label><label><input id="pref100" type="checkbox" checked><span>100 m</span></label><label><input id="pref50" type="checkbox" checked><span>50 m</span></label></div></article></div>
      </section>


      <section id="screenRadio" class="auto-screen page-screen" data-screen-id="radio">
        <div class="page-head"><div><small>ENTRETENIMENTO</small><h1>Rádio</h1><p>FM do aparelho e estações online</p></div><div class="page-actions"><button id="openFmRadio" class="primary-action" type="button">FM DO APARELHO</button></div></div>
        <div class="radio-layout">
          <article class="auto-card radio-fm-card"><div class="radio-wave">FM<span>)))</span></div><div><small>RÁDIO FÍSICO</small><h2>Rádio FM</h2><p id="fmRadioStatus">Verificando disponibilidade no aparelho…</p></div><button id="openFmRadioCard" class="settings-action" type="button">ABRIR FM</button></article>
          <article class="auto-card radio-online-card"><div><small>RÁDIO ONLINE</small><h2>Minhas estações</h2><p id="radioStatus">Adicione uma URL direta de stream de áudio.</p></div><form id="radioStationForm" class="radio-form"><input id="radioName" required placeholder="Nome da rádio"><input id="radioFrequency" placeholder="Frequência (ex.: 98.7 FM)"><input id="radioUrl" type="url" required placeholder="https://... stream de áudio"><button type="submit">SALVAR</button></form></article>
        </div>
        <div id="radioStations" class="radio-stations"></div>
      </section>

      <section id="screenApps" class="auto-screen page-screen" data-screen-id="apps">
        <div class="page-head"><div><small>LAUNCHER AUTOMOTIVO</small><h1>Central multimídia</h1><p>Funções essenciais com botões grandes para uso no painel do carro</p></div></div>
        <div class="app-grid">
          <button class="app-tile" data-drive-mode="nav" type="button"><span>➤</span><b>Navegação</b><small>Mapa, rotas e instruções</small></button>
          <button class="app-tile" data-screen="music" type="button"><span>♫</span><b>Música</b><small>Biblioteca e player</small></button>
          <button class="app-tile" data-screen="alerts" type="button"><span>!</span><b>Drive Assist</b><small>Radares e quebra-molas</small></button>
          <button class="app-tile" data-screen="radio" type="button"><span>FM</span><b>Rádio</b><small>FM e estações online</small></button>
          <button class="app-tile" data-screen="offline" type="button"><span>⇩</span><b>Mapas offline</b><small>Baixar estado no aparelho</small></button>
          <button class="app-tile" id="appFullscreen" type="button"><span>⛶</span><b>Tela cheia</b><small>Modo central automotiva</small></button>
          <a class="app-tile" href="../index.php?standard=1"><span>MR</span><b>Modo padrão</b><small>Abrir interface vertical/clássica</small></a>
          <?php if ($isAdmin): ?><a class="app-tile" href="../admin.php"><span>⚙</span><b>Administração</b><small>Painel do servidor</small></a><?php endif; ?>
          <button class="app-tile" data-screen="settings" type="button"><span>⚙</span><b>Ajustes</b><small>Voz, tela e sistema</small></button>
          <form id="driveLogoutForm" class="app-tile-form" method="post" action="../logout.php"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token(),ENT_QUOTES)?>"><button class="app-tile danger-tile" type="submit"><span>↪</span><b>Sair</b><small>Encerrar esta sessão</small></button></form>
        </div>
      </section>

      <section id="screenOffline" class="auto-screen page-screen" data-screen-id="offline">
        <div class="page-head"><div><small>USO SEM INTERNET</small><h1>Mapas offline</h1><p>Baixe um estado e mantenha as vias no próprio dispositivo</p></div></div>
        <div class="settings-grid">
          <article class="settings-card auto-card offline-device-card"><div><small>ESTADO</small><h2>Mapa no aparelho</h2><p>Baixa uma base estadual leve com as principais rodovias. Detalhes urbanos podem ser preparados sob demanda na interface vertical, sem disparar centenas de downloads.</p></div><div class="settings-stack"><label class="select-label">Estado<select id="autoOfflineState"><option value="">Selecione o estado</option></select></label><button id="autoDownloadMap" class="settings-action primary-action" type="button" disabled>BAIXAR MAPA DO ESTADO</button><button id="autoDownloadRadars" class="settings-action" type="button" disabled>BAIXAR RADARES</button></div></article>
          <article class="settings-card auto-card"><div><small>ARMAZENAMENTO</small><h2>Dispositivo</h2><p id="autoOfflineProgress">Selecione um estado para começar.</p></div><span class="version-badge">DEVICE</span></article>
          <article class="settings-card auto-card"><div><small>IMPORTANTE</small><h2>Pacote eficiente</h2><p>O mapa-base contém as principais rodovias do estado. O detalhe urbano é opcional e baixado apenas para a cidade escolhida no modo vertical.</p></div></article>
        </div>
      </section>

      <section id="screenSettings" class="auto-screen page-screen" data-screen-id="settings">
        <div class="page-head"><div><small>DRIVE OS</small><h1>Configurações da central</h1><p>Tela, voz, atalhos e comportamento automotivo</p></div></div>
        <div class="settings-grid">
          <article class="settings-card auto-card"><div><small>TELA</small><h2>Modo automotivo</h2><p>Use tela cheia e, quando suportado, bloqueio em landscape.</p></div><button id="settingsFullscreen" class="settings-action" type="button">ATIVAR TELA CHEIA</button></article>
          <article class="settings-card auto-card"><div><small>DISPLAY</small><h2>Tela sempre ativa</h2><p>Evita que o painel apague durante a navegação quando o navegador permitir.</p></div><label class="big-switch"><input id="prefWakeLock" type="checkbox" checked><span></span></label></article>
          <article class="settings-card auto-card"><div><small>VOZ</small><h2>Copiloto</h2><p id="voiceDescription">Alertas e instruções em português.</p></div><div class="settings-stack"><label class="select-label">Voz<select id="ttsVoice"><option value="">Voz padrão</option></select></label><button id="testVoice" class="settings-action" type="button">TESTAR VOZ</button></div></article>
          <article class="settings-card auto-card"><div><small>NAVEGAÇÃO</small><h2>Casa e Trabalho</h2><p>Defina destinos rápidos para usar com um toque.</p></div><div class="settings-stack"><button id="setHomeFavorite" class="settings-action" type="button">DEFINIR CASA</button><button id="setWorkFavorite" class="settings-action" type="button">DEFINIR TRABALHO</button></div></article>
          <article class="settings-card auto-card"><div><small>CONTA</small><h2><?= htmlspecialchars((string)($user['name'] ?? 'Usuário')) ?></h2><p>Mesmo usuário, banco e APIs do MusicRoad principal.</p></div><form method="post" action="../logout.php"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token(),ENT_QUOTES)?>"><button class="settings-action" type="submit">SAIR DA CONTA</button></form></article>
          <article class="settings-card auto-card" id="driveVersionDeviceCard"><div><small>VERSÃO</small><h2>MusicRoad Drive 1.2</h2><p>Interface exclusiva horizontal para multimídias automotivas. Toque algumas vezes para opções avançadas.</p></div><div class="settings-stack"><span class="version-badge">DRIVE OS</span><button id="driveRemoveDevice" class="settings-action" type="button" hidden>REMOVER DISPOSITIVO</button></div></article>
        </div>
      </section>
    </main>

    <footer class="auto-playerbar">
      <div class="footer-status"><span class="pulse-dot"></span><div><small id="footerMode">SISTEMA</small><b id="footerStatus">Pronto para dirigir</b></div></div>
      <div class="footer-now"><div class="footer-cover">♫</div><div><b id="footerTrackTitle">Nenhuma música</b><small id="footerTrackArtist">MusicRoad DriveOS</small></div></div>
      <div class="footer-controls"><button data-player="prev" type="button">⏮</button><button class="footer-play" data-player="toggle" type="button">▶</button><button data-player="next" type="button">⏭</button></div>
      <div class="footer-date"><span id="dateLabel">--</span><b id="footerTemperature">DRIVE</b></div>
    </footer>
  </div>

  <div id="pointModal" class="modal" aria-hidden="true">
    <button id="pointModalBackdrop" class="modal-backdrop" type="button" aria-label="Fechar"></button>
    <section class="modal-panel" role="dialog" aria-modal="true" aria-labelledby="pointModalTitle">
      <header><div><small>POSIÇÃO ATUAL</small><h2 id="pointModalTitle">Registrar ponto</h2></div><button id="closePointModal" type="button">×</button></header>
      <div id="pointTypeStep" class="point-type-grid">
        <button data-point-type="RADAR_REPORTADO" data-point-label="Radar" type="button"><span class="road-sign">60</span><b>Radar</b><small>velocidade</small></button>
        <button data-point-type="FISCALIZACAO_PORTATIL" data-point-label="Fiscalização portátil" type="button"><span>⚡</span><b>Portátil</b><small>móvel / estático</small></button>
        <button data-point-type="VIDEO_MONITORAMENTO" data-point-label="Videomonitoramento" type="button"><span>CAM</span><b>Vídeo</b><small>câmera / OCR</small></button>
        <button data-point-type="FISCALIZACAO_SEMAFORICA" data-point-label="Fiscalização semafórica" type="button"><span>●</span><b>Semáforo</b><small>avanço de sinal</small></button>
        <button data-point-type="QUEBRA_MOLA" data-point-label="Quebra-mola" type="button"><span>⌁</span><b>Quebra-mola</b><small>lombada</small></button>
      </div>
      <div id="pointSpeedStep" class="point-speed-step" hidden><button id="backPointType" class="back-link" type="button">‹ Voltar</button><small>VELOCIDADE PERMITIDA</small><h3 id="pointSpeedLabel">Radar</h3><div class="speed-choice-grid"><?php foreach ([30,40,50,60,70,80,90,100,110] as $v): ?><button data-point-speed="<?= $v ?>" type="button"><?= $v ?></button><?php endforeach; ?></div><button id="saveUnknownPointSpeed" class="unknown-speed" type="button">Não sei a velocidade</button></div>
    </section>
  </div>

  <audio id="webAudio" preload="metadata"></audio>
  <script nonce="<?=htmlspecialchars(csp_nonce(),ENT_QUOTES)?>">
    window.MRA = <?= json_encode([
      'csrf' => $csrf,
      'user' => ['name' => $user['name'] ?? 'Motorista', 'role' => $user['role'] ?? 'user'],
      'mapbox' => $mapbox,
      'version' => '1.2.0'
    ], JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES) ?>;
  </script>
  <script src="../assets/vendor/leaflet/leaflet.js?v=1.9.4"></script>
  <?php if($mapbox['enabled']): ?><script nonce="<?=htmlspecialchars(csp_nonce(),ENT_QUOTES)?>" src="https://api.mapbox.com/mapbox-gl-js/v3.26.0/mapbox-gl.js"></script><?php endif; ?>
  <script src="../assets/js/mapbox-base.js?v=1.2.0"></script>
  <script nonce="<?=htmlspecialchars(csp_nonce(),ENT_QUOTES)?>">try{if(window.MusicRoadAndroid){window.MusicRoadAndroid.saveAccountSnapshot?.(JSON.stringify(<?= json_encode(['id'=>$user['id']??0,'name'=>$user['name']??'Usuário','role'=>$user['role']??'client'],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES) ?>));window.MusicRoadAndroid.setSetupComplete?.(true);}}catch(_){}</script>
  <script src="assets/auto.js?v=1.2.0"></script>
</body>
</html>
