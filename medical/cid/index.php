<?php
require_once __DIR__ . '/../api/bootstrap.php';
require_login();
$medico = current_medico();
?>
<!DOCTYPE html>
<html lang="pt-br">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Consulta de CID - Sistema Médico</title>
    <style>
        body { font-family: Arial, sans-serif; background-color: #f4f7f6; margin: 0; padding: 0; }
        .header { background-color: #0056b3; color: white; padding: 15px 20px; display: flex; justify-content: space-between; align-items: center; }
        .header h1 { margin: 0; font-size: 20px; }
        .nav { background-color: #333; overflow: hidden; }
        .nav a { float: left; display: block; color: white; text-align: center; padding: 14px 16px; text-decoration: none; }
        .nav a:hover { background-color: #ddd; color: black; }
        .container { padding: 20px; max-width: 800px; margin: auto; }
        .card { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        input[type="text"] { width: 100%; padding: 10px; margin-bottom: 15px; border: 1px solid #ccc; border-radius: 4px; box-sizing: border-box; font-size: 16px; }
        ul#results { list-style-type: none; padding: 0; margin: 0; }
        ul#results li { padding: 12px; border-bottom: 1px solid #eee; }
        ul#results li:last-child { border-bottom: none; }
        .codigo { font-weight: bold; color: #0056b3; margin-right: 10px; }
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
        <a href="../laudos/index.php">Laudos</a>
        <a href="index.php" style="background-color: #ddd; color: black;">CID</a>
    </div>
    <div class="container">
        <h2>Consulta de CID-10</h2>
        <p>A pesquisa retornará os códigos ou descrições relacionados na base ativa.</p>
        <div class="card">
            <input type="text" id="cid_search" placeholder="Digite o código (ex: J01) ou nome da doença...">
            <ul id="results"></ul>
        </div>
    </div>

    <script>
        const input = document.getElementById('cid_search');
        const results = document.getElementById('results');

        let timeout = null;
        input.addEventListener('input', function() {
            clearTimeout(timeout);
            const query = this.value.trim();

            if (query.length < 2) {
                results.innerHTML = '<li>Digite pelo menos 2 caracteres...</li>';
                return;
            }

            results.innerHTML = '<li>Buscando...</li>';

            timeout = setTimeout(() => {
                fetch('search.php?q=' + encodeURIComponent(query))
                    .then(r => r.json())
                    .then(data => {
                        results.innerHTML = '';
                        if (data.length === 0) {
                            results.innerHTML = '<li>Nenhum resultado encontrado.</li>';
                            return;
                        }
                        data.forEach(item => {
                            const li = document.createElement('li');
                            li.innerHTML = `<span class="codigo">${item.codigo}</span> ${item.descricao}`;
                            results.appendChild(li);
                        });
                    })
                    .catch(err => {
                        results.innerHTML = '<li>Erro ao buscar CIDs.</li>';
                    });
            }, 300);
        });
    </script>
</body>
</html>
