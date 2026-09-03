<?php
declare(strict_types=1);
require __DIR__ . '/api/bootstrap.php';
ensure_default_users();
$admin = require_admin();

$modules = [
    'epc' => ['label'=>'Visão geral','group'=>'Principal','icon'=>'◈','file'=>'admin_epc.php','desc'=>'Comando central do Estrada Play'],
    'clients' => ['label'=>'Clientes e músicas','group'=>'Gestão','icon'=>'●','file'=>'admin.php','desc'=>'Contas, Google Drive e biblioteca'],
    'devices' => ['label'=>'Dispositivos','group'=>'Gestão','icon'=>'▣','file'=>'admin_devices.php','desc'=>'Aparelhos registrados e acessos'],
    'reports' => ['label'=>'Relatos','group'=>'Estrada','icon'=>'▲','file'=>'admin_reports.php','desc'=>'Moderação de relatos recebidos'],
    'collective' => ['label'=>'Base comunitária','group'=>'Estrada','icon'=>'≋','file'=>'admin_collective.php','desc'=>'Dados colaborativos e ocorrências'],
    'radars' => ['label'=>'Radares','group'=>'Estrada','icon'=>'◎','file'=>'admin_radares.php','desc'=>'Base, importação e sincronização'],
    'radio' => ['label'=>'Rádio PTT','group'=>'Estrada','icon'=>'◉','file'=>'admin_radio.php','desc'=>'Estado e diagnóstico do rádio'],
    'official' => ['label'=>'Base oficial / limpeza','group'=>'Manutenção','icon'=>'★','file'=>'admin_official_data_cleanup.php','desc'=>'Prévia e remoção segura de dados não oficiais'],
    'server' => ['label'=>'Servidor','group'=>'Manutenção','icon'=>'◆','file'=>'admin_server.php','desc'=>'Saúde, armazenamento e rotinas'],
    'auth' => ['label'=>'Autenticação','group'=>'Diagnóstico','icon'=>'⌁','file'=>'admin_auth_diagnostics.php','desc'=>'Sessão, credenciais e persistência'],
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
<title>Estrada Play · Administração</title>
<style>
:root{color-scheme:dark;--bg:#080808;--side:#0d0d0e;--card:#151516;--line:#29292b;--text:#f5f2e9;--muted:#aaa6a0;--red:#a6121d;--gold:#e2be67;--gold2:#f1d788}
*{box-sizing:border-box}html,body{margin:0;height:100%;background:var(--bg);color:var(--text);font-family:Inter,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}body{overflow:hidden}.shell{height:100vh;display:grid;grid-template-columns:276px minmax(0,1fr)}.sidebar{background:linear-gradient(180deg,#111112 0,#090909 100%);border-right:1px solid var(--line);display:flex;flex-direction:column;min-height:0}.brand{padding:20px 18px 16px;display:flex;align-items:center;gap:12px;border-bottom:1px solid var(--line)}.star{width:40px;height:40px;border-radius:12px;display:grid;place-items:center;background:var(--red);color:var(--gold2);font-size:23px;box-shadow:0 8px 28px rgba(166,18,29,.22)}.brand strong{display:block;font-size:14px;letter-spacing:.09em}.brand small{display:block;color:var(--muted);font-size:10px;letter-spacing:.13em;margin-top:3px}.menu{padding:12px 10px 20px;overflow:auto;flex:1}.group{margin:13px 8px 7px;color:#6f6c68;font-size:10px;font-weight:800;letter-spacing:.14em;text-transform:uppercase}.nav{display:flex;align-items:center;gap:11px;width:100%;padding:10px 11px;margin:3px 0;border:1px solid transparent;border-radius:11px;color:#d7d4ce;text-decoration:none;transition:.14s;background:transparent}.nav:hover{background:#171718;border-color:#262628}.nav.active{background:linear-gradient(90deg,rgba(166,18,29,.28),rgba(226,190,103,.06));border-color:#54242a;color:#fff}.nav .ico{width:23px;text-align:center;color:var(--gold);font-size:15px}.nav .txt{min-width:0}.nav b{display:block;font-size:13px}.nav small{display:block;color:#85817d;font-size:10px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin-top:2px}.bottom{padding:13px;border-top:1px solid var(--line)}.who{padding:10px 11px;background:#131314;border:1px solid #242426;border-radius:10px;margin-bottom:9px}.who small{display:block;color:#85817d}.who strong{display:block;font-size:12px;margin-top:3px;overflow:hidden;text-overflow:ellipsis}.logout{display:block;text-align:center;text-decoration:none;color:#ffb9bd;border:1px solid #54242a;border-radius:9px;padding:9px;font-size:12px}.content{min-width:0;display:grid;grid-template-rows:68px minmax(0,1fr)}.topbar{display:flex;align-items:center;gap:16px;padding:10px 18px;border-bottom:1px solid var(--line);background:rgba(10,10,10,.96)}.mobile-menu{display:none}.title{min-width:0;flex:1}.title small{display:block;color:var(--gold);font-size:10px;font-weight:800;letter-spacing:.13em;text-transform:uppercase}.title strong{display:block;font-size:17px;margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.badge{font-size:11px;color:#a7d8ad;border:1px solid #285b34;background:#102317;padding:7px 10px;border-radius:999px;white-space:nowrap}.frame-wrap{position:relative;min-height:0;background:#0b0b0c}.loader{position:absolute;z-index:0;inset:0;display:grid;place-items:center;color:#777;font-size:12px}.frame{position:relative;z-index:1;width:100%;height:100%;border:0;background:#0b0b0c}.open{color:#c8c3ba;text-decoration:none;border:1px solid #323235;border-radius:9px;padding:8px 10px;font-size:11px;white-space:nowrap}.open:hover{border-color:#6c5a33;color:#fff}
@media(max-width:840px){body{overflow:auto}.shell{display:block;height:auto;min-height:100vh}.sidebar{position:fixed;z-index:20;left:0;top:0;bottom:0;width:min(86vw,310px);transform:translateX(-105%);transition:.2s;box-shadow:20px 0 60px rgba(0,0,0,.45)}body.menu-open .sidebar{transform:none}.content{height:100vh;grid-template-rows:64px minmax(0,1fr)}.mobile-menu{display:inline-grid;place-items:center;width:38px;height:38px;border:1px solid #343437;border-radius:9px;background:#141415;color:#fff;font-size:18px}.badge{display:none}.topbar{padding:9px 11px}.title strong{font-size:15px}.open{padding:7px 8px}.menu-shade{display:none;position:fixed;z-index:19;inset:0;background:rgba(0,0,0,.62)}body.menu-open .menu-shade{display:block}}
</style>
</head>
<body>
<div class="menu-shade" data-close-menu></div>
<div class="shell">
<aside class="sidebar">
  <div class="brand"><div class="star">★</div><div><strong>ESTRADA PLAY</strong><small>ADMINISTRAÇÃO UNIFICADA</small></div></div>
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
    <div class="title"><small>Central administrativa</small><strong id="moduleTitle"><?=ac_e($activeModule['label'])?></strong></div>
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
