<?php
require_once __DIR__ . '/../api/bootstrap.php';

if (current_medico()) {
    audit_log('logout', 'medicos', $_SESSION['medico_id'], ['ip' => $_SERVER['REMOTE_ADDR']]);
}

session_destroy();
header('Location: index.php');
die();
