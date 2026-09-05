<?php
declare(strict_types=1);
require __DIR__ . '/api/bootstrap.php';
$user = require_admin();
require __DIR__ . '/api/radar_db.php';
require __DIR__ . '/api/road_hazard_db.php';
radar_ensure_tables();
road_hazard_ensure_tables();
@set_time_limit(170);

$message='';
$error='';

if($_SERVER['REQUEST_METHOD']==='POST'){
    require_csrf();
    $action=(string)($_POST['action']??'create');
    try{
        if($action==='sync_state'){
            $uf=strtoupper(trim((string)($_POST['uf']??'')));
            if(!in_array($uf,['ES','RJ','MG','SP'],true))throw new RuntimeException('UF inválida.');
            $result=road_hazard_sync_state($uf);
            audit_log('road_hazards.admin_sync',['uf'=>$uf,'ok'=>!empty($result['ok']),'count'=>(int)($result['count']??0)]);
            if(empty($result['ok']))throw new RuntimeException((string)($result['error']??'Falha ao sincronizar o estado.'));
            $message='Base de '.$uf.' atualizada: '.(int)$result['count'].' alertas rodoviários importados.';
        }elseif($action==='toggle'){
            $id=(int)($_POST['id']??0);
            $ativo=(int)($_POST['ativo']??0)===1?1:0;
            if($id<=0)throw new RuntimeException('Alerta inválido.');
            $stmt=db()->prepare('UPDATE radars SET ativo=? WHERE id=?');
            $stmt->execute([$ativo,$id]);
            audit_log('radars.admin_toggle',['id'=>$id,'ativo'=>$ativo]);
            $message=$ativo?'Alerta local ativado.':'Alerta local desativado.';
        }elseif($action==='delete'){
            $id=(int)($_POST['id']??0);
            if($id<=0)throw new RuntimeException('Alerta inválido.');
            db()->beginTransaction();
            try{
                $s=db()->prepare('DELETE FROM radar_sources WHERE radar_id=?');$s->execute([$id]);
                $s=db()->prepare('DELETE FROM radars WHERE id=?');$s->execute([$id]);
                db()->commit();
            }catch(Throwable $e){if(db()->inTransaction())db()->rollBack();throw $e;}
            audit_log('radars.admin_delete',['id'=>$id]);
            $message='Alerta local removido.';
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
            audit_log('radars.admin_create',['id'=>$id,'uf'=>$uf,'tipo'=>$_POST['tipo']??'RADAR_FIXO']);
            $message='Alerta local cadastrado com sucesso.';
        }
    }catch(Throwable $e){
        $error='Não foi possível concluir: '.$e->getMessage();
        error_log('admin_radares: '.$e->getMessage());
    }
}

$ufFilter=strtoupper(trim((string)($_GET['uf']??'')));
if(!in_array($ufFilter,['ES','RJ','MG','SP'],true))$ufFilter='';
$q=trim((string)($_GET['q']??''));
$activeFilter=(string)($_GET['ativo']??'');
$typeFilter=strtoupper(trim((string)($_GET['tipo']??'')));

try{
    $localTotal=(int)db()->query('SELECT COUNT(*) FROM radars')->fetchColumn();
    $localActive=(int)db()->query('SELECT COUNT(*) FROM radars WHERE ativo=1')->fetchColumn();
    $localInactive=(int)db()->query('SELECT COUNT(*) FROM radars WHERE ativo=0')->fetchColumn();
    $hazardStats=road_hazard_stats($ufFilter!==''?$ufFilter:null);
    $hazardTotal=array_sum($hazardStats);
    $summary=[
        'Total disponível'=>$localActive+$hazardTotal,
        'Alertas locais'=>$localTotal,
        'OpenStreetMap'=>$hazardTotal,
        'Radares OSM'=>(int)($hazardStats['RADAR']??0),
        'Quebra-molas'=>(int)($hazardStats['QUEBRA_MOLAS']??0),
        'Semáforos'=>(int)($hazardStats['SEMAFORO']??0),
        'Pedágios'=>(int)($hazardStats['PEDAGIO']??0),
        'Passagens de nível'=>(int)($hazardStats['PASSAGEM_NIVEL']??0),
    ];
    $stateRows=[];
    foreach(['ES','RJ','MG','SP'] as $u){
        $stmt=db()->prepare("SELECT COUNT(*) FROM radars WHERE ativo=1 AND UPPER(COALESCE(uf,''))=?");$stmt->execute([$u]);
        $local=(int)$stmt->fetchColumn();
        $osm=road_hazard_count_state($u);
        $stateRows[]=['uf'=>$u,'local'=>$local,'osm'=>$osm,'total'=>$local+$osm];
    }
    $syncStatus=[];
    foreach(road_hazard_sync_status() as $s)$syncStatus[(string)$s['uf']]=$s;
}catch(Throwable $e){
    $summary=[];$stateRows=[];$syncStatus=[];
    $error=$error?:'Falha ao consultar as bases de alertas: '.$e->getMessage();
}

