<?php
require_once __DIR__ . '/../api/bootstrap.php';
require_login();

$medico = current_medico();
$error = '';

$stmt = db()->prepare('SELECT id, nome FROM pacientes WHERE medico_id = ? ORDER BY nome ASC');
$stmt->execute([$medico['id']]);
$pacientes = $stmt->fetchAll();

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $paciente_id = (int)$_POST['paciente_id'];
    $data_atendimento = date('Y-m-d H:i:s');
    $queixa_principal = $_POST['queixa_principal'] ?? '';
    $historia_clinica = $_POST['historia_clinica'] ?? '';
    $sintomas = $_POST['sintomas'] ?? '';
    $duracao = $_POST['duracao'] ?? '';
    $evolucao = $_POST['evolucao'] ?? '';
    $medicamentos = $_POST['medicamentos'] ?? '';
    $alergias = $_POST['alergias'] ?? '';
    $exame_fisico = $_POST['exame_fisico'] ?? '';
    $exames_apresentados = $_POST['exames_apresentados'] ?? '';
    $tratamento = $_POST['tratamento'] ?? '';
    $observacoes = $_POST['observacoes'] ?? '';

    // Capacidade funcional
    $capacidade_limitacoes = $_POST['capacidade_limitacoes'] ?? '';
    $capacidade_restricoes = $_POST['capacidade_restricoes'] ?? '';
    $capacidade_atividades = $_POST['capacidade_atividades'] ?? '';
    $capacidade_impacto = $_POST['capacidade_impacto'] ?? '';
    $capacidade_obs = $_POST['capacidade_obs'] ?? '';

    if (empty($paciente_id) || empty($queixa_principal)) {
        $error = 'Paciente e Queixa Principal são obrigatórios.';
    } else {
        $stmt = db()->prepare('INSERT INTO atendimentos (paciente_id, medico_id, data_atendimento, queixa_principal, historia_clinica, sintomas, duracao, evolucao, medicamentos, alergias, exame_fisico, exames_apresentados, tratamento, observacoes, capacidade_limitacoes, capacidade_restricoes, capacidade_atividades, capacidade_impacto, capacidade_obs) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)');
        $stmt->execute([
            $paciente_id, $medico['id'], $data_atendimento, $queixa_principal, $historia_clinica,
            $sintomas, $duracao, $evolucao, $medicamentos, $alergias, $exame_fisico,
            $exames_apresentados, $tratamento, $observacoes, $capacidade_limitacoes,
            $capacidade_restricoes, $capacidade_atividades, $capacidade_impacto, $capacidade_obs
        ]);

        $id = db()->lastInsertId();
        audit_log('criar_atendimento', 'atendimentos', $id);
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
    <title>Novo Atendimento - Sistema Médico</title>
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
        .btn { padding: 10px 15px; background: #0056b3; color: white; border: none; border-radius: 4px; cursor: pointer; }
        .btn:hover { background: #004494; }
        .error { color: red; margin-bottom: 15px; }
        .card { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); margin-bottom: 20px; }
        h3 { margin-top: 0; border-bottom: 1px solid #eee; padding-bottom: 10px; color: #444; }
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
        <a href="index.php">Atendimentos</a>
        <a href="../atestados/index.php">Atestados</a>
        <a href="../laudos/index.php">Laudos</a>
    </div>
    <div class="container">
        <h2>Novo Atendimento</h2>

        <?php if ($error): ?>
            <div class="error"><?php echo htmlspecialchars($error); ?></div>
        <?php endif; ?>

        <form method="POST">
            <div class="card">
                <h3>Informações Gerais</h3>
                <div class="form-group">
                    <label>Paciente *</label>
                    <select name="paciente_id" required>
                        <option value="">Selecione o paciente</option>
                        <?php foreach ($pacientes as $p): ?>
                        <option value="<?php echo $p['id']; ?>"><?php echo htmlspecialchars($p['nome']); ?></option>
                        <?php endforeach; ?>
                    </select>
                </div>
                <div class="form-group">
                    <label>Queixa Principal *</label>
                    <input type="text" name="queixa_principal" required>
                </div>
            </div>

            <div class="card">
                <h3>Anamnese e Exame Físico</h3>
                <div class="form-group">
                    <label>História Clínica</label>
                    <textarea name="historia_clinica" rows="3"></textarea>
                </div>
                <div style="display: flex; gap: 15px;">
                    <div class="form-group" style="flex: 1;">
                        <label>Sintomas</label>
                        <textarea name="sintomas" rows="2"></textarea>
                    </div>
                    <div class="form-group" style="flex: 1;">
                        <label>Duração</label>
                        <input type="text" name="duracao">
                    </div>
                    <div class="form-group" style="flex: 1;">
                        <label>Evolução</label>
                        <input type="text" name="evolucao">
                    </div>
                </div>
                <div style="display: flex; gap: 15px;">
                    <div class="form-group" style="flex: 1;">
                        <label>Medicamentos em uso</label>
                        <textarea name="medicamentos" rows="2"></textarea>
                    </div>
                    <div class="form-group" style="flex: 1;">
                        <label>Alergias</label>
                        <textarea name="alergias" rows="2"></textarea>
                    </div>
                </div>
                <div class="form-group">
                    <label>Exame Físico</label>
                    <textarea name="exame_fisico" rows="3"></textarea>
                </div>
                <div class="form-group">
                    <label>Exames Apresentados</label>
                    <textarea name="exames_apresentados" rows="2"></textarea>
                </div>
                <div class="form-group">
                    <label>Tratamento / Conduta</label>
                    <textarea name="tratamento" rows="3"></textarea>
                </div>
                <div class="form-group">
                    <label>Observações</label>
                    <textarea name="observacoes" rows="2"></textarea>
                </div>
            </div>

            <div class="card">
                <h3>Capacidade Funcional (Opcional - Foco em Incapacidade)</h3>
                <div style="display: flex; gap: 15px;">
                    <div class="form-group" style="flex: 1;">
                        <label>Limitações</label>
                        <textarea name="capacidade_limitacoes" rows="2"></textarea>
                    </div>
                    <div class="form-group" style="flex: 1;">
                        <label>Restrições</label>
                        <textarea name="capacidade_restricoes" rows="2"></textarea>
                    </div>
                </div>
                <div style="display: flex; gap: 15px;">
                    <div class="form-group" style="flex: 1;">
                        <label>Atividades Afetadas</label>
                        <textarea name="capacidade_atividades" rows="2"></textarea>
                    </div>
                    <div class="form-group" style="flex: 1;">
                        <label>Impacto na Atividade Habitual</label>
                        <textarea name="capacidade_impacto" rows="2"></textarea>
                    </div>
                </div>
                <div class="form-group">
                    <label>Observações sobre Capacidade Laboral</label>
                    <textarea name="capacidade_obs" rows="2"></textarea>
                </div>
            </div>

            <button type="submit" class="btn">Salvar Atendimento</button>
            <a href="index.php" style="margin-left: 10px; color: #666;">Cancelar</a>
        </form>
    </div>
</body>
</html>
