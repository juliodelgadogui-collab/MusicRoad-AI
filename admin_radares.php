<?php
declare(strict_types=1);
require __DIR__ . '/api/bootstrap.php';
$user = require_admin();
require __DIR__ . '/api/radar_db.php';
radar_ensure_tables();

$message='';
$error='';

if($_SERVER['REQUEST_METHOD']==='POST'){
    require_csrf();
    $action=(string)($_POST['action']??'create');
    try{
        if($action==='toggle'){
            $id=(int)($_POST['id']??0);
            $ativo=(int)($_POST['ativo']??0)===1?1:0;
            if($id<=0)throw new RuntimeException('Radar inválido.');
            $stmt=db()->prepare('UPDATE radars SET ativo=? WHERE id=?');
            $stmt->execute([$ativo,$id]);
            audit_log('radars.admin_toggle',['id'=>$id,'ativo'=>$ativo]);
            $message=$ativo?'Radar ativado.':'Radar desativado.';
        }elseif($action==='delete'){
            $id=(int)($_POST['id']??0);
            if($id<=0)throw new RuntimeException('Radar inválido.');
            db()->beginTransaction();
            try{
                $s=db()->prepare('DELETE FROM radar_sources WHERE radar_id=?');$s->execute([$id]);
                $s=db()->prepare('DELETE FROM radars WHERE id=?');$s->execute([$id]);
                db()->commit();
            }catch(Throwable $e){if(db()->inTransaction())db()->rollBack();throw $e;}
            audit_log('radars.admin_delete',['id'=>$id]);
            $message='Radar removido.';
        }else{
            $latRaw=trim((string)($_POST['latitude']??''));
            $lonRaw=trim((string)($_POST['longitude']??''));
            if(!is_numeric($latRaw)||!is_numeric($lonRaw))throw new RuntimeException('Latitude e longitude são obrigatórias.');
            $lat=(float)$latRaw;$lon=(float)$lonRaw;
            if(abs($lat)>90||abs($lon)>180)throw new RuntimeException('Coordenadas inválidas.');
            $uf=radar_normalize_uf($_POST['uf']??null,$lat,$lon);
            $stmt=db()->prepare('INSERT INTO radars (external_id, latitude, longitude, uf, cidade, rodovia, km, sentido, heading, velocidade, tipo, situacao, fonte, data_fonte, data_importacao, confiabilidade, quantidade_fontes, ativo) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)');
            $stmt->execute([
                trim((string)($_POST['external_id']??''))!==''?trim((string)$_POST['external_id']):null,
                $lat,$lon,$uf,
                trim((string)($_POST['cidade']??''))?:null,
                trim((string)($_POST['rodovia']??''))?:null,
                trim((string)($_POST['km']??''))?:null,
                trim((string)($_POST['sentido']??''))?:null,
                trim((string)($_POST['heading']??''))!==''?(float)$_POST['heading']:null,
                trim((string)($_POST['velocidade']??''))!==''?(int)$_POST['velocidade']:null,
                trim((string)($_POST['tipo']??''))?:'RADAR_FIXO',
                trim((string)($_POST['situacao']??''))?:'ATIVO',
                trim((string)($_POST['fonte']??''))?:'ADMIN',
                trim((string)($_POST['data_fonte']??''))?:null,
                radar_now(),
                trim((string)($_POST['confiabilidade']??''))?:'BAIXA',
                max(1,(int)($_POST['quantidade_fontes']??1)),
                isset($_POST['ativo'])?1:0,
            ]);
            $id=(int)db()->lastInsertId();
            audit_log('radars.admin_create',['id'=>$id,'uf'=>$uf]);
            $message='Radar cadastrado com sucesso.';
        }
    }catch(Throwable $e){
        $error='Não foi possível concluir: '.$e->getMessage();
        error_log('admin_radares: '.$e->getMessage());
    }
}

try{
    $summary=[
        'Total'=>(int)db()->query('SELECT COUNT(*) FROM radars')->fetchColumn(),
        'Ativos'=>(int)db()->query('SELECT COUNT(*) FROM radars WHERE ativo = 1')->fetchColumn(),
        'Inativos'=>(int)db()->query('SELECT COUNT(*) FROM radars WHERE ativo = 0')->fetchColumn(),
        'Sem velocidade'=>(int)db()->query('SELECT COUNT(*) FROM radars WHERE velocidade IS NULL')->fetchColumn(),
        'Espírito Santo'=>(int)db()->query("SELECT COUNT(*) FROM radars WHERE UPPER(COALESCE(uf,''))='ES'")->fetchColumn(),
    ];
    $byState=db()->query("SELECT COALESCE(NULLIF(UPPER(uf),''),'SEM UF') uf, COUNT(*) total FROM radars GROUP BY COALESCE(NULLIF(UPPER(uf),''),'SEM UF') ORDER BY total DESC")->fetchAll();
}catch(Throwable $e){$summary=[];$byState=[];$error=$error?:'Falha ao consultar a tabela de radares: '.$e->getMessage();}

