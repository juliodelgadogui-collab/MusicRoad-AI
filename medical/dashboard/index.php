<?php
require_once __DIR__ . '/../api/bootstrap.php';
require_login();

$medico = current_medico();

// Simple stats
$stmt = db()->prepare('SELECT COUNT(*) as count FROM pacientes');
$stmt->execute();
$pacientes_count = $stmt->fetch()['count'];

$stmt = db()->prepare('SELECT COUNT(*) as count FROM atendimentos');
$stmt->execute();
$atendimentos_count = $stmt->fetch()['count'];

$stmt = db()->prepare('SELECT COUNT(*) as count FROM atestados');
$stmt->execute();
$atestados_count = $stmt->fetch()['count'];

$stmt = db()->prepare('SELECT COUNT(*) as count FROM laudos');
$stmt->execute();
$laudos_count = $stmt->fetch()['count'];
?>
<!DOCTYPE html>
<html lang="pt-br">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Dashboard - Sistema Médico</title>
    <style>
        body { font-family: Arial, sans-serif; background-color: #f4f7f6; margin: 0; padding: 0; }
        .header { background-color: #0056b3; color: white; padding: 15px 20px; display: flex; justify-content: space-between; align-items: center; }
        .header h1 { margin: 0; font-size: 20px; }
        .nav { background-color: #333; overflow: hidden; }
        .nav a { float: left; display: block; color: white; text-align: center; padding: 14px 16px; text-decoration: none; }
        .nav a:hover { background-color: #ddd; color: black; }
        .container { padding: 20px; }
        .stats { display: flex; gap: 20px; margin-bottom: 20px; }
        .stat-box { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); flex: 1; text-align: center; }
        .stat-box h3 { margin-top: 0; color: #666; font-size: 16px; }
        .stat-box .num { font-size: 24px; font-weight: bold; color: #0056b3; }
    </style>
</head>
<body>
    <div class="header">
        <h1>Sistema Médico</h1>
        <div>
            Bem-vindo, <?php echo htmlspecialchars($medico['nome_completo']); ?>
            <a href="../login/logout.php" style="color: white; margin-left: 15px;">Sair</a>
        </div>
    </div>
    <div class="nav">
        <a href="index.php">Dashboard</a>
        <a href="../pacientes/index.php">Pacientes</a>
        <a href="../atendimentos/index.php">Atendimentos</a>
        <a href="../atestados/index.php">Atestados</a>
        <a href="../laudos/index.php">Laudos</a>
    </div>
    <div class="container">
        <h2>Visão Geral</h2>
        <div class="stats">
            <div class="stat-box">
                <h3>Pacientes</h3>
                <div class="num"><?php echo $pacientes_count; ?></div>
            </div>
            <div class="stat-box">
                <h3>Atendimentos</h3>
                <div class="num"><?php echo $atendimentos_count; ?></div>
            </div>
            <div class="stat-box">
                <h3>Atestados</h3>
                <div class="num"><?php echo $atestados_count; ?></div>
            </div>
            <div class="stat-box">
                <h3>Laudos</h3>
                <div class="num"><?php echo $laudos_count; ?></div>
            </div>
        </div>
    </div>
</body>
</html>
