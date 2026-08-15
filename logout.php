<?php
require __DIR__ . '/api/bootstrap.php';
audit_log('auth.logout', ['user_id' => $_SESSION['user_id'] ?? null]);
$_SESSION = [];
session_destroy();
header('Location: login.php');
