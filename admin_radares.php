<?php
require __DIR__ . '/api/bootstrap.php';
$user = require_admin();
db()->exec(file_get_contents(__DIR__ . '/database/schema.sql'));

$message = '';
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    require_csrf();
    $stmt = db()->prepare('INSERT INTO radars (external_id, latitude, longitude, uf, cidade, rodovia, km, sentido, heading, velocidade, tipo, situacao, fonte, data_fonte, data_importacao, confiabilidade, quantidade_fontes, ativo) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime("now"), ?, ?, ?)');
    $stmt->execute([
        $_POST['external_id'] ?: null,
        (float)$_POST['latitude'],
        (float)$_POST['longitude'],
        $_POST['uf'] ?: null,
        $_POST['cidade'] ?: null,
        $_POST['rodovia'] ?: null,
        $_POST['km'] ?: null,
        $_POST['sentido'] ?: null,
        $_POST['heading'] !== '' ? (float)$_POST['heading'] : null,
        $_POST['velocidade'] !== '' ? (int)$_POST['velocidade'] : null,
        $_POST['tipo'] ?: 'RADAR_FIXO',
        $_POST['situacao'] ?: 'ATIVO',
        $_POST['fonte'] ?: 'ADMIN',
        $_POST['data_fonte'] ?: null,
        $_POST['confiabilidade'] ?: 'BAIXA',
        $_POST['quantidade_fontes'] !== '' ? (int)$_POST['quantidade_fontes'] : 1,
        isset($_POST['ativo']) ? 1 : 0,
    ]);
    audit_log('radars.admin_create', ['id' => db()->lastInsertId()]);
    $message = 'Radar cadastrado com sucesso.';
}

$summary = [
    'Total' => (int)db()->query('SELECT COUNT(*) FROM radars')->fetchColumn(),
    'Ativos' => (int)db()->query('SELECT COUNT(*) FROM radars WHERE ativo = 1')->fetchColumn(),
    'Inativos' => (int)db()->query('SELECT COUNT(*) FROM radars WHERE ativo = 0')->fetchColumn(),
    'Sem velocidade' => (int)db()->query('SELECT COUNT(*) FROM radars WHERE velocidade IS NULL')->fetchColumn(),
    'Sem sentido' => (int)db()->query('SELECT COUNT(*) FROM radars WHERE sentido IS NULL OR sentido = ""')->fetchColumn(),
];
$radars = db()->query('SELECT * FROM radars ORDER BY id DESC LIMIT 200')->fetchAll();
?>
<!doctype html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Central de Radares - MusicRoad AI</title>
  <link rel="stylesheet" href="assets/css/app.css">
</head>
<body>
<main>
  <div class="toolbar">
    <h1>Central de Radares</h1>
    <div class="row"><a class="button secondary" href="admin.php">Painel Admin</a><a class="button secondary" href="index.php">Abrir App</a><a class="button secondary" href="logout.php">Sair</a></div>
  </div>
  <div class="grid">
    <?php foreach ($summary as $label => $value): ?>
      <article class="panel"><h2><?= htmlspecialchars($label) ?></h2><p class="big-number"><?= (int)$value ?></p></article>
    <?php endforeach; ?>
  </div>
  <?php if ($message): ?><div class="panel success"><?= htmlspecialchars($message) ?></div><?php endif; ?>
  <section class="panel">
    <h2>Adicionar radar</h2>
    <form method="post" class="form-grid">
      <input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>">
      <label>Latitude<input name="latitude" required placeholder="-21.123456"></label>
      <label>Longitude<input name="longitude" required placeholder="-41.123456"></label>
      <label>UF<input name="uf" maxlength="2" placeholder="RJ"></label>
      <label>Cidade<input name="cidade" placeholder="Bom Jesus do Itabapoana"></label>
      <label>Rodovia<input name="rodovia" placeholder="BR-101"></label>
      <label>KM<input name="km" placeholder="153"></label>
      <label>Sentido<input name="sentido" placeholder="Norte / Sul"></label>
      <label>Direção heading<input name="heading" type="number" step="0.1"></label>
      <label>Velocidade<input name="velocidade" type="number" placeholder="60"></label>
      <label>Tipo<select name="tipo"><option>RADAR_FIXO</option><option>FISCALIZACAO</option><option>ACIDENTE</option><option>OBRA</option><option>BURACO</option></select></label>
      <label>Situação<select name="situacao"><option>ATIVO</option><option>INATIVO</option><option>AGUARDANDO_VALIDACAO</option></select></label>
      <label>Fonte<input name="fonte" value="ADMIN"></label>
      <label>Data da fonte<input name="data_fonte" type="date"></label>
      <label>Confiabilidade<select name="confiabilidade"><option>BAIXA</option><option>MÉDIA</option><option>ALTA</option><option>CONFIRMADA</option></select></label>
      <label>Quantidade de fontes<input name="quantidade_fontes" type="number" value="1"></label>
      <label>External ID<input name="external_id"></label>
      <label class="check"><input name="ativo" type="checkbox" checked> Ativo</label>
      <button type="submit">Salvar radar</button>
    </form>
  </section>
  <section class="panel">
    <h2>Últimos radares</h2>
    <div class="table-wrap">
      <table>
        <thead><tr><th>ID</th><th>UF</th><th>Cidade</th><th>Rodovia</th><th>KM</th><th>Vel.</th><th>Fonte</th><th>Conf.</th><th>Status</th></tr></thead>
        <tbody>
          <?php foreach ($radars as $r): ?>
            <tr><td><?= (int)$r['id'] ?></td><td><?= htmlspecialchars((string)$r['uf']) ?></td><td><?= htmlspecialchars((string)$r['cidade']) ?></td><td><?= htmlspecialchars((string)$r['rodovia']) ?></td><td><?= htmlspecialchars((string)$r['km']) ?></td><td><?= htmlspecialchars((string)$r['velocidade']) ?></td><td><?= htmlspecialchars((string)$r['fonte']) ?></td><td><?= htmlspecialchars((string)$r['confiabilidade']) ?></td><td><?= $r['ativo'] ? 'Ativo' : 'Inativo' ?></td></tr>
          <?php endforeach; ?>
        </tbody>
      </table>
    </div>
  </section>
</main>
</body>
</html>
