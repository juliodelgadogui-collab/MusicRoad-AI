<?php
require_once __DIR__ . '/../api/bootstrap.php';

if (current_medico()) {
    header('Location: ../dashboard/index.php');
    die();
}

$error = '';
$success = '';

$stmt = db()->query('SELECT COUNT(*) as count FROM medicos');
$is_first_access = $stmt->fetch()['count'] == 0;

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    require_csrf();
    $email = trim($_POST['email'] ?? '');
    $senha = $_POST['senha'] ?? '';

    if ($is_first_access) {
        $nome = trim($_POST['nome'] ?? '');
        $crm = trim($_POST['crm'] ?? '');
        $uf = trim($_POST['uf'] ?? '');

        if (empty($nome) || empty($email) || empty($senha) || empty($crm) || empty($uf)) {
            $error = 'Todos os campos são obrigatórios para o primeiro acesso.';
        } else {
            $hash = password_hash($senha, PASSWORD_DEFAULT);
            $stmt = db()->prepare('INSERT INTO medicos (nome_completo, email, senha_hash, crm, uf, is_admin) VALUES (?, ?, ?, ?, ?, 1)');
            $stmt->execute([$nome, $email, $hash, $crm, $uf]);
            $success = 'Conta médica criada com sucesso. Você já pode fazer login.';
            $is_first_access = false;
        }
    } else {
        $stmt = db()->prepare('SELECT * FROM medicos WHERE email = ?');
        $stmt->execute([$email]);
        $medico = $stmt->fetch();

        if ($medico && password_verify($senha, $medico['senha_hash'])) {
            session_regenerate_id(true);
            $_SESSION['medico_id'] = $medico['id'];
            audit_log('login', 'medicos', $medico['id'], ['ip' => $_SERVER['REMOTE_ADDR']]);
            header('Location: ../dashboard/index.php');
            die();
        } else {
            $error = 'Email ou senha incorretos.';
        }
    }
}
?>
<!DOCTYPE html>
<html lang="pt-br">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title><?php echo $is_first_access ? 'Primeiro Acesso' : 'Login'; ?> - Sistema Médico</title>
    <style>
        body { font-family: Arial, sans-serif; background-color: #f4f7f6; display: flex; justify-content: center; align-items: center; min-height: 100vh; margin: 0; }
        .login-box { background: white; padding: 30px; border-radius: 8px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); width: 100%; max-width: 400px; }
        h1 { margin-top: 0; font-size: 24px; color: #333; text-align: center; }
        p.subtitle { text-align: center; color: #666; margin-bottom: 20px; font-size: 14px; }
        label { display: block; margin-bottom: 5px; color: #666; font-size: 14px; }
        input[type="email"], input[type="password"], input[type="text"] { width: 100%; padding: 10px; margin-bottom: 15px; border: 1px solid #ccc; border-radius: 4px; box-sizing: border-box; }
        button { width: 100%; padding: 12px; background-color: #0056b3; color: white; border: none; border-radius: 4px; font-size: 16px; cursor: pointer; font-weight: bold; }
        button:hover { background-color: #004494; }
        .error { color: #d9534f; margin-bottom: 15px; text-align: center; padding: 10px; background: #fdf2f2; border: 1px solid #d9534f; border-radius: 4px; }
        .success { color: #28a745; margin-bottom: 15px; text-align: center; padding: 10px; background: #e8f5e9; border: 1px solid #28a745; border-radius: 4px; }
    </style>
</head>
<body>
    <div class="login-box">
        <h1>Sistema Médico</h1>
        <?php if ($is_first_access): ?>
            <p class="subtitle">Bem-vindo. Este é o seu primeiro acesso. Por favor, crie a sua conta médica administrativa abaixo para iniciar.</p>
        <?php else: ?>
            <p class="subtitle">Faça login para acessar o sistema.</p>
        <?php endif; ?>

        <?php if ($error): ?>
            <div class="error"><?php echo htmlspecialchars($error); ?></div>
        <?php endif; ?>

        <?php if ($success): ?>
            <div class="success"><?php echo htmlspecialchars($success); ?></div>
        <?php endif; ?>

        <form method="POST">
            <input type="hidden" name="csrf" value="<?php echo htmlspecialchars(csrf_token()); ?>">

            <?php if ($is_first_access): ?>
                <label>Nome Completo</label>
                <input type="text" name="nome" required>

                <div style="display: flex; gap: 10px;">
                    <div style="flex: 2;">
                        <label>CRM</label>
                        <input type="text" name="crm" required>
                    </div>
                    <div style="flex: 1;">
                        <label>UF</label>
                        <input type="text" name="uf" required maxlength="2">
                    </div>
                </div>
            <?php endif; ?>

            <label>E-mail</label>
            <input type="email" name="email" required autofocus>

            <label>Senha</label>
            <input type="password" name="senha" required>

            <button type="submit"><?php echo $is_first_access ? 'Criar Conta' : 'Entrar'; ?></button>
        </form>
    </div>
</body>
</html>
