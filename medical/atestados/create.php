<?php
require_once __DIR__ . '/../api/bootstrap.php';
require_login();

$medico = current_medico();
$error = '';

$stmt = db()->query('SELECT id, nome FROM pacientes ORDER BY nome ASC');
$pacientes = $stmt->fetchAll();

$atendimento_id = $_GET['atendimento_id'] ?? null;
$pre_paciente_id = '';
if ($atendimento_id) {
    $stmt = db()->prepare('SELECT paciente_id FROM atendimentos WHERE id = ?');
    $stmt->execute([$atendimento_id]);
    $at = $stmt->fetch();
    if ($at) {
        $pre_paciente_id = $at['paciente_id'];
    }
}

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $paciente_id = (int)$_POST['paciente_id'];
    $cid_codigo = $_POST['cid_codigo'] ?? '';
    $cid_descricao = $_POST['cid_descricao'] ?? '';
    $periodo_afastamento = $_POST['periodo_afastamento'] ?? '';
    $info_clinica = $_POST['info_clinica'] ?? '';
    $observacoes = $_POST['observacoes'] ?? '';
    $assinatura_confirmada = isset($_POST['confirmar_assinatura']) ? true : false;

    if (empty($paciente_id) || empty($periodo_afastamento)) {
        $error = 'Paciente e Período de Afastamento são obrigatórios.';
    } elseif (!$assinatura_confirmada) {
        $error = 'Você deve confirmar a revisão e autorizar a emissão do documento.';
    } else {
        $codigo_validacao = 'MED-' . date('Y') . '-' . strtoupper(bin2hex(random_bytes(4)));
        $data_emissao = date('Y-m-d H:i:s');

        $stmt = db()->prepare('INSERT INTO atestados (paciente_id, medico_id, atendimento_id, codigo_validacao, data_emissao, cid_codigo, cid_descricao, periodo_afastamento, info_clinica, observacoes, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)');
        $stmt->execute([
            $paciente_id, $medico['id'], $atendimento_id, $codigo_validacao, $data_emissao,
            $cid_codigo, $cid_descricao, $periodo_afastamento, $info_clinica, $observacoes, 'EMITIDO'
        ]);

        $id = db()->lastInsertId();
        audit_log('emitir_atestado', 'atestados', $id, ['codigo' => $codigo_validacao]);
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
    <title>Novo Atestado - Sistema Médico</title>
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
        select, input[type="text"], textarea { width: 100%; padding: 8px; border: 1px solid #ccc; border-radius: 4px; box-sizing: border-box; }
        .btn { padding: 10px 15px; background: #0056b3; color: white; border: none; border-radius: 4px; cursor: pointer; font-size: 16px; width: 100%; }
        .btn:hover { background: #004494; }
        .error { color: red; margin-bottom: 15px; }
        .card { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); margin-bottom: 20px; }
        .assinatura-box { background: #fff3cd; border: 1px solid #ffeeba; padding: 15px; border-radius: 4px; }
        .assinatura-box label { display: inline; font-weight: normal; margin-left: 5px; }
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
        <a href="index.php">Atestados</a>
        <a href="../laudos/index.php">Laudos</a>
    </div>
    <div class="container">
        <h2>Emitir Novo Atestado</h2>

        <?php if ($error): ?>
            <div class="error"><?php echo htmlspecialchars($error); ?></div>
        <?php endif; ?>

        <form method="POST">
            <div class="card">
                <div class="form-group">
                    <label>Paciente *</label>
                    <select name="paciente_id" required>
                        <option value="">Selecione o paciente</option>
                        <?php foreach ($pacientes as $p): ?>
                        <option value="<?php echo $p['id']; ?>" <?php echo $p['id'] == $pre_paciente_id ? 'selected' : ''; ?>>
                            <?php echo htmlspecialchars($p['nome']); ?>
                        </option>
                        <?php endforeach; ?>
                    </select>
                </div>

                <div style="display: flex; gap: 15px;">
                    <div class="form-group" style="flex: 1;">
                        <label>CID (Código)</label>
                        <input type="text" name="cid_codigo" placeholder="Ex: J01.9">
                    </div>
                    <div class="form-group" style="flex: 2;">
                        <label>Descrição do Diagnóstico</label>
                        <input type="text" name="cid_descricao">
                    </div>
                </div>

                <div class="form-group">
                    <label>Período de Afastamento *</label>
                    <input type="text" name="periodo_afastamento" placeholder="Ex: 5 (cinco) dias a partir de DD/MM/AAAA" required>
                </div>

                <div class="form-group">
                    <label>Informações Clínicas Pertinentes</label>
                    <textarea name="info_clinica" rows="3"></textarea>
                </div>

                <div class="form-group">
                    <label>Observações</label>
                    <textarea name="observacoes" rows="2"></textarea>
                </div>
            </div>

            <div class="card assinatura-box">
                <h3 style="margin-top: 0;">CONFIRMAR EMISSÃO</h3>
                <p>
                    <strong>Médico:</strong> <?php echo htmlspecialchars($medico['nome_completo']); ?><br>
                    <strong>CRM:</strong> <?php echo htmlspecialchars($medico['crm'] . '/' . $medico['uf']); ?>
                </p>
                <div>
                    <input type="checkbox" id="confirmar_assinatura" name="confirmar_assinatura" value="1">
                    <label for="confirmar_assinatura">Confirmo que revisei o documento e autorizo a emissão com minha assinatura.</label>
                </div>
            </div>

            <button type="submit" class="btn">ASSINAR E EMITIR ATESTADO</button>
            <div style="text-align: center; margin-top: 15px;">
                <a href="index.php" style="color: #666; text-decoration: none;">Cancelar</a>
            </div>
        </form>
    </div>
</body>
</html>
