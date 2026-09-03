<?php
require __DIR__ . '/api/bootstrap.php';
ensure_default_users();
if ($u = current_user()) {
    header('Location: ' . (($u['role'] ?? '') === 'admin' ? 'admin_central.php?p=dashboard' : 'client.php'));
    exit;
}
$error = '';
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    require_csrf();
    $login = trim((string)($_POST['login'] ?? ''));
    $password = (string)($_POST['password'] ?? '');
    $stmt = db()->prepare('SELECT * FROM users WHERE (username = ? OR email = ?) LIMIT 1');
    $stmt->execute([$login, $login]);
    $user = $stmt->fetch();
    if ($user && ($user['status'] ?? 'active') !== 'active') {
        $error = 'Este usuário está desativado. Fale com o administrador.';
    } elseif ($user && password_verify($password, (string)$user['password_hash'])) {
        session_regenerate_id(true);
        $_SESSION['user_id'] = (int)$user['id'];
        $upd = db()->prepare("UPDATE users SET last_login_at = CURRENT_TIMESTAMP WHERE id = ?");
        $upd->execute([(int)$user['id']]);
        audit_log('auth.login', ['user_id'=>(int)$user['id'],'role'=>$user['role']]);
        header('Location: ' . (($user['role'] ?? '') === 'admin' ? 'admin_central.php?p=dashboard' : 'client.php'));
        exit;
    } else {
        $error = 'Usuário ou senha inválidos.';
    }
}
?>
<!doctype html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><meta name="theme-color" content="#080808">
  <title>Entrar · Estrada Play</title><link rel="stylesheet" href="assets/css/app.css?v=1.0.0">
</head>
<body class="login-page" style="background:radial-gradient(circle at 50% 0,rgba(179,19,25,.22),transparent 38%),#080808">
  <main class="login-wrap"><form class="login-card" method="post" autocomplete="on" style="border-color:#4a2426;background:#100d0d">
    <input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>">
    <div class="brand login-brand"><span class="brand-mark" style="background:#a51218;color:#f2d47d">★</span><strong>Estrada Play Comunista</strong></div>
    <span class="version-chip">Servidor Central</span><h1>Acesso EPC</h1>
    <p><strong>Administrador</strong> entra no novo Painel Completo 2.3.10. <strong>Cliente</strong> mantém o acesso normal da conta.</p>
    <?php if ($error): ?><div class="alert"><?= htmlspecialchars($error) ?></div><?php endif; ?>
    <label>Usuário ou e-mail<input name="login" autocomplete="username" required></label>
    <label>Senha<input name="password" type="password" autocomplete="current-password" required></label>
    <button type="submit">Acessar sistema</button>
  </form></main>
</body></html>
