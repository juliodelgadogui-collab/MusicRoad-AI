<?php
// EPC_CONVOY_PUBLIC_LINK_V238
// Public landing page for links such as /c/7K4M2Q. The public domain is kept
// separate from the internal API hostname; this page only hands the code back
// to the installed Android app through the existing deep-link scheme.

header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
header('Pragma: no-cache');
header('Referrer-Policy: no-referrer');
header('X-Content-Type-Options: nosniff');

$raw = isset($_GET['code']) ? (string)$_GET['code'] : '';
$code = strtoupper(preg_replace('/[^A-Z0-9]/i', '', $raw));
$code = substr($code, 0, 8);

if (strlen($code) < 4) {
    http_response_code(404);
    $code = '';
}

$deepLink = $code !== '' ? 'estradaplay://comboio/' . rawurlencode($code) : '';
$title = $code !== '' ? 'Entrar no Comboio ' . $code : 'Convite inválido';
?>
<!doctype html>
<html lang="pt-BR">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
    <meta name="theme-color" content="#080507">
    <meta name="robots" content="noindex,nofollow,noarchive">
    <title><?= htmlspecialchars($title, ENT_QUOTES, 'UTF-8') ?> · Estrada Play</title>
    <meta property="og:title" content="Comboio Estrada Play">
    <meta property="og:description" content="Toque para abrir o convite no Estrada Play.">
    <style>
        *{box-sizing:border-box}html,body{margin:0;min-height:100%;background:#080507;color:#f6eee0;font-family:Arial,sans-serif}
        body{display:flex;align-items:center;justify-content:center;padding:24px}
        .card{width:min(480px,100%);background:#12090c;border:1px solid #4c262c;border-radius:26px;padding:30px;text-align:center;box-shadow:0 24px 70px rgba(0,0,0,.45)}
        .star{font-size:42px;color:#e2b94c}.eyebrow{margin-top:8px;color:#48d486;font-size:12px;font-weight:700;letter-spacing:.14em}
        h1{font-size:26px;margin:12px 0 8px}.code{font-size:34px;font-weight:700;letter-spacing:.16em;color:#e2b94c;margin:18px 0}
        p{color:#ae9792;line-height:1.5}.open{display:block;margin-top:24px;padding:17px 20px;background:#be1226;border-radius:16px;color:white;text-decoration:none;font-weight:700}
        .hint{font-size:12px;margin-top:18px}.invalid{color:#ff6b6b}
    </style>
</head>
<body>
<div class="card">
    <div class="star">★</div>
    <div class="eyebrow">ESTRADA PLAY · COMBOIO</div>
    <?php if ($code !== ''): ?>
        <h1>Convite para o Comboio</h1>
        <div class="code"><?= htmlspecialchars($code, ENT_QUOTES, 'UTF-8') ?></div>
        <p>Estamos abrindo o Estrada Play neste aparelho.</p>
        <a class="open" id="openApp" href="<?= htmlspecialchars($deepLink, ENT_QUOTES, 'UTF-8') ?>">ABRIR ESTRADA PLAY</a>
        <p class="hint">Se o app não abrir automaticamente, toque no botão acima.</p>
        <script>
            (function(){
                var deep = <?= json_encode($deepLink, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE) ?>;
                window.setTimeout(function(){ window.location.href = deep; }, 180);
            })();
        </script>
    <?php else: ?>
        <h1 class="invalid">Convite inválido</h1>
        <p>O código do comboio não foi reconhecido.</p>
    <?php endif; ?>
</div>
</body>
</html>
