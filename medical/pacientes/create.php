<?php
require_once __DIR__ . '/../api/bootstrap.php';
require_login();

$medico = current_medico();
$error = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $nome = $_POST['nome'] ?? '';
    $cpf = $_POST['cpf'] ?? '';
    $data_nascimento = $_POST['data_nascimento'] ?? '';
    $sexo = $_POST['sexo'] ?? '';
    $telefone = $_POST['telefone'] ?? '';
    $email = $_POST['email'] ?? '';
    $endereco = $_POST['endereco'] ?? '';
    $profissao = $_POST['profissao'] ?? '';
    $funcao = $_POST['funcao'] ?? '';
    $empresa = $_POST['empresa'] ?? '';
    $info_clinica = $_POST['info_clinica'] ?? '';

    if (empty($nome)) {
        $error = 'O nome é obrigatório.';
    } else {
        $stmt = db()->prepare('INSERT INTO pacientes (medico_id, nome, cpf, data_nascimento, sexo, telefone, email, endereco, profissao, funcao, empresa, info_clinica) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)');
        $stmt->execute([$medico['id'], $nome, $cpf, $data_nascimento, $sexo, $telefone, $email, $endereco, $profissao, $funcao, $empresa, $info_clinica]);
        $id = db()->lastInsertId();

        audit_log('cadastro_paciente', 'pacientes', $id);
        header('Location: index.php');
        die();
    }
}
?>
<!DOCTYPE html>
<html lang="pt-br">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Novo Paciente - Sistema Médico</title>
    <style>
        body { font-family: Arial, sans-serif; background-color: #f4f7f6; margin: 0; padding: 0; }
        .header { background-color: #0056b3; color: white; padding: 15px 20px; display: flex; justify-content: space-between; align-items: center; }
        .header h1 { margin: 0; font-size: 20px; }
        .nav { background-color: #333; overflow: hidden; }
        .nav a { float: left; display: block; color: white; text-align: center; padding: 14px 16px; text-decoration: none; }
        .nav a:hover { background-color: #ddd; color: black; }
        .container { padding: 20px; max-width: 800px; margin: auto; }
        .form-group { margin-bottom: 15px; }
        label { display: block; margin-bottom: 5px; font-weight: bold; }
        input[type="text"], input[type="date"], input[type="email"], select, textarea { width: 100%; padding: 8px; border: 1px solid #ccc; border-radius: 4px; box-sizing: border-box; }
        .btn { padding: 10px 15px; background: #0056b3; color: white; border: none; border-radius: 4px; cursor: pointer; }
        .btn:hover { background: #004494; }
        .error { color: red; margin-bottom: 15px; }
        .card { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
    </style>
</head>
<body>
    <div class="header">
        <h1>Sistema Médico</h1>
        <div>
            <?php echo htmlspecialchars($medico['nome_completo']); ?>
            <a href="../login/logout.php" style="color: white; margin-left: 15px;">Sair</a>
        </div>
    </div>
    <div class="nav">
        <a href="../dashboard/index.php">Dashboard</a>
        <a href="index.php">Pacientes</a>
        <a href="../atendimentos/index.php">Atendimentos</a>
        <a href="../atestados/index.php">Atestados</a>
        <a href="../laudos/index.php">Laudos</a>
    </div>
    <div class="container">
        <h2>Novo Paciente</h2>
        <div class="card">
            <?php if ($error): ?>
                <div class="error"><?php echo htmlspecialchars($error); ?></div>
            <?php endif; ?>
            <form method="POST">
                <div class="form-group">
                    <label>Nome Completo *</label>
                    <input type="text" name="nome" required>
                </div>
                <div style="display: flex; gap: 15px;">
                    <div class="form-group" style="flex: 1;">
                        <label>CPF</label>
                        <input type="text" name="cpf">
                    </div>
                    <div class="form-group" style="flex: 1;">
                        <label>Data de Nascimento</label>
                        <input type="date" name="data_nascimento">
                    </div>
                    <div class="form-group" style="flex: 1;">
                        <label>Sexo</label>
                        <select name="sexo">
                            <option value="">Selecione</option>
                            <option value="M">Masculino</option>
                            <option value="F">Feminino</option>
                            <option value="O">Outro</option>
                        </select>
                    </div>
                </div>
                <div style="display: flex; gap: 15px;">
                    <div class="form-group" style="flex: 1;">
                        <label>Telefone</label>
                        <input type="text" name="telefone">
                    </div>
                    <div class="form-group" style="flex: 1;">
                        <label>E-mail</label>
                        <input type="email" name="email">
                    </div>
                </div>
                <div class="form-group">
                    <label>Endereço</label>
                    <input type="text" name="endereco">
                </div>
                <div style="display: flex; gap: 15px;">
                    <div class="form-group" style="flex: 1;">
                        <label>Profissão</label>
                        <input type="text" name="profissao">
                    </div>
                    <div class="form-group" style="flex: 1;">
                        <label>Função / Atividade</label>
                        <input type="text" name="funcao">
                    </div>
                    <div class="form-group" style="flex: 1;">
                        <label>Empresa</label>
                        <input type="text" name="empresa">
                    </div>
                </div>
                <div class="form-group">
                    <label>Informações Clínicas Relevantes</label>
                    <textarea name="info_clinica" rows="4"></textarea>
                </div>
                <button type="submit" class="btn">Salvar Paciente</button>
                <a href="index.php" style="margin-left: 10px; color: #666;">Cancelar</a>
            </form>
        </div>
    </div>
</body>
</html>