$ufFilter=strtoupper(trim((string)($_GET['uf']??'')));
$q=trim((string)($_GET['q']??''));
$activeFilter=(string)($_GET['ativo']??'');
$where=[];$params=[];
if(in_array($ufFilter,['SP','RJ','MG','ES'],true)){$where[]='UPPER(COALESCE(uf,\'\'))=?';$params[]=$ufFilter;}
if($activeFilter==='1'||$activeFilter==='0'){$where[]='ativo=?';$params[]=(int)$activeFilter;}
if($q!==''){
    $where[]='(cidade LIKE ? OR rodovia LIKE ? OR external_id LIKE ? OR fonte LIKE ?)';
    $needle='%'.$q.'%';array_push($params,$needle,$needle,$needle,$needle);
}
$sql='SELECT * FROM radars'.($where?' WHERE '.implode(' AND ',$where):'').' ORDER BY id DESC LIMIT 300';
try{$stmt=db()->prepare($sql);$stmt->execute($params);$radars=$stmt->fetchAll()?:[];}catch(Throwable $e){$radars=[];$error=$error?:$e->getMessage();}
?>
<!doctype html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Central de Radares - EstradaPlay</title>
  <link rel="stylesheet" href="assets/css/app.css">
  <style>
    .radar-actions{display:flex;gap:6px;flex-wrap:wrap}.radar-actions form{margin:0}.state-pills{display:flex;gap:8px;flex-wrap:wrap;margin:10px 0 18px}.state-pills a{padding:8px 12px;border:1px solid #39424e;border-radius:999px;text-decoration:none}.filters{display:grid;grid-template-columns:1fr 120px 140px auto;gap:8px;align-items:end}.notice-error{border-color:#8c3038;color:#ffadb4}.notice-success{border-color:#226844;color:#8df0b6}@media(max-width:760px){.filters{grid-template-columns:1fr 1fr}.filters .wide{grid-column:1/-1}}
  </style>
</head>
<body>
<main>
  <div class="toolbar">
    <div><h1>Central de Radares</h1><p>Base usada pelos alertas offline do EstradaPlay.</p></div>
    <div class="row"><a class="button secondary" href="admin.php">Painel Admin</a><a class="button secondary" href="logout.php">Sair</a></div>
  </div>

  <?php if($message): ?><div class="panel notice-success"><?= htmlspecialchars($message,ENT_QUOTES,'UTF-8') ?></div><?php endif; ?>
  <?php if($error): ?><div class="panel notice-error"><?= htmlspecialchars($error,ENT_QUOTES,'UTF-8') ?></div><?php endif; ?>

  <div class="grid">
    <?php foreach($summary as $label=>$value): ?>
      <article class="panel"><h2><?= htmlspecialchars($label,ENT_QUOTES,'UTF-8') ?></h2><p class="big-number"><?= (int)$value ?></p></article>
    <?php endforeach; ?>
  </div>

  <section class="panel">
    <h2>Distribuição por estado</h2>
    <div class="state-pills">
      <?php foreach($byState as $s): ?>
        <a href="?uf=<?= urlencode((string)$s['uf']) ?>"><?= htmlspecialchars((string)$s['uf'],ENT_QUOTES,'UTF-8') ?> · <?= (int)$s['total'] ?></a>
      <?php endforeach; ?>
    </div>
  </section>

  <section class="panel">
    <h2>Adicionar radar</h2>
    <form method="post" class="form-grid">
      <input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token(),ENT_QUOTES,'UTF-8') ?>">
      <input type="hidden" name="action" value="create">
      <label>Latitude<input name="latitude" required inputmode="decimal" placeholder="-20.315500"></label>
      <label>Longitude<input name="longitude" required inputmode="decimal" placeholder="-40.312800"></label>
      <label>UF<input name="uf" maxlength="2" placeholder="ES"><small>Se vazio, o sistema tenta identificar pelas coordenadas.</small></label>
      <label>Cidade<input name="cidade" placeholder="Vitória"></label>
      <label>Rodovia<input name="rodovia" placeholder="BR-101"></label>
      <label>KM<input name="km" placeholder="293"></label>
      <label>Sentido<input name="sentido" placeholder="Norte / Sul"></label>
      <label>Direção heading<input name="heading" type="number" min="0" max="359.9" step="0.1"></label>
      <label>Velocidade<input name="velocidade" type="number" min="10" max="180" placeholder="60"></label>
      <label>Tipo<select name="tipo"><option>RADAR_FIXO</option><option>FISCALIZACAO</option><option>SEMÁFORO</option><option>QUEBRA_MOLAS</option><option>PEDAGIO</option></select></label>
      <label>Situação<select name="situacao"><option>ATIVO</option><option>INATIVO</option><option>AGUARDANDO_VALIDACAO</option></select></label>
      <label>Fonte<input name="fonte" value="ADMIN"></label>
      <label>Data da fonte<input name="data_fonte" type="date"></label>
      <label>Confiabilidade<select name="confiabilidade"><option>BAIXA</option><option>MÉDIA</option><option>ALTA</option><option>CONFIRMADA</option></select></label>
      <label>Quantidade de fontes<input name="quantidade_fontes" type="number" min="1" value="1"></label>
      <label>External ID<input name="external_id"></label>
      <label class="check"><input name="ativo" type="checkbox" checked> Ativo</label>
      <button type="submit">Salvar radar</button>
    </form>
  </section>

  <section class="panel">
    <h2>Consultar base</h2>
    <form method="get" class="filters">
      <label class="wide">Busca<input name="q" value="<?= htmlspecialchars($q,ENT_QUOTES,'UTF-8') ?>" placeholder="Cidade, rodovia, fonte ou ID"></label>
      <label>UF<select name="uf"><option value="">Todas</option><?php foreach(['ES','RJ','MG','SP'] as $u): ?><option value="<?= $u ?>" <?= $ufFilter===$u?'selected':'' ?>><?= $u ?></option><?php endforeach; ?></select></label>
      <label>Status<select name="ativo"><option value="">Todos</option><option value="1" <?= $activeFilter==='1'?'selected':'' ?>>Ativos</option><option value="0" <?= $activeFilter==='0'?'selected':'' ?>>Inativos</option></select></label>
      <button type="submit">Filtrar</button>
    </form>
  </section>

  <section class="panel">
    <h2>Radares encontrados · <?= count($radars) ?></h2>
    <div class="table-wrap">
      <table>
        <thead><tr><th>ID</th><th>UF</th><th>Cidade</th><th>Rodovia</th><th>KM</th><th>Vel.</th><th>Fonte</th><th>Conf.</th><th>Status</th><th>Ações</th></tr></thead>
        <tbody>
        <?php foreach($radars as $r): ?>
          <tr>
            <td><?= (int)$r['id'] ?></td>
            <td><?= htmlspecialchars((string)($r['uf']??''),ENT_QUOTES,'UTF-8') ?></td>
            <td><?= htmlspecialchars((string)($r['cidade']??''),ENT_QUOTES,'UTF-8') ?></td>
            <td><?= htmlspecialchars((string)($r['rodovia']??''),ENT_QUOTES,'UTF-8') ?></td>
            <td><?= htmlspecialchars((string)($r['km']??''),ENT_QUOTES,'UTF-8') ?></td>
            <td><?= htmlspecialchars((string)($r['velocidade']??''),ENT_QUOTES,'UTF-8') ?></td>
            <td><?= htmlspecialchars((string)($r['fonte']??''),ENT_QUOTES,'UTF-8') ?></td>
            <td><?= htmlspecialchars((string)($r['confiabilidade']??''),ENT_QUOTES,'UTF-8') ?></td>
            <td><?= !empty($r['ativo'])?'Ativo':'Inativo' ?></td>
            <td><div class="radar-actions">
              <form method="post"><input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token(),ENT_QUOTES,'UTF-8') ?>"><input type="hidden" name="action" value="toggle"><input type="hidden" name="id" value="<?= (int)$r['id'] ?>"><input type="hidden" name="ativo" value="<?= !empty($r['ativo'])?0:1 ?>"><button class="secondary" type="submit"><?= !empty($r['ativo'])?'Desativar':'Ativar' ?></button></form>
              <form method="post" onsubmit="return confirm('Remover este radar?')"><input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token(),ENT_QUOTES,'UTF-8') ?>"><input type="hidden" name="action" value="delete"><input type="hidden" name="id" value="<?= (int)$r['id'] ?>"><button class="secondary" type="submit">Excluir</button></form>
            </div></td>
          </tr>
        <?php endforeach; ?>
        <?php if(!$radars): ?><tr><td colspan="10">Nenhum radar encontrado com esses filtros.</td></tr><?php endif; ?>
        </tbody>
      </table>
    </div>
  </section>
</main>
</body>
</html>
