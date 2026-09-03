<?php
declare(strict_types=1);
require __DIR__ . '/api/bootstrap.php';
ensure_default_users();
$admin = require_admin();

// ADMIN_ALL_FUNCTIONS_V239: expose every existing administrative function from one sidebar.
$modules = [
    'epc' => ['label'=>'Visão geral','group'=>'Principal','icon'=>'◈','file'=>'admin_epc.php#visao','desc'=>'Comando central, métricas e estado operacional'],

    'users' => ['label'=>'Usuários','group'=>'Contas e mídia','icon'=>'●','file'=>'admin_epc.php#usuarios','desc'=>'Usuários, status e redefinição de senha'],
    'clients' => ['label'=>'Clientes / Drive / músicas','group'=>'Contas e mídia','icon'=>'♫','file'=>'admin.php','desc'=>'Contas, Google Drive, biblioteca e senha do ADM'],
    'devices' => ['label'=>'Dispositivos','group'=>'Contas e mídia','icon'=>'▣','file'=>'admin_devices.php','desc'=>'Aparelhos registrados, acessos e credenciais'],

    'road_live' => ['label'=>'Estrada Viva','group'=>'Estrada','icon'=>'▲','file'=>'admin_epc.php#estrada-viva','desc'=>'Ocorrências LIVE e alertas comunitários'],
    'reports' => ['label'=>'Relatos','group'=>'Estrada','icon'=>'✎','file'=>'admin_reports.php','desc'=>'Moderação e aprovação de relatos recebidos'],
    'collective' => ['label'=>'Base comunitária','group'=>'Estrada','icon'=>'≋','file'=>'admin_collective.php','desc'=>'Dados colaborativos e ocorrências'],
    'traffic_weather' => ['label'=>'Trânsito e clima','group'=>'Estrada','icon'=>'☁','file'=>'admin_epc.php#trafego-clima','desc'=>'Trânsito colaborativo, clima e cache'],
    'fuel' => ['label'=>'Combustível','group'=>'Estrada','icon'=>'◆','file'=>'admin_epc.php#combustivel','desc'=>'Preços recebidos e moderação'],
    'radars' => ['label'=>'Radares','group'=>'Estrada','icon'=>'◎','file'=>'admin_radares.php','desc'=>'Base, importação, fontes e sincronização'],
    'convoys' => ['label'=>'Comboios','group'=>'Estrada','icon'=>'▶','file'=>'admin_epc.php#comboios','desc'=>'Comboios ativos, membros e encerramento'],
    'radio' => ['label'=>'Rádio PTT','group'=>'Estrada','icon'=>'◉','file'=>'admin_radio.php','desc'=>'Estado e diagnóstico do rádio por rodovia'],

    'official' => ['label'=>'Base oficial / limpeza','group'=>'Dados e manutenção','icon'=>'★','file'=>'admin_official_data_cleanup.php','desc'=>'Prévia e remoção segura da base não oficial'],
    'server' => ['label'=>'Servidor','group'=>'Dados e manutenção','icon'=>'▤','file'=>'admin_server.php','desc'=>'Saúde, armazenamento, sincronização e rotinas'],
    'operations' => ['label'=>'Operações / housekeeping','group'=>'Dados e manutenção','icon'=>'↻','file'=>'admin_epc.php#servidor','desc'=>'Manutenção, cache meteorológico e estado do host'],
    'audit' => ['label'=>'Auditoria / logs','group'=>'Dados e manutenção','icon'=>'⌁','file'=>'admin_epc.php#auditoria','desc'=>'Ações administrativas e logs recentes'],

    'auth' => ['label'=>'Autenticação','group'=>'Diagnóstico','icon'=>'🔐','file'=>'admin_auth_diagnostics.php','desc'=>'Sessão, credenciais e persistência'],
    'diag209' => ['label'=>'Diagnóstico legado 2.0.9','group'=>'Diagnóstico','icon'=>'◇','file'=>'diagnostico_estradaplay_209.php','desc'=>'Verificações de compatibilidade antigas'],
];

