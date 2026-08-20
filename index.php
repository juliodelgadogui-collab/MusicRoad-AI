<?php
header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
header('Pragma: no-cache');
require __DIR__ . '/api/bootstrap.php';
$user = require_login();
$csrf = csrf_token();
$isAdmin = (($user['role'] ?? '') === 'admin');
$mapbox = mapbox_client_config();
?>
<!doctype html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover,maximum-scale=1,user-scalable=no">
  <meta name="theme-color" content="#070b10">
  <meta name="mobile-web-app-capable" content="yes">
  <title>MusicRoad</title>
  <link rel="manifest" href="manifest.webmanifest?v=1.2.0">
  <link rel="stylesheet" href="assets/vendor/leaflet/leaflet.css?v=1.9.4">
  <?php if($mapbox['enabled']): ?><link rel="stylesheet" href="https://api.mapbox.com/mapbox-gl-js/v3.26.0/mapbox-gl.css"><?php endif; ?>
  <link rel="stylesheet" href="assets/css/cockpit.css?v=1.2.0">
  <link rel="stylesheet" href="assets/css/command.css?v=1.2.0">
  <link rel="stylesheet" href="assets/css/aurora.css?v=1.2.0">


<link rel="stylesheet" href="assets/css/final-v15.css?v=1.2.0">
<link rel="stylesheet" href="assets/css/one.css?v=1.2.0">
<link rel="stylesheet" href="assets/css/premium.css?v=1.2.0">
<style id="mr-inline-core-css"><?php
  foreach ([__DIR__.'/assets/css/cockpit.css', __DIR__.'/assets/css/command.css', __DIR__.'/assets/css/final-v15.css', __DIR__.'/assets/css/one.css', __DIR__.'/assets/css/premium.css'] as $__css) { if (is_file($__css)) echo "\n" . file_get_contents($__css); }
