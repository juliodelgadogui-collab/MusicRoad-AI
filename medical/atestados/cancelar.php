<?php
require_once __DIR__ . '/../api/bootstrap.php';
require_login();

$medico = current_medico();

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    require_csrf();
    $id = (int)$_POST['id'];
    $motivo = trim($_POST['motivo'] ?? '');

    if (empty($motivo)) {
        die('Motivo é obrigatório.');
    }

    $stmt = db()->prepare('UPDATE atestados SET status = ?, cancelado_em = ?, cancelado_motivo = ? WHERE id = ? AND medico_id = ?');
    $stmt->execute(['CANCELADO', date('Y-m-d H:i:s'), $motivo, $id, $medico['id']]);

    audit_log('cancelar_atestado', 'atestados', $id, ['motivo' => $motivo]);

    header('Location: index.php');
    die();
}
