<?php
require __DIR__ . '/api/bootstrap.php';
ensure_default_users();
$user = current_user();
if (!$user) { header('Location: login.php'); exit; }
header('Location: ' . (($user['role'] ?? '') === 'admin' ? 'admin_central.php?p=dashboard' : 'client.php'));
exit;