?></style>
<script id="mr-ui-cache-reset" nonce="<?=htmlspecialchars(csp_nonce(),ENT_QUOTES)?>">
(function(){
  var build='1.2.0';
  try{
    var key='mr-ui-build';
    if(localStorage.getItem(key)!==build){
      localStorage.setItem(key,build);
      if('caches' in window){caches.keys().then(function(keys){return Promise.all(keys.filter(function(k){return k.indexOf('musicroad-')===0&&k.indexOf('musicroad-state-offline-')!==0&&k.indexOf('musicroad-state-map-device-')!==0}).map(function(k){return caches.delete(k)}))}).catch(function(){})}
      if('serviceWorker' in navigator){navigator.serviceWorker.getRegistrations().then(function(rs){return Promise.all(rs.map(function(r){return r.unregister()}))}).finally(function(){setTimeout(function(){location.replace(location.pathname+'?ui='+build+'&t='+Date.now())},120)})}
      else {setTimeout(function(){location.replace(location.pathname+'?ui='+build+'&t='+Date.now())},120)}
    }
  }catch(e){}
})();
</script>
<script id="mr-driveos-auto-landscape" nonce="<?=htmlspecialchars(csp_nonce(),ENT_QUOTES)?>">
(function(){
  try{if(window.MusicRoadAndroid&&typeof window.MusicRoadAndroid.setLandscapeMode==='function')window.MusicRoadAndroid.setLandscapeMode(false)}catch(e){}
  var nativeApp=/MusicRoadAndroid\//i.test(navigator.userAgent||'');
  var forceStandard=new URLSearchParams(location.search).get('standard')==='1';
  function enterDriveOS(){
    if(!nativeApp||forceStandard)return;
    var landscape=(window.matchMedia&&matchMedia('(orientation: landscape)').matches)||innerWidth>innerHeight;
    if(landscape&&innerWidth>=700)location.replace('horizontal/');
  }
  addEventListener('orientationchange',function(){setTimeout(enterDriveOS,180)});
  addEventListener('resize',function(){clearTimeout(window.__mrDriveOsTimer);window.__mrDriveOsTimer=setTimeout(enterDriveOS,250)});
  setTimeout(enterDriveOS,60);
})();
</script>
  <style id="mr-critical-fallback">html,body{margin:0;min-height:100%;background:#050b12;color:#f5f8fc;font-family:Inter,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}button,input,select,a{font:inherit}button{color:inherit;background:#101e2d;border:1px solid #203044;border-radius:12px}input,select{background:#09131e;color:#f5f8fc;border:1px solid #203044;border-radius:12px}.cockpit-screen{display:none}.cockpit-screen.active{display:block}.main-drawer{display:none}.main-drawer.open{display:block}</style>
</head>
<body class="cockpit-body">
  <div class="cockpit-app" id="cockpitApp">
    <header class="one-topbar">
      <button id="mainMenuToggle" class="one-menu" type="button" aria-label="Abrir central" aria-expanded="false"><img src="assets/mr-one.svg" alt=""></button>
      <div class="one-brand"><strong>MusicRoad</strong><small>Seu caminho, sem distração</small></div>
      <div class="one-top-status"><span id="netBadge" class="one-net">online</span><button id="auroraFavorite" class="one-favorite" type="button" aria-label="Favoritos">♡</button><span class="user-chip"><?= htmlspecialchars((string)($user['name'] ?? 'Usuário')) ?></span></div>
    </header>

    <aside id="mainDrawer" class="main-drawer one-drawer" aria-hidden="true">
      <button id="mainDrawerBackdrop" class="main-drawer-backdrop" type="button" aria-label="Fechar menu"></button>
      <section class="main-drawer-panel one-drawer-panel">
        <div class="one-drawer-head"><img src="assets/mr-one.svg" alt=""><div><strong>MusicRoad</strong><small><?= htmlspecialchars((string)($user['name'] ?? 'Usuário')) ?></small></div></div>
        <nav class="drawer-nav one-launcher" aria-label="Menu principal">
          <button class="active" data-nav="board" type="button"><span>⌂</span><strong>Início</strong></button>
          <button data-nav="map" type="button"><span>➤</span><strong>Navegar</strong></button>
          <button data-nav="music" type="button"><span>♫</span><strong>Música</strong></button>
          <button data-nav="offline" type="button"><span>⇩</span><strong>Offline</strong></button>
          <button data-nav="radio" type="button"><span>FM</span><strong>Rádio</strong></button>
          <button data-nav="settings" type="button"><span>⚙</span><strong>Ajustes</strong></button>
          <button id="openDriveOs" class="one-driveos" type="button"><span>▰</span><strong>DriveOS</strong></button>
        </nav>
        <div class="drawer-footer one-drawer-footer"><span id="drawerOnlineDot"></span><b>MusicRoad 1.2</b><small>release</small></div>
      </section>
    </aside>

    <div id="roadAlert" class="road-alert" aria-live="assertive" aria-atomic="true">
      <span id="roadAlertIcon" class="road-alert-icon">⚠</span>
      <div class="road-alert-copy"><strong id="roadAlertTitle">Atenção</strong><b id="roadAlertDistance" class="road-alert-distance" hidden></b><small id="roadAlertText"></small></div>
    </div>

    <main class="screen-stack">
      <section class="cockpit-screen active one-home" data-screen="board" id="screen-board">
        <div class="one-home-head">
          <div><small>BEM-VINDO</small><h1><?= htmlspecialchars((string)($user['name'] ?? 'Motorista')) ?></h1></div>
          <span id="gpsStatus" class="one-gps">GPS aguardando</span>
        </div>

        <article class="one-destination-card trip-card">
          <div class="one-section-title"><span>➤</span><div><small>DESTINO</small><h2>Onde vamos?</h2></div></div>
          <div class="destination-mode-row one-mode" role="group" aria-label="Modo de destino">
            <button id="destinationCityMode" class="destination-mode is-active" type="button" aria-pressed="true">Cidade</button>
            <button id="destinationFreeMode" class="destination-mode" type="button" aria-pressed="false">Endereço</button>
          </div>
          <div id="destinationCityFields" class="destination-city-fields one-destination-fields">
            <label class="field"><span>Estado</span><select id="destinationState"><option value="">Selecione</option></select></label>
            <label class="field city-search-field"><span>Cidade</span><input id="destinationCitySearch" autocomplete="off" placeholder="Digite a cidade" disabled><select id="destinationCity" hidden disabled><option value="">Escolha primeiro o estado</option></select><div id="destinationCitySuggestions" class="place-suggestions" hidden></div></label>
          </div>
          <label id="destinationFreeField" class="field address-search-field" hidden><span>Rua, número ou CEP</span><input id="destination" autocomplete="off" placeholder="Ex.: Rua das Flores, 120"><div id="destinationSuggestions" class="place-suggestions" hidden></div></label>
          <div class="one-origin-row">
            <label class="field one-origin"><span>Origem</span><div class="field-inline"><input id="origin" autocomplete="off" placeholder="Minha localização"><button id="useLocation" class="icon-button" type="button" aria-label="Usar localização">◎</button></div></label>
            <div class="trip-actions"><button id="startTrip" class="one-go primary-action" type="button">IR</button><button id="stopTrip" class="trip-stop" type="button" hidden>ENCERRAR</button></div>
          </div>
          <p id="tripSummary" class="trip-summary one-trip-summary">Escolha o destino para começar.</p>
        </article>

        <div class="one-actions" aria-label="Atalhos">
          <button data-go="map" type="button"><span>⌖</span><div><strong>Mapa</strong><small>Ver navegação</small></div></button>
          <button data-go="music" type="button"><span>♫</span><div><strong>Música</strong><small>Ouvir agora</small></div></button>
          <button data-go="offline" type="button"><span>⇩</span><div><strong>Offline</strong><small>Mapas no aparelho</small></div></button>
        </div>

        <div class="v15-stats one-trip-glance" aria-label="Resumo da viagem">
          <div class="v15-stat"><span>VELOCIDADE</span><strong id="speedNow">0</strong><small>km/h</small></div>
          <div class="v15-stat"><span>LIMITE</span><strong id="speedLimit">--</strong><small>km/h</small></div>
          <div class="v15-stat"><span>ALERTA</span><strong id="nextRadar">--</strong><small>à frente</small></div>
        </div>

        <article class="one-now-card now-card">
          <div class="cover-placeholder" id="miniCover">♫</div>
          <div class="now-meta"><small>AGORA</small><strong id="boardNowTitle">Nenhuma música</strong><span id="boardNowArtist">Toque em Música para escolher</span></div>
          <button class="round-control main" data-player="toggle" type="button" aria-label="Play/Pause">▶</button>
        </article>
      </section>

      <section class="cockpit-screen" data-screen="music" id="screen-music">
        <div class="music-head">
          <div>
            <small>MINHA BIBLIOTECA</small>
            <h1>Música</h1>
          </div>
          <button id="refreshMusic" class="sync-icon" type="button" aria-label="Sincronizar biblioteca" title="Sincronizar">↻</button>
        </div>

        <article id="emptyLibraryCard" class="empty-library-card" hidden>
          <span class="empty-library-icon">♫</span>
          <div><strong>Sua biblioteca está vazia</strong><p>Autorize o acesso às músicas do celular ou sincronize o Drive. Depois disso, filtros e pastas aparecem automaticamente.</p></div>
          <div class="empty-library-actions"><button id="emptyGrantMusic" class="primary-action" type="button">PERMITIR MÚSICAS</button><button id="emptySyncOnline" class="ghost-button" type="button">SINCRONIZAR DRIVE</button></div>
        </article>

        <div class="source-tabs source-tabs-v2" role="tablist" aria-label="Fontes de música">
          <button class="active" data-source-filter="all" type="button"><span>Todas</span><b>0</b></button>
          <button data-source-filter="device" type="button"><span>Celular</span><b>0</b></button>
          <button data-source-filter="drive" type="button"><span>Drive</span><b>0</b></button>
          <button data-source-filter="server" type="button"><span>Servidor</span><b>0</b></button>
        </div>

        <div class="offline-download-bar" id="offlineDownloadBar">
          <div class="offline-download-symbol">↓</div>
          <div><strong>Downloads offline</strong><small>Toque no círculo ao lado da música. Verde com ✓ = já está no celular.</small></div>
          <button id="downloadedOnly" type="button" aria-pressed="false">Baixadas <b id="downloadedCount">0</b></button>
        </div>

        <div class="library-toolbar">
          <label class="search-box" for="musicSearch">
            <span>⌕</span>
            <input id="musicSearch" type="search" inputmode="search" autocomplete="off" placeholder="Buscar música, artista, pasta ou ritmo">
          </label>
        </div>

        <div id="librarySummary" class="library-summary">Organizando sua biblioteca...</div>

        <section class="facet-block" aria-labelledby="foldersLabel">
          <div class="facet-title"><span id="foldersLabel">PASTAS</span><button id="clearFolderFilter" type="button">Todas</button></div>
          <div id="folderRail" class="folder-rail" aria-label="Pastas de músicas"></div>
        </section>

        <section class="facet-block compact" aria-labelledby="rhythmsLabel">
          <div class="facet-title"><span id="rhythmsLabel">RITMOS</span><button id="clearRhythmFilter" type="button">Todos</button></div>
          <div id="rhythmRail" class="rhythm-rail" aria-label="Ritmos musicais"></div>
        </section>

        <div id="activeFilters" class="active-filters"></div>
        <div id="musicStatus" class="inline-status">Carregando biblioteca...</div>
        <div id="trackList" class="track-list track-list-v2"></div>

        <div class="music-actions-hidden" aria-hidden="true">
          <button id="scanDeviceMusic" type="button">Atualizar celular</button>
          <button id="loadServerMusic" type="button">Atualizar Drive/Servidor</button>
        </div>
      </section>

      <section class="cockpit-screen" data-screen="radio" id="screen-radio">
        <div class="screen-heading radio-heading"><div><small>ENTRETENIMENTO</small><h1>Rádio</h1></div><span class="source-badge">FM</span></div>
        <article class="card radio-fm-card">
          <div class="settings-title-row"><div><h2>Rádio FM do aparelho</h2><p>Se o seu celular tiver aplicativo/tuner FM exposto pelo Android, o MusicRoad pode abri-lo diretamente.</p></div><span class="radio-wave">)))</span></div>
          <button id="openFmRadio" class="primary-action compact-action" type="button">ABRIR RÁDIO FM</button>
          <small id="fmRadioStatus">Verificando rádio FM disponível no aparelho...</small>
        </article>
        <article class="card radio-online-card">
          <div class="settings-title-row"><div><h2>Rádios online</h2><p>Salve as estações que você gosta. Streams online usam o mesmo player do MusicRoad e continuam tocando em segundo plano.</p></div><span class="source-badge">WEB</span></div>
          <form id="radioStationForm" class="radio-station-form" autocomplete="off">
            <input id="radioName" type="text" placeholder="Nome da rádio" required>
            <input id="radioFrequency" type="text" inputmode="decimal" placeholder="Frequência (ex.: 98.7 FM)">
            <input id="radioUrl" type="url" placeholder="https://... stream de áudio" required autocapitalize="none" spellcheck="false">
            <button class="primary-action compact-action" type="submit">SALVAR ESTAÇÃO</button>
          </form>
          <div id="radioStatus" class="inline-status">Adicione uma estação por URL ou abra o rádio FM do aparelho.</div>
          <div id="radioStations" class="radio-stations"></div>
        </article>
      </section>

      <section class="cockpit-screen map-screen" data-screen="map" id="screen-map">
        <div class="map-toolbar">
          <button id="mapBack" class="ghost-button" type="button">← Bordo</button>
          <div><strong>Navegação</strong><small id="mapRouteText">Nenhuma viagem ativa</small><span id="mapSourceBadge" class="map-source-badge" data-mode="online">MAPA ONLINE</span></div>
          <div class="map-toolbar-actions">
            <button id="fitRoute" class="ghost-button map-icon-button" type="button" aria-label="Ver rota inteira">▣</button>
            <button id="mapFiscalToggle" class="ghost-button fiscal-toggle" type="button" aria-label="Abrir Central de Fiscalização" aria-expanded="false">⚠</button>
            <button id="recenterMap" class="ghost-button" type="button" aria-label="Centralizar no veículo">◎ Seguir</button>
          </div>
        </div>
        <aside id="mapFiscalPanel" class="map-fiscal-panel" aria-hidden="true">
          <div class="map-fiscal-head"><div><small>CAMADAS DO MAPA</small><strong>Central de Fiscalização</strong></div><button id="closeMapFiscal" type="button" aria-label="Fechar">×</button></div>
          <div class="map-fiscal-grid">
            <button class="map-fiscal-kind is-on" data-map-kind="radar" type="button" aria-pressed="true"><span class="kind-icon radar">◎</span><div><strong>Radares</strong><small><b data-kind-count="radar">0</b> na rota</small></div><i></i></button>
            <button class="map-fiscal-kind is-on" data-map-kind="bump" type="button" aria-pressed="true"><span class="kind-icon bump">⌁</span><div><strong>Quebra-molas</strong><small><b data-kind-count="bump">0</b> na rota</small></div><i></i></button>
            <button class="map-fiscal-kind is-on" data-map-kind="video" type="button" aria-pressed="true"><span class="kind-icon video">▣</span><div><strong>Vídeo monitoramento</strong><small><b data-kind-count="video">0</b> na rota</small></div><i></i></button>
            <button class="map-fiscal-kind is-on" data-map-kind="signal" type="button" aria-pressed="true"><span class="kind-icon signal">●</span><div><strong>Semáforos</strong><small><b data-kind-count="signal">0</b> na rota</small></div><i></i></button>
          </div>
          <button id="reportPoint" class="map-report-point" type="button">＋ Registrar ponto neste local</button>
          <small class="map-fiscal-note">Os filtros controlam o que aparece no mapa e quais alertas serão emitidos.</small>
        </aside>
        <article class="aurora-turn-card" id="auroraTurnCard">
          <div class="turn-icon">➜</div>
          <div><strong id="auroraTurnDistance">Rota</strong><span id="auroraTurnInstruction">Siga o trajeto destacado</span><small id="auroraTurnRoad">MusicRoad Navigation</small></div>
          <div class="turn-stats"><b id="auroraTripDistance">--</b><small>distância</small><b id="auroraTripTime">--</b><small>tempo</small></div>
        </article>
        <div class="map-driving-hud">
          <div class="hud-speed"><strong id="mapSpeed">0</strong><span>km/h</span></div>
          <div class="hud-metric"><span>LIMITE</span><strong id="mapLimit">--</strong></div>
          <div class="hud-metric radar"><span>PRÓXIMA FISCALIZAÇÃO</span><strong id="mapNextRadar">--</strong><small id="mapRadarInfo">Nenhum ponto à frente</small></div>
        </div>
        <div id="map"></div>
        <div id="radarCoverage" class="radar-coverage">Pontos de fiscalização serão carregados ao iniciar a viagem.</div>
      </section>

      <section class="cockpit-screen" data-screen="offline" id="screen-offline">
        <div class="music-head"><div><small>USO SEM INTERNET</small><h1>Mapas e radares</h1></div></div>
        <article class="card settings-card state-download-card">
          <div class="settings-title-row"><div><h2>Baixar por estado</h2><p>Escolha o estado e baixe os radares e um mapa-base viário para o próprio dispositivo. Se houver uma cidade selecionada nesse estado, o MusicRoad baixa também o detalhe urbano dela.</p></div><span class="source-badge">BR</span></div>
          <label class="field"><span>Estado</span><select id="offlineState"><option value="">Selecione o estado</option></select></label>
          <div class="state-download-actions"><button id="downloadStateRadars" class="primary-action" type="button" disabled>↓ BAIXAR RADARES</button><button id="downloadStateMap" class="ghost-button" type="button" disabled>↓ BAIXAR MAPA</button></div>
          <div id="stateOfflineProgress" class="state-offline-progress" aria-live="polite">Selecione um estado para começar.</div>
          <div class="state-download-note"><strong>Armazenamento local</strong><small>O pacote fica no armazenamento offline deste aparelho. A base estadual cobre as principais rodovias; o detalhe completo é baixado sob demanda apenas para a cidade selecionada, evitando centenas de downloads desnecessários.</small></div>
        </article>
        <article class="card settings-card state-download-card device-map-card">
          <div class="settings-title-row"><div><h2>Mapa no aparelho</h2><p>Depois do download, o MusicRoad lê as vias diretamente do armazenamento offline do dispositivo.</p></div><span class="source-badge">DEVICE</span></div>
          <div class="state-download-note"><strong>Uso eficiente</strong><small>O servidor mantém apenas um cache técnico temporário da base estadual para não repetir consultas externas; o pacote de uso do motorista fica no aparelho. Para liberar espaço, use as configurações do Android para limpar os dados offline do MusicRoad.</small></div>
        </article>
      </section>

      <section class="cockpit-screen" data-screen="settings" id="screen-settings">
        <div class="screen-heading"><div><small>SISTEMA</small><h1>Ajustes</h1></div></div>
        <article class="card settings-card"><h2>Conta</h2><p><?= htmlspecialchars((string)($user['name'] ?? 'Usuário')) ?></p><form id="logoutForm" method="post" action="logout.php"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token(),ENT_QUOTES)?>"><button class="settings-link danger-link" type="submit">Sair da conta</button></form></article>
        <article class="card settings-card"><h2>Aplicativo</h2><p id="nativeInfo">Verificando integração Android...</p><button id="openNativeLibrary" class="settings-link native-only" type="button">Abrir biblioteca nativa</button></article>
        <article class="card settings-card voice-card">
          <div class="settings-title-row"><div><h2>Voz do copiloto</h2><p>Escolha uma voz instalada no Android e ajuste o ritmo da fala.</p></div><span class="source-badge">VOZ</span></div>
          <label class="setting-field"><span>Voz</span><select id="ttsVoice"><option value="">Voz padrão do aparelho</option></select></label>
          <label class="setting-field"><span>Velocidade da fala <b id="ttsRateValue">1.00×</b></span><input id="ttsRate" type="range" min="0.75" max="1.25" step="0.05" value="1"></label>
          <label class="setting-field"><span>Tom da voz <b id="ttsPitchValue">1.00×</b></span><input id="ttsPitch" type="range" min="0.80" max="1.20" step="0.05" value="1"></label>
          <div class="settings-inline-actions"><button id="refreshTtsVoices" class="settings-link" type="button">Atualizar vozes</button><button id="testTtsVoice" class="settings-link" type="button">Testar voz</button></div>
          <small id="ttsStatus">A voz disponível depende do mecanismo de fala instalado no telefone.</small>
        </article>
        <article class="card settings-card notification-card">
          <div class="settings-title-row"><div><h2>Alertas da viagem</h2><p>Você decide como o MusicRoad avisa durante a condução.</p></div><span class="source-badge">ALERTA</span></div>
          <div class="setting-switch-grid">
            <label class="setting-switch"><input id="prefVoice" type="checkbox" checked><span><strong>Falar alertas</strong><small>voz do copiloto</small></span></label>
            <label class="setting-switch"><input id="prefVisual" type="checkbox" checked><span><strong>Banner na tela</strong><small>aviso temporário no cockpit</small></span></label>
            <label class="setting-switch"><input id="prefSystem" type="checkbox" checked><span><strong>Notificação Android</strong><small>inclusive em segundo plano</small></span></label>
            <label class="setting-switch"><input id="prefVibrate" type="checkbox" checked><span><strong>Vibração</strong><small>pulso curto no alerta</small></span></label>
            <label class="setting-switch"><input id="prefManeuvers" type="checkbox" checked><span><strong>Orientação da rota</strong><small>direita, esquerda, retorno</small></span></label>
            <label class="setting-switch"><input id="prefSpeeding" type="checkbox" checked><span><strong>Excesso de velocidade</strong><small>alerta ao superar limite conhecido</small></span></label>
          </div>
          <div class="settings-subtitle">Distâncias de aviso</div>
          <div class="distance-choice-grid">
            <label><input id="pref300" type="checkbox" checked><span>300 m</span></label>
            <label><input id="pref200" type="checkbox" checked><span>200 m</span></label>
            <label><input id="pref100" type="checkbox" checked><span>100 m</span></label>
            <label><input id="pref50" type="checkbox" checked><span>50 m</span></label>
            <label class="front-choice"><input id="prefFront" type="checkbox" checked><span>Na sua frente</span></label>
          </div>
          <div class="settings-subtitle">Tipos de ponto</div>
          <div class="distance-choice-grid point-choice-grid">
            <label><input id="prefRadar" type="checkbox" checked><span>Radar</span></label>
            <label><input id="prefPortable" type="checkbox" checked><span>Portátil</span></label>
            <label><input id="prefVideo" type="checkbox" checked><span>Vídeo</span></label>
            <label><input id="prefSignal" type="checkbox" checked><span>Semáforo</span></label>
            <label><input id="prefBump" type="checkbox" checked><span>Quebra-mola</span></label>
          </div>
          <small id="notificationPrefStatus">Preferências salvas neste aparelho.</small>
        </article>
        <article class="card settings-card offline-card"><div class="settings-title-row"><div><h2>Modo offline</h2><p>O MusicRoad mantém a última rota, radares carregados, telas e mapas já visualizados. Músicas baixadas do Drive aparecem na biblioteca do celular.</p></div><span id="offlineReadyBadge" class="source-badge">AUTO</span></div><small id="offlineStatus">Preparando cache offline...</small></article>
        <?php if ($isAdmin): ?>
        <article class="card settings-card"><h2>Administração</h2><a class="settings-link" href="admin.php">Painel administrativo</a><a class="settings-link" href="admin_radares.php">Central de radares</a></article>
        <article class="card settings-card radar-import-card">
          <div class="settings-title-row"><div><h2>Base de fiscalização Brasil</h2><p>Importe sua própria base CSV/TXT/KML para complementar ANTT, DNIT, DER, OSM e pontos comunitários.</p></div><span class="source-badge">BR</span></div>
          <label class="radar-import-file" for="radarImportFile"><span>⇧</span><div><strong>Selecionar arquivo de radares</strong><small>Compatível com iGO/MapaRadar: X,Y,TYPE,SPEED,DIRTYPE,DIRECTION</small></div></label>
          <input id="radarImportFile" type="file" accept=".csv,.txt,.kml,text/csv,text/plain,application/vnd.google-earth.kml+xml">
          <div class="radar-import-actions"><select id="radarImportSource" aria-label="Origem da base"><option value="MAPARADAR_USUARIO">MapaRadar — arquivo do usuário</option><option value="IMPORT_USUARIO">Outra base CSV/TXT/KML</option></select><button id="importRadarDb" class="primary-action compact-action" type="button">IMPORTAR BASE</button></div>
          <div id="radarImportStatus" class="inline-status">Nenhum arquivo importado nesta sessão.</div>
          <small class="license-note">Use apenas arquivos que você tenha direito de utilizar. O MusicRoad não redistribui a base importada.</small>
        </article>
        <?php endif; ?>
        <article class="card settings-card" id="versionDeviceCard"><h2>Versão</h2><p>MusicRoad 1.2 · segurança, radares e Offline Core</p><small>Toque algumas vezes nesta área para abrir opções avançadas do dispositivo.</small><button id="removeRegisteredDevice" class="settings-link danger-link native-only" type="button" hidden>Remover este dispositivo da conta</button></article>
      </section>
    </main>

    <div id="pointSheet" class="point-sheet" aria-hidden="true">
      <button id="pointSheetBackdrop" class="point-sheet-backdrop" type="button" aria-label="Fechar"></button>
      <section class="point-sheet-panel" role="dialog" aria-modal="true" aria-labelledby="pointSheetTitle">
        <div class="point-sheet-head"><div><small>POSIÇÃO ATUAL</small><strong id="pointSheetTitle">Registrar fiscalização</strong></div><button id="closePointSheet" type="button" aria-label="Fechar">×</button></div>
        <div id="pointTypeStep">
          <div class="point-type-grid">
            <button data-point-type="RADAR_REPORTADO" data-point-label="Radar de velocidade" type="button"><span>60</span><strong>Radar</strong><small>mede velocidade</small></button>
            <button data-point-type="FISCALIZACAO_PORTATIL" data-point-label="Fiscalização portátil" type="button"><span>⚡</span><strong>Portátil</strong><small>ponto móvel/estático</small></button>
            <button data-point-type="VIDEO_MONITORAMENTO" data-point-label="Videomonitoramento" type="button"><span>CAM</span><strong>Vídeo</strong><small>câmera / OCR</small></button>
            <button data-point-type="FISCALIZACAO_SEMAFORICA" data-point-label="Fiscalização semafórica" type="button"><span>●</span><strong>Semáforo</strong><small>avanço de sinal</small></button>
            <button data-point-type="QUEBRA_MOLA" data-point-label="Quebra-mola" type="button"><span>⌁</span><strong>Quebra-mola</strong><small>lombada / redutor</small></button>
          </div>
          <p class="point-sheet-note">Escolha o tipo de fiscalização encontrado neste ponto.</p>
        </div>
        <div id="pointSpeedStep" class="point-speed-step" hidden>
          <div class="speed-step-title"><button id="backPointType" type="button">‹</button><div><small>VELOCIDADE PERMITIDA</small><strong id="pointSpeedLabel">Radar</strong></div></div>
          <div class="speed-choice-grid">
            <button data-point-speed="30" type="button">30</button><button data-point-speed="40" type="button">40</button><button data-point-speed="50" type="button">50</button>
            <button data-point-speed="60" type="button">60</button><button data-point-speed="70" type="button">70</button><button data-point-speed="80" type="button">80</button>
            <button data-point-speed="90" type="button">90</button><button data-point-speed="100" type="button">100</button><button data-point-speed="110" type="button">110</button>
          </div>
          <div class="custom-speed-row"><input id="customPointSpeed" type="number" inputmode="numeric" min="20" max="130" step="10" placeholder="Outra velocidade"><button id="saveCustomPointSpeed" type="button">SALVAR</button></div>
          <button id="saveUnknownPointSpeed" class="speed-unknown" type="button">Não sei a velocidade</button>
        </div>
      </section>
    </div>

    <div id="appUpdateSheet" class="app-update-sheet" aria-hidden="true">
      <button class="app-update-backdrop" id="appUpdateLaterBackdrop" type="button" aria-label="Fechar"></button>
      <section class="app-update-panel" role="dialog" aria-modal="true" aria-labelledby="appUpdateVersion">
        <div class="app-update-kicker">ATUALIZAÇÃO DO APP</div>
        <h3 id="appUpdateVersion">Nova versão disponível</h3>
        <p id="appUpdateNotes">Há uma atualização do MusicRoad disponível para baixar.</p>
        <div class="app-update-actions"><button id="appUpdateLater" type="button">DEPOIS</button><button id="appUpdateDownload" class="primary" type="button">BAIXAR ATUALIZAÇÃO</button></div>
      </section>
    </div>

    <section class="dock-player" id="dockPlayer">
      <div class="dock-track"><div class="dock-cover">♪</div><div><strong id="nowTitle">Nenhuma música</strong><span id="nowArtist">MusicRoad</span></div></div>
      <input id="playerProgress" class="progress" type="range" min="0" max="100" value="0" step="0.1" aria-label="Progresso">
      <div class="dock-controls"><button data-player="prev" type="button">⏮</button><button data-player="toggle" class="play-toggle" type="button">▶</button><button data-player="next" type="button">⏭</button></div>
    </section>

  </div>
  <audio id="webAudio" preload="metadata"></audio>
  <script nonce="<?=htmlspecialchars(csp_nonce(),ENT_QUOTES)?>">window.MR_BOOTSTRAP={csrf:<?= json_encode($csrf) ?>,user:<?= json_encode(['id'=>$user['id']??0,'name'=>$user['name']??'Usuário','role'=>$user['role']??'client'],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES) ?>,mapbox:<?=json_encode($mapbox,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES)?>,version:'1.2.0'};</script>
<script nonce="<?=htmlspecialchars(csp_nonce(),ENT_QUOTES)?>">try{if(window.MusicRoadAndroid){window.MusicRoadAndroid.saveAccountSnapshot?.(JSON.stringify(window.MR_BOOTSTRAP.user||{}));window.MusicRoadAndroid.setSetupComplete?.(true);}}catch(_){}</script>
  <script src="assets/vendor/leaflet/leaflet.js?v=1.9.4"></script>
  <?php if($mapbox['enabled']): ?><script nonce="<?=htmlspecialchars(csp_nonce(),ENT_QUOTES)?>" src="https://api.mapbox.com/mapbox-gl-js/v3.26.0/mapbox-gl.js"></script><?php endif; ?>
  <script src="assets/js/mapbox-base.js?v=1.2.0"></script>
  <script src="assets/js/cockpit-player.js?v=1.2.0"></script>
  <script src="assets/js/cockpit.js?v=1.2.0"></script>
</body>
</html>
