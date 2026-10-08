<?php
require_once __DIR__ . '/../api/bootstrap.php';
require_login();

$medico = current_medico();

$stmt = db()->prepare('
    SELECT a.*, p.nome as paciente_nome
    FROM atestados a
    JOIN pacientes p ON a.paciente_id = p.id
    WHERE a.medico_id = ?
    ORDER BY a.data_emissao DESC
');
$stmt->execute([$medico['id']]);
$atestados = $stmt->fetchAll();
?>
<!DOCTYPE html>
<html lang="pt-br">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Atestados - Sistema Médico</title>
    <style>
        body { font-family: Arial, sans-serif; background-color: #f4f7f6; margin: 0; padding: 0; }
        .header { background-color: #0056b3; color: white; padding: 15px 20px; display: flex; justify-content: space-between; align-items: center; }
        .header h1 { margin: 0; font-size: 20px; }
        .nav { background-color: #333; overflow: hidden; }
        .nav a { float: left; display: block; color: white; text-align: center; padding: 14px 16px; text-decoration: none; }
        .nav a:hover { background-color: #ddd; color: black; }
        .container { padding: 20px; }
        .btn { display: inline-block; padding: 10px 15px; background: #28a745; color: white; text-decoration: none; border-radius: 4px; margin-bottom: 15px; }
        .btn:hover { background: #218838; }
        table { width: 100%; border-collapse: collapse; background: white; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        th, td { padding: 12px 15px; text-align: left; border-bottom: 1px solid #ddd; }
        th { background-color: #f8f9fa; }
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
        <h2>Atestados</h2>
        <a href="create.php" class="btn">Novo Atestado</a>
        <table>
            <thead>
                <tr>
                    <th>Data de Emissão</th>
                    <th>Paciente</th>
                    <th>CID</th>
                    <th>Período</th>
                    <th>Ações</th>
                </tr>
            </thead>
            <tbody>
                <?php foreach ($atestados as $a): ?>
                <tr>
                    <td><?php echo date('d/m/Y H:i', strtotime($a['data_emissao'])); ?></td>
                    <td><?php echo htmlspecialchars($a['paciente_nome']); ?></td>
                    <td><?php echo htmlspecialchars($a['cid_codigo']); ?></td>
                    <td><?php echo htmlspecialchars($a['periodo_afastamento']); ?></td>
                    <td>
                        <a href="view.php?id=<?php echo $a['id']; ?>" target="_blank">Visualizar PDF</a> | <form method="POST" action="cancelar.php" style="display:inline;" onsubmit="return confirm('Confirmar cancelamento?');"><input type="hidden" name="csrf" value="<?php echo htmlspecialchars(csrf_token()); ?>"><input type="hidden" name="id" value="<?php echo $a['id']; ?>"><input type="hidden" name="motivo" value="Cancelado pelo emissor"><button type="submit" style="background:none;border:none;color:red;cursor:pointer;text-decoration:underline;">Cancelar</button></form>
                    </td>
                </tr>
                <?php endforeach; ?>
                <?php if (count($atestados) === 0): ?>
                <tr>
                    <td colspan="5" style="text-align: center;">Nenhum atestado gerado.</td>
                </tr>
                <?php endif; ?>
            </tbody>
        </table>
    </div>
</body>
</html>