$requested = strtolower(trim((string)($_GET['mod'] ?? 'epc')));
$active = array_key_exists($requested,$modules) ? $requested : 'epc';
$activeModule = $modules[$active];
$groups = [];
foreach ($modules as $key=>$module) $groups[$module['group']][$key]=$module;

function ac_e(mixed $v): string { return htmlspecialchars((string)$v,ENT_QUOTES,'UTF-8'); }
?>
<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#080808">
<title>Estrada Play · Administração Completa</title>
<style>
:root{color-scheme:dark;--bg:#080808;--side:#0d0d0e;--line:#29292b;--text:#f5f2e9;--muted:#aaa6a0;--red:#a6121d;--gold:#e2be67;--gold2:#f1d788}
*{box-sizing:border-box}html,body{margin:0;height:100%;background:var(--bg);color:var(--text);font-family:Inter,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}body{overflow:hidden}.shell{height:100vh;display:grid;grid-template-columns:300px minmax(0,1fr)}.sidebar{background:linear-gradient(180deg,#111112 0,#090909 100%);border-right:1px solid var(--line);display:flex;flex-direction:column;min-height:0}.brand{padding:18px;display:flex;align-items:center;gap:12px;border-bottom:1px solid var(--line)}.star{width:42px;height:42px;border-radius:12px;display:grid;place-items:center;background:var(--red);color:var(--gold2);font-size:24px}.brand strong{display:block;font-size:14px;letter-spacing:.08em}.brand small{display:block;color:var(--muted);font-size:10px;letter-spacing:.12em;margin-top:3px}.menu{padding:10px 10px 18px;overflow:auto;flex:1}.group{margin:14px 8px 6px;color:#77736d;font-size:10px;font-weight:800;letter-spacing:.13em;text-transform:uppercase}.nav{display:flex;align-items:center;gap:10px;padding:9px 10px;margin:2px 0;border:1px solid transparent;border-radius:10px;color:#d7d4ce;text-decoration:none;background:transparent}.nav:hover{background:#171718;border-color:#272729}.nav.active{background:linear-gradient(90deg,rgba(166,18,29,.30),rgba(226,190,103,.06));border-color:#54242a;color:#fff}.ico{width:24px;text-align:center;color:var(--gold);font-size:15px}.txt{min-width:0}.txt b{display:block;font-size:12.5px}.txt small{display:block;color:#85817d;font-size:9.5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin-top:2px}.bottom{padding:12px;border-top:1px solid var(--line)}.who{padding:9px 10px;background:#131314;border:1px solid #242426;border-radius:10px;margin-bottom:8px}.who small{display:block;color:#85817d}.who strong{display:block;font-size:12px;margin-top:3px;overflow:hidden;text-overflow:ellipsis}.logout{display:block;text-align:center;text-decoration:none;color:#ffb9bd;border:1px solid #54242a;border-radius:9px;padding:9px;font-size:12px}.content{min-width:0;display:grid;grid-template-rows:66px minmax(0,1fr)}.topbar{display:flex;align-items:center;gap:14px;padding:9px 16px;border-bottom:1px solid var(--line);background:#0a0a0a}.mobile-menu{display:none}.title{min-width:0;flex:1}.title small{display:block;color:var(--gold);font-size:10px;font-weight:800;letter-spacing:.12em;text-transform:uppercase}.title strong{display:block;font-size:17px;margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.badge{font-size:11px;color:#a7d8ad;border:1px solid #285b34;background:#102317;padding:7px 10px;border-radius:999px;white-space:nowrap}.open{color:#c8c3ba;text-decoration:none;border:1px solid #323235;border-radius:9px;padding:8px 10px;font-size:11px;white-space:nowrap}.frame-wrap{position:relative;min-height:0;background:#0b0b0c}.loader{position:absolute;inset:0;display:grid;place-items:center;color:#777;font-size:12px}.frame{position:relative;width:100%;height:100%;border:0;background:#0b0b0c}.menu-shade{display:none}
@media(max-width:900px){body{overflow:auto}.shell{display:block;min-height:100vh}.sidebar{position:fixed;z-index:20;left:0;top:0;bottom:0;width:min(88vw,320px);transform:translateX(-105%);transition:.2s;box-shadow:20px 0 60px rgba(0,0,0,.45)}body.menu-open .sidebar{transform:none}.content{height:100vh;grid-template-rows:62px minmax(0,1fr)}.mobile-menu{display:inline-grid;place-items:center;width:38px;height:38px;border:1px solid #343437;border-radius:9px;background:#141415;color:#fff;font-size:18px}.badge{display:none}.topbar{padding:8px 10px}.open{padding:7px 8px}.menu-shade{position:fixed;z-index:19;inset:0;background:rgba(0,0,0,.62)}body.menu-open .menu-shade{display:block}}
</style>
</head>
<body>
<div class="menu-shade" data-close-menu></div>
<div class="shell">
<aside class="sidebar">
  <div class="brand"><div class="star">★</div><div><strong>ESTRADA PLAY</strong><small>ADMIN · TODAS AS FUNÇÕES</small></div></div>
  <nav class="menu">
    <?php foreach($groups as $group=>$items): ?>
      <div class="group"><?=ac_e($group)?></div>
      <?php foreach($items as $key=>$module): ?>
        <a class="nav <?=$key===$active?'active':''?>" href="?mod=<?=ac_e($key)?>" data-module="<?=ac_e($key)?>" data-file="<?=ac_e($module['file'])?>" data-label="<?=ac_e($module['label'])?>">
          <span class="ico"><?=ac_e($module['icon'])?></span><span class="txt"><b><?=ac_e($module['label'])?></b><small><?=ac_e($module['desc'])?></small></span>
        </a>
      <?php endforeach; ?>
    <?php endforeach; ?>
  </nav>
  <div class="bottom"><div class="who"><small>Administrador conectado</small><strong><?=ac_e($admin['name'] ?? $admin['username'] ?? 'admin')?></strong></div><a class="logout" href="logout.php" target="_top">Sair do painel</a></div>
</aside>
<main class="content">
  <header class="topbar">
    <button class="mobile-menu" type="button" data-menu>☰</button>
    <div class="title"><small>Administração completa</small><strong id="moduleTitle"><?=ac_e($activeModule['label'])?></strong></div>
    <span class="badge">● SERVIDOR CONECTADO</span>
    <a class="open" id="openModule" href="<?=ac_e($activeModule['file'])?>" target="_blank" rel="noopener">Abrir isolado ↗</a>
  </header>
  <div class="frame-wrap"><div class="loader">Carregando módulo…</div><iframe class="frame" id="adminFrame" title="<?=ac_e($activeModule['label'])?>" src="<?=ac_e($activeModule['file'])?>"></iframe></div>
</main>
</div>
<script>
(()=>{
 const frame=document.getElementById('adminFrame'), title=document.getElementById('moduleTitle'), open=document.getElementById('openModule');
 document.querySelectorAll('[data-module]').forEach(link=>link.addEventListener('click',e=>{
   e.preventDefault();
   const mod=link.dataset.module,file=link.dataset.file,label=link.dataset.label;
   document.querySelectorAll('[data-module]').forEach(x=>x.classList.remove('active'));link.classList.add('active');
   frame.src=file;frame.title=label;title.textContent=label;open.href=file;
   history.replaceState(null,'','?mod='+encodeURIComponent(mod));document.body.classList.remove('menu-open');
 }));
 document.querySelector('[data-menu]')?.addEventListener('click',()=>document.body.classList.toggle('menu-open'));
 document.querySelector('[data-close-menu]')?.addEventListener('click',()=>document.body.classList.remove('menu-open'));
})();
</script>
</body>
</html>
