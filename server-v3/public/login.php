<?php
declare(strict_types=1);
require_once __DIR__ . '/../bootstrap.php';

if (current_user()) {
    header('Location: app/');
    exit;
}

$error = '';
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    require_csrf();
    $login = trim((string)($_POST['login'] ?? ''));
    $password = (string)($_POST['password'] ?? '');
    try {
        $stmt = db()->prepare('SELECT * FROM users WHERE (username=? OR email=?) LIMIT 1');
        $stmt->execute([$login,$login]);
        $user = $stmt->fetch();
        if (!$user || ($user['status'] ?? 'active') !== 'active' || !password_verify($password,(string)$user['password_hash'])) {
            $error = 'Usuário ou senha inválidos.';
        } else {
            session_regenerate_id(true);
            $_SESSION['user_id'] = (int)$user['id'];
            try {
                $u = db()->prepare('UPDATE users SET last_login_at=CURRENT_TIMESTAMP WHERE id=?');
                $u->execute([(int)$user['id']]);
            } catch (Throwable $e) {}
            if (function_exists('audit_log')) audit_log('web_v3.login',['user_id'=>(int)$user['id']]);
            header('Location: app/');
            exit;
        }
    } catch (Throwable $e) {
        error_log('EPC V3 LOGIN: '.$e->getMessage());
        $error = 'Não foi possível entrar agora.';
    }
}
?>
<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#090909">
<title>Estrada Play Web 3.0</title>
<link rel="manifest" href="manifest.webmanifest">
<link rel="stylesheet" href="assets/app.css?v=300a1">
</head>
<body class="v3-login-page">
<main class="v3-login-wrap">
  <form method="post" class="v3-login-card" autocomplete="on">
    <input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token(),ENT_QUOTES,'UTF-8')?>">
    <div class="v3-login-brand"><span>★</span><div><strong>ESTRADA PLAY</strong><small>WEB · SERVER 3.0 ALPHA</small></div></div>
    <h1>O app no navegador</h1>
    <p>Mapa, GPS, rota, radares oficiais, clima e música usando a mesma conta do Estrada Play.</p>
    <?php if($error):?><div class="v3-login-error"><?=htmlspecialchars($error,ENT_QUOTES,'UTF-8')?></div><?php endif;?>
    <label>Usuário ou e-mail<input name="login" required autocomplete="username"></label>
    <label>Senha<input name="password" type="password" required autocomplete="current-password"></label>
    <button type="submit">ENTRAR NO ESTRADA PLAY WEB</button>
    <small class="v3-login-note">A versão web é independente do APK. O Android continua nativo, sem WebView.</small>
  </form>
</main>
</body>
</html>
