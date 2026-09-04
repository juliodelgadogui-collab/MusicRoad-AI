<?php
// EPC_PUBLIC_HOME_V239
header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
header('Pragma: no-cache');
header('Referrer-Policy: no-referrer');
header('X-Content-Type-Options: nosniff');
header("Permissions-Policy: geolocation=(), camera=(), microphone=()");

$deepLink = 'estradaplay://comboio';
?>
<!doctype html>
<html lang="pt-BR">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
    <meta name="theme-color" content="#090607">
    <meta name="robots" content="noindex,nofollow,noarchive">
    <title>Estrada Play</title>
    <meta name="description" content="Abra o Estrada Play neste aparelho.">
    <style>
        :root{color-scheme:dark;--bg:#070506;--panel:#12090c;--panel2:#1a0c10;--border:#572a31;--red:#c41128;--red2:#8f0d1c;--gold:#e2b94c;--text:#f7efe4;--muted:#b7a29d;--green:#48d486}
        *{box-sizing:border-box}
        html,body{margin:0;min-height:100%;background:var(--bg);color:var(--text);font-family:Inter,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
        body{display:flex;align-items:center;justify-content:center;padding:max(24px,env(safe-area-inset-top)) 20px max(24px,env(safe-area-inset-bottom));overflow-x:hidden}
        body:before{content:"";position:fixed;inset:-25%;background:radial-gradient(circle at 50% 25%,rgba(196,17,40,.18),transparent 34%),radial-gradient(circle at 70% 78%,rgba(226,185,76,.07),transparent 26%);pointer-events:none}
        .wrap{position:relative;width:min(560px,100%)}
        .card{position:relative;overflow:hidden;background:linear-gradient(180deg,var(--panel2),var(--panel));border:1px solid var(--border);border-radius:30px;padding:36px 28px 30px;text-align:center;box-shadow:0 28px 90px rgba(0,0,0,.55)}
        .card:before{content:"";position:absolute;left:0;right:0;top:0;height:3px;background:linear-gradient(90deg,transparent,var(--red),var(--gold),var(--red),transparent)}
        .brand{display:inline-flex;align-items:center;gap:11px;margin-bottom:18px;font-weight:800;letter-spacing:.12em;font-size:12px;color:var(--gold)}
        .star{display:grid;place-items:center;width:48px;height:48px;margin:0 auto 12px;border-radius:50%;border:1px solid rgba(226,185,76,.45);background:rgba(226,185,76,.08);color:var(--gold);font-size:26px;box-shadow:0 0 28px rgba(226,185,76,.12)}
        .status{display:inline-flex;align-items:center;gap:7px;margin-bottom:14px;padding:7px 11px;border:1px solid rgba(72,212,134,.25);border-radius:999px;background:rgba(72,212,134,.07);color:var(--green);font-size:11px;font-weight:800;letter-spacing:.08em}
        .dot{width:7px;height:7px;border-radius:50%;background:var(--green);box-shadow:0 0 12px rgba(72,212,134,.65)}
        h1{margin:0;font-size:clamp(29px,8vw,42px);line-height:1.05;letter-spacing:-.035em}
        .lead{margin:16px auto 0;max-width:430px;color:var(--muted);font-size:16px;line-height:1.6}
        .open{display:flex;align-items:center;justify-content:center;gap:10px;width:100%;margin-top:28px;padding:18px 20px;border:1px solid #e3354a;border-radius:17px;background:linear-gradient(180deg,#d41631,var(--red2));box-shadow:0 14px 36px rgba(196,17,40,.24);color:#fff;text-decoration:none;font-weight:900;letter-spacing:.035em;transition:transform .15s ease,filter .15s ease}
        .open:active{transform:scale(.985);filter:brightness(.92)}
        .features{display:grid;grid-template-columns:repeat(3,1fr);gap:9px;margin-top:21px}
        .feature{padding:12px 7px;border:1px solid rgba(87,42,49,.75);border-radius:14px;background:rgba(255,255,255,.018);color:#d7c7c1;font-size:12px;font-weight:700}
        .hint{min-height:34px;margin:19px 4px 0;color:#8f7c78;font-size:12px;line-height:1.45}
        .invite{margin-top:16px;padding:15px 16px;border:1px dashed rgba(226,185,76,.27);border-radius:15px;color:#ad9892;font-size:12px;line-height:1.5}
        .invite strong{color:var(--gold)}
        .foot{margin-top:14px;color:#685957;font-size:10px;letter-spacing:.05em}
        @media(max-width:420px){.card{padding:30px 18px 24px;border-radius:24px}.features{gap:6px}.feature{font-size:11px}}
    </style>
</head>
<body>
<main class="wrap">
    <section class="card">
        <div class="star">★</div>
        <div class="brand">ESTRADA PLAY</div>
        <div class="status"><span class="dot"></span> APP PARA ANDROID</div>
        <h1>Sua estrada.<br>Seu som.</h1>
        <p class="lead">Abra o Estrada Play neste aparelho para continuar sua experiência de navegação, música e Comboio.</p>
        <a class="open" id="openApp" href="<?= htmlspecialchars($deepLink, ENT_QUOTES, 'UTF-8') ?>">ABRIR ESTRADA PLAY <span aria-hidden="true">→</span></a>
        <div class="features" aria-label="Recursos do aplicativo">
            <div class="feature">MAPA</div>
            <div class="feature">MÚSICA</div>
            <div class="feature">COMBOIO</div>
        </div>
        <p class="hint" id="hint">Toque no botão acima. Se o aplicativo estiver instalado, o Android abrirá o Estrada Play.</p>
        <div class="invite">Recebeu um convite de Comboio? <strong>Abra pelo link que a pessoa enviou</strong> para entrar no grupo correto automaticamente.</div>
        <div class="foot">ESTRADA PLAY · EXPERIÊNCIA AUTOMOTIVA</div>
    </section>
</main>
<script>
(function(){
    var button=document.getElementById('openApp');
    var hint=document.getElementById('hint');
    button.addEventListener('click',function(){
        hint.textContent='Tentando abrir o Estrada Play…';
        window.setTimeout(function(){
            if(!document.hidden) hint.textContent='Se nada aconteceu, confirme se o Estrada Play está instalado neste aparelho.';
        },1500);
    });
})();
</script>
</body>
</html>
