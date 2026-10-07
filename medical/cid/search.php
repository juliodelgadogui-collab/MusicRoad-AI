<?php
require_once __DIR__ . '/../api/bootstrap.php';
require_login();

header('Content-Type: application/json; charset=utf-8');

$query = trim($_GET['q'] ?? '');

if (strlen($query) < 2) {
    echo json_encode([]);
    die();
}

$stmt = db()->prepare('SELECT codigo, descricao FROM cids WHERE codigo LIKE ? OR descricao LIKE ? LIMIT 50');
$like_q = "%$query%";
$stmt->execute([$like_q, $like_q]);

$results = $stmt->fetchAll();
echo json_encode($results);
