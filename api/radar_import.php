<?php
require __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/radar_import_helpers.php';
$user=require_admin();
require_csrf();
require_session_rate_limit('radar-import-legacy',10,3600);
session_write_close();

if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'POST') json_response(['ok'=>false,'error'=>'Use POST.'],405);
if (!isset($_FILES['file']) || !is_uploaded_file($_FILES['file']['tmp_name'] ?? '')) json_response(['ok'=>false,'error'=>'Selecione um arquivo CSV, TXT ou KML.'],422);
$file=$_FILES['file'];
if (($file['error'] ?? UPLOAD_ERR_OK) !== UPLOAD_ERR_OK) json_response(['ok'=>false,'error'=>'Falha no upload do arquivo.'],422);
$size=(int)($file['size'] ?? 0);if($size<=0||$size>30*1024*1024)json_response(['ok'=>false,'error'=>'Arquivo vazio ou maior que 30 MB.'],422);
$name=(string)($file['name'] ?? 'radares.csv');$ext=strtolower(pathinfo($name,PATHINFO_EXTENSION));
if(!in_array($ext,['csv','txt','kml'],true))json_response(['ok'=>false,'error'=>'Formato não suportado. Use CSV, TXT ou KML.'],422);
$source=((string)($_POST['source']??''))==='MAPARADAR_USUARIO'?'MAPARADAR_USUARIO':'IMPORT_USUARIO';
$raw=(string)file_get_contents($file['tmp_name']);$rows=mr_import_parse_bytes($raw,$name,$source);
if(!$rows)json_response(['ok'=>false,'error'=>'Não encontrei coordenadas válidas no arquivo. Para iGO use X,Y,TYPE,SPEED,DIRTYPE,DIRECTION.'],422);

$db=db();$inserted=0;$updated=0;$skipped=0;
$find=$db->prepare('SELECT id FROM radars WHERE external_id=? LIMIT 1');
$ins=$db->prepare('INSERT INTO radars (external_id,latitude,longitude,uf,cidade,rodovia,km,sentido,heading,velocidade,tipo,situacao,fonte,data_fonte,data_importacao,confiabilidade,quantidade_fontes,ativo) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,?,?,1)');
$upd=$db->prepare('UPDATE radars SET latitude=?,longitude=?,heading=?,velocidade=?,tipo=?,situacao="ATIVO",fonte=?,data_importacao=CURRENT_TIMESTAMP,confiabilidade=?,ativo=1 WHERE id=?');
$db->beginTransaction();
try{
    foreach($rows as $r){
        try{
            $find->execute([$r['external_id']]);$id=$find->fetchColumn();
            if($id){$upd->execute([$r['latitude'],$r['longitude'],$r['heading'],$r['velocidade'],$r['tipo'],$r['fonte'],$r['confiabilidade'],$id]);$updated++;}
            else{$ins->execute([$r['external_id'],$r['latitude'],$r['longitude'],null,null,null,null,null,$r['heading'],$r['velocidade'],$r['tipo'],'ATIVO',$r['fonte'],date('Y-m-d'),$r['confiabilidade'],1]);$inserted++;}
        }catch(Throwable $e){$skipped++;}
    }
    $db->commit();
}catch(Throwable $e){if($db->inTransaction())$db->rollBack();$incident=report_runtime_exception('radar_import',$e);json_response(['ok'=>false,'error'=>'Falha ao gravar a base de radares.','incident'=>$incident],500);}
try{audit_log('radar_import',['source'=>$source,'file'=>$name,'parsed'=>count($rows),'inserted'=>$inserted,'updated'=>$updated,'skipped'=>$skipped]);}catch(Throwable $e){}
$total=(int)$db->query('SELECT COUNT(*) FROM radars WHERE ativo=1')->fetchColumn();
json_response(['ok'=>true,'source'=>$source,'parsed'=>count($rows),'inserted'=>$inserted,'updated'=>$updated,'skipped'=>$skipped,'active_total'=>$total,'message'=>'Base importada. Os pontos já entram na consulta das rotas.']);
