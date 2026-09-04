<?php
declare(strict_types=1);
require_once __DIR__ . '/../../bootstrap.php';
$user = current_user();
if (!$user) {
    header('Location: ../login.php');
    exit;
}
?>
<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover,user-scalable=no">
<meta name="theme-color" content="#090909">
<title>Estrada Play Web</title>
<link rel="manifest" href="../manifest.webmanifest">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" crossorigin="">
<link rel="stylesheet" href="../assets/app.css?v=300a1">
</head>
<body class="v3-app-page">
<div id="appShell" class="v3-shell">
  <header class="v3-topbar">
    <div class="v3-brand"><span class="v3-star">★</span><div><strong>ESTRADA PLAY</strong><small>WEB 3.0 · ALPHA</small></div></div>
    <form id="searchForm" class="v3-search"><input id="destinationInput" placeholder="Para onde você vai?" autocomplete="off"><button>IR</button></form>
    <div class="v3-account"><span id="serverBadge">SERVIDOR</span><strong><?=htmlspecialchars((string)($user['name']?:$user['username']),ENT_QUOTES,'UTF-8')?></strong><a href="../../../logout.php">Sair</a></div>
  </header>

  <main class="v3-stage">
    <div id="map" class="v3-map"></div>

    <section class="v3-drive-hud">
      <div><small>VELOCIDADE</small><strong id="speedValue">0</strong><span>km/h</span></div>
      <div><small>GPS</small><strong id="gpsState">AGUARDANDO</strong></div>
    </section>

    <section id="routeCard" class="v3-card v3-route-card">
      <div class="v3-card-head"><div><small>ROTA</small><strong id="routeTitle">Sem destino</strong></div><button id="clearRoute" class="v3-icon-btn" title="Limpar rota">×</button></div>
      <div id="routeMetrics" class="v3-metrics"><span>— km</span><span>— min</span></div>
      <div id="routeSteps" class="v3-steps"><p>Digite um destino para começar.</p></div>
    </section>

    <section class="v3-side-stack">
      <article class="v3-card">
        <div class="v3-card-head"><div><small>CLIMA</small><strong id="weatherTitle">Aguardando GPS</strong></div><span id="weatherSeverity" class="v3-pill">—</span></div>
        <p id="weatherText">O clima da rota aparecerá aqui.</p>
      </article>
      <article class="v3-card">
        <div class="v3-card-head"><div><small>ALERTAS OFICIAIS</small><strong id="alertCount">0 próximos</strong></div><span class="v3-pill ok">OFICIAL</span></div>
        <div id="alertsList" class="v3-list"><p>Nenhum alerta carregado.</p></div>
      </article>
    </section>

    <div class="v3-map-actions">
      <button id="locateButton" class="v3-fab" title="Minha localização">◎</button>
      <button id="musicButton" class="v3-fab" title="Música">♫</button>
    </div>

    <aside id="musicPanel" class="v3-music-panel" aria-hidden="true">
      <div class="v3-panel-head"><div><small>BIBLIOTECA</small><strong>Música</strong></div><button id="closeMusic" class="v3-icon-btn">×</button></div>
      <input id="musicSearch" class="v3-panel-search" placeholder="Buscar música ou artista">
      <div id="musicList" class="v3-music-list"><p>Carregando biblioteca…</p></div>
    </aside>
  </main>

  <footer class="v3-player">
    <div class="v3-track-meta"><small>TOCANDO AGORA</small><strong id="nowTitle">Nada tocando</strong><span id="nowArtist">—</span></div>
    <button id="playPause" class="v3-play" disabled>▶</button>
    <audio id="audio" preload="metadata"></audio>
  </footer>
</div>
<script>window.EPC_V3={api:'../../api/v8',version:'<?=EPC_V3_VERSION?>',account:<?=json_encode(v3_account($user),JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES)?>};</script>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js" crossorigin=""></script>
<script src="../assets/app.js?v=300a1"></script>
</body>
</html>
