<?php
require_once __DIR__ . '/api/bootstrap.php';

$codigo = trim($_GET['id'] ?? '');
$documento = null;
$tipo = '';
$status_doc = 'NÃO ENCONTRADO';

if (!empty($codigo)) {
    // Busca em atestados
    $stmt = db()->prepare('
        SELECT a.data_emissao, a.status, a.cancelado_em, m.nome_completo as medico_nome, m.crm, m.uf
        FROM atestados a
        JOIN medicos m ON a.medico_id = m.id
        WHERE a.codigo_validacao = ?
    ');
    $stmt->execute([$codigo]);
    $doc = $stmt->fetch();

    if ($doc) {
        $documento = $doc;
        $tipo = 'Atestado Médico';
        $status_doc = $doc['status'];
    } else {
        // Busca em laudos
        $stmt = db()->prepare('
            SELECT l.data_emissao, l.status, l.cancelado_em, m.nome_completo as medico_nome, m.crm, m.uf
            FROM laudos l
            JOIN medicos m ON l.medico_id = m.id
            WHERE l.codigo_validacao = ?
        ');
        $stmt->execute([$codigo]);
        $doc = $stmt->fetch();
        if ($doc) {
            $documento = $doc;
            $tipo = 'Laudo Médico';
            $status_doc = $doc['status'];
        }
    }
}
?>
<!DOCTYPE html>
<html lang="pt-br">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Validação de Documento - Sistema Médico</title>
    <style>
        body { font-family: Arial, sans-serif; background-color: #f4f7f6; margin: 0; padding: 0; display: flex; justify-content: center; align-items: center; min-height: 100vh; }
        .card { background: white; padding: 30px; border-radius: 8px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); width: 100%; max-width: 500px; text-align: center; }
        h1 { margin-top: 0; font-size: 24px; color: #333; }
        .status { padding: 15px; margin: 20px 0; border-radius: 4px; font-weight: bold; font-size: 18px; }
        .status.valido { background: #d4edda; color: #155724; border: 1px solid #c3e6cb; }
        .status.cancelado { background: #f8d7da; color: #721c24; border: 1px solid #f5c6cb; }
        .status.not-found { background: #e2e3e5; color: #383d41; border: 1px solid #d6d8db; }
        .details { text-align: left; background: #f8f9fa; padding: 15px; border-radius: 4px; border: 1px solid #ddd; }
        .details p { margin: 5px 0; font-size: 14px; }
        .footer { margin-top: 20px; font-size: 12px; color: #999; }
    </style>
</head>
<body>
    <div class="card">
        <h1>Validação de Documento</h1>

        <?php if ($status_doc === 'EMITIDO' || $status_doc === 'VÁLIDO'): ?>
            <div class="status valido">DOCUMENTO VÁLIDO</div>
            <div class="details">
                <p><strong>Tipo:</strong> <?php echo htmlspecialchars($tipo); ?></p>
                <p><strong>Código:</strong> <?php echo htmlspecialchars($codigo); ?></p>
                <p><strong>Data de Emissão:</strong> <?php echo date('d/m/Y H:i', strtotime($documento['data_emissao'])); ?></p>
                <p><strong>Médico Responsável:</strong> <?php echo htmlspecialchars($documento['medico_nome']); ?></p>
                <p><strong>CRM:</strong> <?php echo htmlspecialchars($documento['crm'] . '/' . $documento['uf']); ?></p>
            </div>
            <p style="font-size: 13px; color: #666; margin-top: 15px;">Este documento foi emitido e assinado eletronicamente. Por questões de sigilo, dados clínicos do paciente não são exibidos nesta página pública.</p>

        <?php elseif ($status_doc === 'CANCELADO'): ?>
            <div class="status cancelado">DOCUMENTO CANCELADO</div>
            <div class="details">
                <p><strong>Tipo:</strong> <?php echo htmlspecialchars($tipo); ?></p>
                <p><strong>Código:</strong> <?php echo htmlspecialchars($codigo); ?></p>
                <p><strong>Data de Emissão:</strong> <?php echo date('d/m/Y H:i', strtotime($documento['data_emissao'])); ?></p>
                <p><strong>Cancelado em:</strong> <?php echo date('d/m/Y H:i', strtotime($documento['cancelado_em'])); ?></p>
                <p><strong>Médico Responsável:</strong> <?php echo htmlspecialchars($documento['medico_nome']); ?></p>
            </div>
            <p style="font-size: 13px; color: #721c24; margin-top: 15px;">Este documento foi revogado pelo médico emissor e não possui mais validade.</p>

        <?php else: ?>
            <div class="status not-found">DOCUMENTO NÃO ENCONTRADO</div>
            <p style="font-size: 14px; color: #666;">O código informado não consta em nossa base de dados ou é inválido.</p>
        <?php endif; ?>

        <div class="footer">Sistema Médico - Validação Segura</div>
    </div>
</body>
</html>
