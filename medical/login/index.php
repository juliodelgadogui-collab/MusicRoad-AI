<?php
require_once __DIR__ . '/../api/bootstrap.php';

if (current_medico()) {
    header('Location: ../dashboard/index.php');
    die();
}

$error = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $email = $_POST['email'] ?? '';
    $senha = $_POST['senha'] ?? '';

    $stmt = db()->prepare('SELECT * FROM medicos WHERE email = ?');
    $stmt->execute([$email]);
    $medico = $stmt->fetch();

    if ($medico && password_verify($senha, $medico['senha_hash'])) {
        $_SESSION['medico_id'] = $medico['id'];
        audit_log('login', 'medicos', $medico['id'], ['ip' => $_SERVER['REMOTE_ADDR']]);
        header('Location: ../dashboard/index.php');
        die();
    } else {
        $error = 'Email ou senha incorretos.';
    }
}
?>
<!DOCTYPE html>
<html lang="pt-br">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Login - Sistema Médico</title>
    <style>
        body { font-family: Arial, sans-serif; background-color: #f4f7f6; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; }
        .login-box { background: white; padding: 20px 30px; border-radius: 8px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); width: 100%; max-width: 400px; }
        h1 { margin-top: 0; font-size: 24px; color: #333; text-align: center; }
        label { display: block; margin-bottom: 5px; color: #666; }
        input[type="email"], input[type="password"] { width: 100%; padding: 10px; margin-bottom: 15px; border: 1px solid #ccc; border-radius: 4px; box-sizing: border-box; }
        button { width: 100%; padding: 10px; background-color: #0056b3; color: white; border: none; border-radius: 4px; font-size: 16px; cursor: pointer; }
        button:hover { background-color: #004494; }
        .error { color: #d9534f; margin-bottom: 15px; text-align: center; }
    </style>
</head>
<body>
    <div class="login-box">
        <h1>Sistema Médico</h1>
        <?php if ($error): ?>
            <div class="error"><?php echo htmlspecialchars($error); ?></div>
        <?php endif; ?>
        <form method="POST">
            <label for="email">E-mail</label>
            <input type="email" id="email" name="email" required autofocus>
            <label for="senha">Senha</label>
            <input type="password" id="senha" name="senha" required>
            <button type="submit">Entrar</button>
        </form>
    </div>
</body>
</html>
