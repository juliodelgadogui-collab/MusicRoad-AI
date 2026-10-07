<?php
require_once __DIR__ . '/../api/bootstrap.php';
require_login();

$medico = current_medico();
$error = '';
$success = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    require_csrf();
    $nome = trim($_POST['nome_completo'] ?? '');
    $cpf = trim($_POST['cpf'] ?? '');
    $crm = trim($_POST['crm'] ?? '');
    $uf = trim($_POST['uf'] ?? '');
    $especialidade = trim($_POST['especialidade'] ?? '');
    $rqe = trim($_POST['rqe'] ?? '');
    $telefone = trim($_POST['telefone'] ?? '');
    $endereco = trim($_POST['endereco'] ?? '');

    if (empty($nome) || empty($crm) || empty($uf)) {
        $error = 'Nome, CRM e UF são obrigatórios.';
    } else {
        $assinatura_path = $medico['assinatura_path'];

        if (isset($_FILES['assinatura']) && $_FILES['assinatura']['error'] === UPLOAD_ERR_OK) {
            $tmp_name = $_FILES['assinatura']['tmp_name'];
            $name = basename($_FILES['assinatura']['name']);
            $size = $_FILES['assinatura']['size'];

            $finfo = finfo_open(FILEINFO_MIME_TYPE);
            $mime = finfo_file($finfo, $tmp_name);
            finfo_close($finfo);

            $allowed_mimes = ['image/png', 'image/jpeg', 'image/webp'];
            $allowed_exts = ['png', 'jpg', 'jpeg', 'webp'];
            $ext = strtolower(pathinfo($name, PATHINFO_EXTENSION));

            if (!in_array($mime, $allowed_mimes) || !in_array($ext, $allowed_exts)) {
                $error = 'A assinatura deve ser uma imagem válida (PNG, JPG, WEBP).';
            } elseif ($size > 2 * 1024 * 1024) {
                $error = 'O arquivo de assinatura não pode exceder 2MB.';
            } else {
                $filename = 'assinatura_' . $medico['id'] . '_' . time() . '.' . $ext;
                $dest = __DIR__ . '/../storage/assinaturas/' . $filename;
                if (move_uploaded_file($tmp_name, $dest)) {
                    // Remove old signature if exists
                    if ($assinatura_path && file_exists(__DIR__ . '/../' . $assinatura_path)) {
                        unlink(__DIR__ . '/../' . $assinatura_path);
                    }
                    $assinatura_path = 'storage/assinaturas/' . $filename;
                } else {
                    $error = 'Falha ao salvar a imagem da assinatura.';
                }
            }
        }

        if (!$error) {
            $stmt = db()->prepare('UPDATE medicos SET nome_completo = ?, cpf = ?, crm = ?, uf = ?, especialidade = ?, rqe = ?, telefone = ?, endereco = ?, assinatura_path = ? WHERE id = ?');
            $stmt->execute([$nome, $cpf, $crm, $uf, $especialidade, $rqe, $telefone, $endereco, $assinatura_path, $medico['id']]);
            $success = 'Configurações salvas com sucesso!';
            $medico = current_medico(); // Refresh
        }
    }
}
?>
<!DOCTYPE html>
<html lang="pt-br">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Configurações - Sistema Médico</title>
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
        input[type="text"], input[type="file"], textarea { width: 100%; padding: 8px; border: 1px solid #ccc; border-radius: 4px; box-sizing: border-box; }
        .btn { padding: 10px 15px; background: #0056b3; color: white; border: none; border-radius: 4px; cursor: pointer; font-size: 16px; }
        .btn:hover { background: #004494; }
        .error { color: #d9534f; margin-bottom: 15px; padding: 10px; background: #fdf2f2; border: 1px solid #d9534f; border-radius: 4px; }
        .success { color: #28a745; margin-bottom: 15px; padding: 10px; background: #e8f5e9; border: 1px solid #28a745; border-radius: 4px; }
        .card { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); margin-bottom: 20px; }
        .assinatura-preview { max-width: 300px; max-height: 150px; border: 1px solid #ddd; padding: 5px; background: #f9f9f9; margin-top: 10px; }
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
        <a href="../pacientes/index.php">Pacientes</a>
        <a href="../atendimentos/index.php">Atendimentos</a>
        <a href="../atestados/index.php">Atestados</a>
        <a href="../laudos/index.php">Laudos</a>
        <a href="index.php" style="background-color: #ddd; color: black;">Configurações</a>
    </div>
    <div class="container">
        <h2>Configurações do Médico</h2>

        <?php if ($error): ?>
            <div class="error"><?php echo htmlspecialchars($error); ?></div>
        <?php endif; ?>
        <?php if ($success): ?>
            <div class="success"><?php echo htmlspecialchars($success); ?></div>
        <?php endif; ?>

        <form method="POST" enctype="multipart/form-data">
            <input type="hidden" name="csrf" value="<?php echo htmlspecialchars(csrf_token()); ?>">

            <div class="card">
                <h3>Dados Profissionais</h3>
                <div class="form-group">
                    <label>Nome Completo *</label>
                    <input type="text" name="nome_completo" value="<?php echo htmlspecialchars($medico['nome_completo'] ?? ''); ?>" required>
                </div>

                <div style="display: flex; gap: 15px;">
                    <div class="form-group" style="flex: 1;">
                        <label>CPF</label>
                        <input type="text" name="cpf" value="<?php echo htmlspecialchars($medico['cpf'] ?? ''); ?>">
                    </div>
                    <div class="form-group" style="flex: 1;">
                        <label>Telefone</label>
                        <input type="text" name="telefone" value="<?php echo htmlspecialchars($medico['telefone'] ?? ''); ?>">
                    </div>
                </div>

                <div style="display: flex; gap: 15px;">
                    <div class="form-group" style="flex: 1;">
                        <label>CRM *</label>
                        <input type="text" name="crm" value="<?php echo htmlspecialchars($medico['crm'] ?? ''); ?>" required>
                    </div>
                    <div class="form-group" style="flex: 1;">
                        <label>UF *</label>
                        <input type="text" name="uf" value="<?php echo htmlspecialchars($medico['uf'] ?? ''); ?>" required maxlength="2">
                    </div>
                </div>

                <div style="display: flex; gap: 15px;">
                    <div class="form-group" style="flex: 1;">
                        <label>Especialidade</label>
                        <input type="text" name="especialidade" value="<?php echo htmlspecialchars($medico['especialidade'] ?? ''); ?>">
                    </div>
                    <div class="form-group" style="flex: 1;">
                        <label>RQE</label>
                        <input type="text" name="rqe" value="<?php echo htmlspecialchars($medico['rqe'] ?? ''); ?>">
                    </div>
                </div>

                <div class="form-group">
                    <label>Endereço Profissional</label>
                    <textarea name="endereco" rows="2"><?php echo htmlspecialchars($medico['endereco'] ?? ''); ?></textarea>
                </div>
            </div>

            <div class="card">
                <h3>Assinatura Digitalizada</h3>
                <p style="font-size: 14px; color: #666;">Faça o upload de uma imagem contendo apenas a sua assinatura (sem fundo escuro). Tipos aceitos: PNG, JPG, WEBP (Máx. 2MB).</p>
                <div class="form-group">
                    <label>Imagem da Assinatura</label>
                    <input type="file" name="assinatura" accept=".png, .jpg, .jpeg, .webp">
                </div>

                <?php if (!empty($medico['assinatura_path']) && file_exists(__DIR__ . '/../' . $medico['assinatura_path'])): ?>
                <div>
                    <label>Assinatura Atual:</label><br>
                    <img src="../<?php echo htmlspecialchars($medico['assinatura_path']); ?>" alt="Assinatura" class="assinatura-preview">
                </div>
                <?php endif; ?>
            </div>

            <button type="submit" class="btn">Salvar Configurações</button>
        </form>
    </div>
</body>
</html>
