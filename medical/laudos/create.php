<?php
require_once __DIR__ . '/../api/bootstrap.php';
require_login();

$medico = current_medico();
$error = '';

$stmt = db()->query('SELECT id, nome FROM pacientes ORDER BY nome ASC');
$pacientes = $stmt->fetchAll();

$atendimento_id = $_GET['atendimento_id'] ?? null;
$pre_paciente_id = '';
$pre_historico = '';
$pre_avaliacao = '';

if ($atendimento_id) {
    $stmt = db()->prepare('SELECT * FROM atendimentos WHERE id = ?');
    $stmt->execute([$atendimento_id]);
    $at = $stmt->fetch();
    if ($at) {
        $pre_paciente_id = $at['paciente_id'];
        $pre_historico = $at['queixa_principal'] . "\n" . $at['historia_clinica'];
        $pre_avaliacao = "Exame Físico:\n" . $at['exame_fisico'] . "\n\nSintomas:\n" . $at['sintomas'];
    }
}

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $paciente_id = (int)$_POST['paciente_id'];
    $historico_clinico = $_POST['historico_clinico'] ?? '';
    $avaliacao = $_POST['avaliacao'] ?? '';
    $exames = $_POST['exames'] ?? '';
    $cid_codigo = $_POST['cid_codigo'] ?? '';
    $cid_descricao = $_POST['cid_descricao'] ?? '';
    $repercussao = $_POST['repercussao'] ?? '';
    $conclusao = $_POST['conclusao'] ?? '';
    $assinatura_confirmada = isset($_POST['confirmar_assinatura']) ? true : false;

    // Checklist verification
    $checklist = isset($_POST['checklist']) ? $_POST['checklist'] : [];

    if (empty($paciente_id) || empty($conclusao)) {
        $error = 'Paciente e Conclusão são obrigatórios.';
    } elseif (count($checklist) < 4) {
        $error = 'Você deve confirmar todos os itens do checklist antes de prosseguir.';
    } elseif (!$assinatura_confirmada) {
        $error = 'Você deve confirmar a revisão e autorizar a emissão do documento.';
    } else {
        $codigo_validacao = 'LAUD-' . date('Y') . '-' . strtoupper(bin2hex(random_bytes(4)));
        $data_emissao = date('Y-m-d H:i:s');

        $stmt = db()->prepare('INSERT INTO laudos (paciente_id, medico_id, atendimento_id, codigo_validacao, data_emissao, historico_clinico, avaliacao, exames, cid_codigo, cid_descricao, repercussao, conclusao, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)');
        $stmt->execute([
            $paciente_id, $medico['id'], $atendimento_id, $codigo_validacao, $data_emissao,
            $historico_clinico, $avaliacao, $exames, $cid_codigo, $cid_descricao, $repercussao, $conclusao, 'EMITIDO'
        ]);

        $id = db()->lastInsertId();
        audit_log('emitir_laudo', 'laudos', $id, ['codigo' => $codigo_validacao]);
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
    <title>Novo Laudo - Sistema Médico</title>
    <style>
        body { font-family: Arial, sans-serif; background-color: #f4f7f6; margin: 0; padding: 0; }
        .header { background-color: #0056b3; color: white; padding: 15px 20px; display: flex; justify-content: space-between; align-items: center; }
        .header h1 { margin: 0; font-size: 20px; }
        .nav { background-color: #333; overflow: hidden; }
        .nav a { float: left; display: block; color: white; text-align: center; padding: 14px 16px; text-decoration: none; }
        .nav a:hover { background-color: #ddd; color: black; }
        .container { padding: 20px; max-width: 900px; margin: auto; }
        .form-group { margin-bottom: 15px; }
        label { display: block; margin-bottom: 5px; font-weight: bold; }
        select, input[type="text"], textarea { width: 100%; padding: 8px; border: 1px solid #ccc; border-radius: 4px; box-sizing: border-box; }
        .btn { padding: 10px 15px; background: #0056b3; color: white; border: none; border-radius: 4px; cursor: pointer; font-size: 16px; width: 100%; }
        .btn:hover { background: #004494; }
        .error { color: red; margin-bottom: 15px; }
        .card { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); margin-bottom: 20px; }
        .assinatura-box { background: #fff3cd; border: 1px solid #ffeeba; padding: 15px; border-radius: 4px; }
        .assinatura-box label { display: inline; font-weight: normal; margin-left: 5px; }
        .checklist-box { background: #e9ecef; padding: 15px; border-radius: 4px; margin-bottom: 20px; }
        .checklist-box h4 { margin-top: 0; }
        .checklist-item { margin-bottom: 8px; }
        .checklist-item label { display: inline; font-weight: normal; margin-left: 5px; }
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
        <a href="index.php">Laudos</a>
    </div>
    <div class="container">
        <h2>Emitir Novo Laudo</h2>

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

                <div class="form-group">
                    <label>Histórico Clínico</label>
                    <textarea name="historico_clinico" rows="4"><?php echo htmlspecialchars($pre_historico); ?></textarea>
                </div>

                <div class="form-group">
                    <label>Avaliação</label>
                    <textarea name="avaliacao" rows="4"><?php echo htmlspecialchars($pre_avaliacao); ?></textarea>
                </div>

                <div class="form-group">
                    <label>Exames</label>
                    <textarea name="exames" rows="3"></textarea>
                </div>

                <div style="display: flex; gap: 15px;">
                    <div class="form-group" style="flex: 1;">
                        <label>CID (Código)</label>
                        <input type="text" name="cid_codigo">
                    </div>
                    <div class="form-group" style="flex: 2;">
                        <label>Descrição do Diagnóstico</label>
                        <input type="text" name="cid_descricao">
                    </div>
                </div>

                <div class="form-group">
                    <label>Repercussão Funcional (Limitações / Impactos)</label>
                    <textarea name="repercussao" rows="4"></textarea>
                </div>

                <div class="form-group">
                    <label>Conclusão *</label>
                    <textarea name="conclusao" rows="4" required></textarea>
                </div>
            </div>

            <div class="checklist-box">
                <h4>CHECKLIST - INCAPACIDADE / PREVIDÊNCIA</h4>
                <div class="checklist-item">
                    <input type="checkbox" name="checklist[]" id="chk1" value="1">
                    <label for="chk1">Paciente devidamente identificado (documentos e dados conferem).</label>
                </div>
                <div class="checklist-item">
                    <input type="checkbox" name="checklist[]" id="chk2" value="2">
                    <label for="chk2">Diagnóstico e CID descritos com clareza.</label>
                </div>
                <div class="checklist-item">
                    <input type="checkbox" name="checklist[]" id="chk3" value="3">
                    <label for="chk3">Informações clínicas e repercussões funcionais estão devidamente embasadas.</label>
                </div>
                <div class="checklist-item">
                    <input type="checkbox" name="checklist[]" id="chk4" value="4">
                    <label for="chk4">O documento não afirma garantias de concessão de benefício por terceiros (ex: INSS).</label>
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
                    <label for="confirmar_assinatura">Confirmo que revisei o documento, preenchi o checklist e autorizo a emissão com minha assinatura.</label>
                </div>
            </div>

            <button type="submit" class="btn">ASSINAR E EMITIR LAUDO</button>
            <div style="text-align: center; margin-top: 15px;">
                <a href="index.php" style="color: #666; text-decoration: none;">Cancelar</a>
            </div>
        </form>
    </div>
</body>
</html>