$where=[];$params=[];
if($ufFilter!==''){$where[]='UPPER(COALESCE(uf,\'\'))=?';$params[]=$ufFilter;}
if($activeFilter==='1'||$activeFilter==='0'){$where[]='ativo=?';$params[]=(int)$activeFilter;}
if($q!==''){
    $where[]='(cidade LIKE ? OR rodovia LIKE ? OR external_id LIKE ? OR fonte LIKE ? OR tipo LIKE ?)';
    $needle='%'.$q.'%';array_push($params,$needle,$needle,$needle,$needle,$needle);
}
$sql='SELECT * FROM radars'.($where?' WHERE '.implode(' AND ',$where):'').' ORDER BY id DESC LIMIT 300';
try{$stmt=db()->prepare($sql);$stmt->execute($params);$radars=$stmt->fetchAll()?:[];}catch(Throwable $e){$radars=[];$error=$error?:$e->getMessage();}

$hazWhere=['active=1'];$hazParams=[];
if($ufFilter!==''){$hazWhere[]="UPPER(COALESCE(uf,''))=?";$hazParams[]=$ufFilter;}
if(in_array($typeFilter,['RADAR','QUEBRA_MOLAS','SEMAFORO','PEDAGIO','PASSAGEM_NIVEL'],true)){$hazWhere[]='type=?';$hazParams[]=$typeFilter;}
if($q!==''){$hazWhere[]='(road LIKE ? OR hazard_key LIKE ? OR source LIKE ?)';$needle='%'.$q.'%';array_push($hazParams,$needle,$needle,$needle);}
$hazSql='SELECT * FROM road_hazards WHERE '.implode(' AND ',$hazWhere).' ORDER BY id DESC LIMIT 300';
try{$stmt=db()->prepare($hazSql);$stmt->execute($hazParams);$hazards=$stmt->fetchAll()?:[];}catch(Throwable $e){$hazards=[];$error=$error?:$e->getMessage();}
?>
<!doctype html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Central de Alertas Rodoviários - EstradaPlay</title>
  <link rel="stylesheet" href="assets/css/app.css">
  <style>
    .radar-actions{display:flex;gap:6px;flex-wrap:wrap}.radar-actions form{margin:0}.state-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:12px}.state-box{border:1px solid #2f4055;border-radius:18px;padding:16px}.state-box h3{margin:0 0 8px}.state-box p{margin:4px 0}.filters{display:grid;grid-template-columns:1fr 110px 150px 150px auto;gap:8px;align-items:end}.notice-error{border-color:#8c3038;color:#ffadb4}.notice-success{border-color:#226844;color:#8df0b6}.muted{opacity:.72}.warning{border:1px solid #795c22;background:#2f2614;padding:14px;border-radius:14px;margin:12px 0}.type-badge{white-space:nowrap}.coords{font-variant-numeric:tabular-nums;font-size:.88rem}.sync-btn{width:100%;margin-top:10px}@media(max-width:760px){.filters{grid-template-columns:1fr 1fr}.filters .wide{grid-column:1/-1}.grid{grid-template-columns:1fr 1fr}.grid .panel{padding:18px}.big-number{font-size:2.4rem}.table-wrap{overflow-x:auto}}
  </style>
</head>
<body>
<main>
  <div class="toolbar">
    <div><h1>Central de Alertas Rodoviários</h1><p>Radares, quebra-molas, semáforos, pedágios e passagens de nível usados pelo EstradaPlay.</p></div>
    <div class="row"><a class="button secondary" href="admin.php">Painel Admin</a><a class="button secondary" href="logout.php">Sair</a></div>
  </div>

  <?php if($message): ?><div class="panel notice-success"><?= htmlspecialchars($message,ENT_QUOTES,'UTF-8') ?></div><?php endif; ?>
  <?php if($error): ?><div class="panel notice-error"><?= htmlspecialchars($error,ENT_QUOTES,'UTF-8') ?></div><?php endif; ?>

  <?php if(($summary['Total disponível']??0)===0): ?>
    <div class="warning"><strong>A base está vazia.</strong> Escolha um estado abaixo e toque em <strong>Sincronizar</strong>. O servidor buscará radares e demais alertas do OpenStreetMap e guardará somente coordenadas/metadados.</div>
  <?php endif; ?>

  <div class="grid">
    <?php foreach($summary as $label=>$value): ?>
      <article class="panel"><h2><?= htmlspecialchars($label,ENT_QUOTES,'UTF-8') ?></h2><p class="big-number"><?= (int)$value ?></p></article>
    <?php endforeach; ?>
  </div>

  <section class="panel">
    <h2>Base por estado</h2>
    <p class="muted">Sincronizar pode levar alguns segundos. Se o OpenStreetMap falhar, a base anterior é mantida.</p>
    <div class="state-grid">
      <?php foreach($stateRows as $s): $u=(string)$s['uf'];$ss=$syncStatus[$u]??null; ?>
        <div class="state-box">
          <h3><?= htmlspecialchars($u,ENT_QUOTES,'UTF-8') ?> · <?= (int)$s['total'] ?></h3>
          <p>Locais: <strong><?= (int)$s['local'] ?></strong></p>
          <p>OpenStreetMap: <strong><?= (int)$s['osm'] ?></strong></p>
          <p class="muted">Última atualização: <?= htmlspecialchars((string)($ss['last_success']??'nunca'),ENT_QUOTES,'UTF-8') ?></p>
          <?php if(!empty($ss['last_error'])): ?><p class="muted">Último erro: <?= htmlspecialchars((string)$ss['last_error'],ENT_QUOTES,'UTF-8') ?></p><?php endif; ?>
          <form method="post">
            <input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token(),ENT_QUOTES,'UTF-8') ?>">
            <input type="hidden" name="action" value="sync_state">
            <input type="hidden" name="uf" value="<?= htmlspecialchars($u,ENT_QUOTES,'UTF-8') ?>">
            <button class="sync-btn" type="submit">Sincronizar <?= htmlspecialchars($u,ENT_QUOTES,'UTF-8') ?></button>
          </form>
        </div>
      <?php endforeach; ?>
    </div>
  </section>

  <section class="panel">
    <h2>Consultar alertas</h2>
    <form method="get" class="filters">
      <label class="wide">Busca<input name="q" value="<?= htmlspecialchars($q,ENT_QUOTES,'UTF-8') ?>" placeholder="Rodovia, cidade, fonte ou ID"></label>
      <label>UF<select name="uf"><option value="">Todas</option><?php foreach(['ES','RJ','MG','SP'] as $u): ?><option value="<?= $u ?>" <?= $ufFilter===$u?'selected':'' ?>><?= $u ?></option><?php endforeach; ?></select></label>
      <label>Tipo<select name="tipo"><option value="">Todos</option><option value="RADAR" <?= $typeFilter==='RADAR'?'selected':'' ?>>Radar</option><option value="QUEBRA_MOLAS" <?= $typeFilter==='QUEBRA_MOLAS'?'selected':'' ?>>Quebra-molas</option><option value="SEMAFORO" <?= $typeFilter==='SEMAFORO'?'selected':'' ?>>Semáforo</option><option value="PEDAGIO" <?= $typeFilter==='PEDAGIO'?'selected':'' ?>>Pedágio</option><option value="PASSAGEM_NIVEL" <?= $typeFilter==='PASSAGEM_NIVEL'?'selected':'' ?>>Passagem de nível</option></select></label>
      <label>Local<select name="ativo"><option value="">Todos</option><option value="1" <?= $activeFilter==='1'?'selected':'' ?>>Ativos</option><option value="0" <?= $activeFilter==='0'?'selected':'' ?>>Inativos</option></select></label>
      <button type="submit">Filtrar</button>
    </form>
  </section>

  <section class="panel">
    <h2>Alertas OpenStreetMap · <?= count($hazards) ?> exibidos</h2>
    <div class="table-wrap">
      <table>
        <thead><tr><th>Tipo</th><th>UF</th><th>Rodovia/local</th><th>Vel.</th><th>Coordenadas</th><th>Fonte</th><th>Atualizado</th></tr></thead>
        <tbody>
          <?php foreach($hazards as $h): ?>
            <tr>
              <td class="type-badge"><?= htmlspecialchars((string)$h['type'],ENT_QUOTES,'UTF-8') ?></td>
              <td><?= htmlspecialchars((string)($h['uf']??''),ENT_QUOTES,'UTF-8') ?></td>
              <td><?= htmlspecialchars((string)($h['road']??''),ENT_QUOTES,'UTF-8') ?></td>
              <td><?= htmlspecialchars((string)($h['speed']??''),ENT_QUOTES,'UTF-8') ?></td>
              <td class="coords"><?= htmlspecialchars(number_format((float)$h['latitude'],6,'.','').', '.number_format((float)$h['longitude'],6,'.',''),ENT_QUOTES,'UTF-8') ?></td>
              <td><?= htmlspecialchars((string)$h['source'],ENT_QUOTES,'UTF-8') ?></td>
              <td><?= htmlspecialchars((string)$h['last_seen'],ENT_QUOTES,'UTF-8') ?></td>
            </tr>
          <?php endforeach; ?>
          <?php if(!$hazards): ?><tr><td colspan="7">Nenhum alerta OSM salvo. Use “Sincronizar” no estado desejado.</td></tr><?php endif; ?>
        </tbody>
      </table>
    </div>
  </section>

  <section class="panel">
    <h2>Adicionar alerta local</h2>
    <p class="muted">Use esta área para cadastrar manualmente um radar, quebra-molas ou outro ponto que não exista na fonte pública.</p>
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
      <label>Tipo<select name="tipo"><option value="RADAR_FIXO">Radar fixo</option><option value="FISCALIZACAO">Fiscalização</option><option value="QUEBRA_MOLAS">Quebra-molas</option><option value="SEMAFORO">Semáforo</option><option value="PEDAGIO">Pedágio</option><option value="PASSAGEM_NIVEL">Passagem de nível</option></select></label>
      <label>Situação<select name="situacao"><option>ATIVO</option><option>INATIVO</option><option>AGUARDANDO_VALIDACAO</option></select></label>
      <label>Fonte<input name="fonte" value="ADMIN"></label>
      <label>Data da fonte<input name="data_fonte" type="date"></label>
      <label>Confiabilidade<select name="confiabilidade"><option>BAIXA</option><option>MÉDIA</option><option>ALTA</option><option>CONFIRMADA</option></select></label>
      <label>Quantidade de fontes<input name="quantidade_fontes" type="number" min="1" value="1"></label>
      <label>External ID<input name="external_id"></label>
      <label class="check"><input name="ativo" type="checkbox" checked> Ativo</label>
      <button type="submit">Salvar alerta</button>
    </form>
  </section>

  <section class="panel">
    <h2>Alertas locais · <?= count($radars) ?> exibidos</h2>
    <div class="table-wrap">
      <table>
        <thead><tr><th>ID</th><th>Tipo</th><th>UF</th><th>Cidade</th><th>Rodovia</th><th>KM</th><th>Vel.</th><th>Fonte</th><th>Status</th><th>Ações</th></tr></thead>
        <tbody>
        <?php foreach($radars as $r): ?>
          <tr>
            <td><?= (int)$r['id'] ?></td>
            <td><?= htmlspecialchars((string)($r['tipo']??'RADAR'),ENT_QUOTES,'UTF-8') ?></td>
            <td><?= htmlspecialchars((string)($r['uf']??''),ENT_QUOTES,'UTF-8') ?></td>
            <td><?= htmlspecialchars((string)($r['cidade']??''),ENT_QUOTES,'UTF-8') ?></td>
            <td><?= htmlspecialchars((string)($r['rodovia']??''),ENT_QUOTES,'UTF-8') ?></td>
            <td><?= htmlspecialchars((string)($r['km']??''),ENT_QUOTES,'UTF-8') ?></td>
            <td><?= htmlspecialchars((string)($r['velocidade']??''),ENT_QUOTES,'UTF-8') ?></td>
            <td><?= htmlspecialchars((string)($r['fonte']??''),ENT_QUOTES,'UTF-8') ?></td>
            <td><?= !empty($r['ativo'])?'Ativo':'Inativo' ?></td>
            <td><div class="radar-actions">
              <form method="post"><input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token(),ENT_QUOTES,'UTF-8') ?>"><input type="hidden" name="action" value="toggle"><input type="hidden" name="id" value="<?= (int)$r['id'] ?>"><input type="hidden" name="ativo" value="<?= !empty($r['ativo'])?0:1 ?>"><button class="secondary" type="submit"><?= !empty($r['ativo'])?'Desativar':'Ativar' ?></button></form>
              <form method="post" onsubmit="return confirm('Remover este alerta?')"><input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token(),ENT_QUOTES,'UTF-8') ?>"><input type="hidden" name="action" value="delete"><input type="hidden" name="id" value="<?= (int)$r['id'] ?>"><button class="secondary" type="submit">Excluir</button></form>
            </div></td>
          </tr>
        <?php endforeach; ?>
        <?php if(!$radars): ?><tr><td colspan="10">Nenhum alerta local encontrado com esses filtros.</td></tr><?php endif; ?>
        </tbody>
      </table>
    </div>
  </section>
</main>
</body>
</html>
