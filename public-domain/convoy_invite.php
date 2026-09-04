<?php
// EPC_CONVOY_PUBLIC_PAGE_V239
header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
header('Pragma: no-cache');
header('Referrer-Policy: no-referrer');
header('X-Content-Type-Options: nosniff');
header("Permissions-Policy: geolocation=(), camera=(), microphone=()");

$raw = isset($_GET['code']) ? (string)$_GET['code'] : '';
$code = strtoupper(preg_replace('/[^A-Z0-9]/i', '', $raw));
$code = substr($code, 0, 8);
if (strlen($code) < 4) {
    http_response_code(404);
    $code = '';
}
$deepLink = $code !== '' ? 'estradaplay://comboio/' . rawurlencode($code) : 'estradaplay://comboio';
$title = $code !== '' ? 'Entrar no Comboio ' . $code : 'Convite inválido';
?>
<!doctype html>
<html lang="pt-BR">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
    <meta name="theme-color" content="#090607">
    <meta name="robots" content="noindex,nofollow,noarchive">
    <title><?= htmlspecialchars($title, ENT_QUOTES, 'UTF-8') ?> · Estrada Play</title>
    <meta property="og:title" content="Convite para Comboio · Estrada Play">
    <meta property="og:description" content="Abra o convite no Estrada Play para entrar no Comboio.">
    <style>
        :root{color-scheme:dark;--bg:#070506;--panel:#12090c;--panel2:#1a0c10;--border:#572a31;--red:#c41128;--red2:#8f0d1c;--gold:#e2b94c;--text:#f7efe4;--muted:#b7a29d;--green:#48d486;--bad:#ff6b6b}
        *{box-sizing:border-box}html,body{margin:0;min-height:100%;background:var(--bg);color:var(--text);font-family:Inter,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
        body{display:flex;align-items:center;justify-content:center;padding:max(24px,env(safe-area-inset-top)) 20px max(24px,env(safe-area-inset-bottom));overflow-x:hidden}
        body:before{content:"";position:fixed;inset:-25%;background:radial-gradient(circle at 50% 25%,rgba(196,17,40,.19),transparent 34%),radial-gradient(circle at 72% 80%,rgba(226,185,76,.08),transparent 25%);pointer-events:none}
        .wrap{position:relative;width:min(540px,100%)}
        .card{position:relative;overflow:hidden;background:linear-gradient(180deg,var(--panel2),var(--panel));border:1px solid var(--border);border-radius:30px;padding:35px 27px 28px;text-align:center;box-shadow:0 28px 90px rgba(0,0,0,.55)}
        .card:before{content:"";position:absolute;left:0;right:0;top:0;height:3px;background:linear-gradient(90deg,transparent,var(--red),var(--gold),var(--red),transparent)}
        .star{display:grid;place-items:center;width:48px;height:48px;margin:0 auto 12px;border-radius:50%;border:1px solid rgba(226,185,76,.45);background:rgba(226,185,76,.08);color:var(--gold);font-size:26px}
        .brand{color:var(--gold);font-size:11px;font-weight:900;letter-spacing:.15em}
        .badge{display:inline-flex;align-items:center;gap:7px;margin:16px 0 10px;padding:7px 11px;border:1px solid rgba(72,212,134,.25);border-radius:999px;background:rgba(72,212,134,.07);color:var(--green);font-size:11px;font-weight:800;letter-spacing:.07em}
        .dot{width:7px;height:7px;border-radius:50%;background:var(--green);box-shadow:0 0 12px rgba(72,212,134,.65)}
        h1{margin:5px 0 0;font-size:clamp(27px,7vw,38px);line-height:1.1;letter-spacing:-.03em}
        p{color:var(--muted);line-height:1.55}
        .code{display:inline-block;margin:22px 0 5px;padding:13px 18px;border-radius:14px;border:1px solid rgba(226,185,76,.3);background:rgba(226,185,76,.06);color:var(--gold);font-size:clamp(28px,9vw,39px);font-weight:900;letter-spacing:.16em}
        .open{display:flex;align-items:center;justify-content:center;gap:10px;width:100%;margin-top:24px;padding:18px 20px;border:1px solid #e3354a;border-radius:17px;background:linear-gradient(180deg,#d41631,var(--red2));box-shadow:0 14px 36px rgba(196,17,40,.24);color:#fff;text-decoration:none;font-weight:900;letter-spacing:.035em}
        .open:active{transform:scale(.985)}
        .hint{min-height:36px;margin:18px 0 0;color:#8f7c78;font-size:12px;line-height:1.5}
        .security{margin-top:14px;padding:13px 14px;border:1px solid rgba(87,42,49,.72);border-radius:14px;background:rgba(255,255,255,.018);color:#9d8884;font-size:11px;line-height:1.5}
        .invalid{color:var(--bad)}
        .home{display:inline-block;margin-top:19px;color:var(--gold);font-size:12px;text-decoration:none;font-weight:800}
        @media(max-width:420px){.card{padding:30px 18px 24px;border-radius:24px}}
    </style>
</head>
<body>
<main class="wrap">
<section class="card">
    <div class="star">★</div>
    <div class="brand">ESTRADA PLAY · COMBOIO</div>
    <?php if ($code !== ''): ?>
        <div class="badge"><span class="dot"></span> CONVITE RECEBIDO</div>
        <h1>Entre no Comboio</h1>
        <p>Alguém compartilhou um Comboio com você. Confirme abaixo para abrir o Estrada Play e entrar no grupo.</p>
        <div class="code"><?= htmlspecialchars($code, ENT_QUOTES, 'UTF-8') ?></div>
        <a class="open" id="openApp" href="<?= htmlspecialchars($deepLink, ENT_QUOTES, 'UTF-8') ?>">ENTRAR NO COMBOIO <span aria-hidden="true">→</span></a>
        <p class="hint" id="hint">O aplicativo só será aberto quando você tocar no botão.</p>
        <div class="security">O código deste convite é usado apenas para localizar o Comboio dentro do Estrada Play. Esta página não solicita login, senha ou localização.</div>
    <?php else: ?>
        <div class="badge"><span class="dot"></span> ESTRADA PLAY</div>
        <h1 class="invalid">Convite inválido</h1>
        <p>O código deste Comboio não foi reconhecido. Peça um novo link para quem enviou o convite.</p>
        <a class="open" href="estradaplay://comboio">ABRIR ESTRADA PLAY <span aria-hidden="true">→</span></a>
    <?php endif; ?>
    <a class="home" href="/">Ir para a página do Estrada Play</a>
</section>
</main>
<?php if ($code !== ''): ?>
<script>
(function(){
    var button=document.getElementById('openApp');
    var hint=document.getElementById('hint');
    button.addEventListener('click',function(){
        hint.textContent='Abrindo o Estrada Play…';
        window.setTimeout(function(){
            if(!document.hidden) hint.textContent='Se nada aconteceu, confirme se o Estrada Play está instalado neste aparelho.';
        },1500);
    });
})();
</script>
<?php endif; ?>
</body>
</html>
