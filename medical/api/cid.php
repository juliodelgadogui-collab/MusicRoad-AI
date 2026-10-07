<?php
require dirname(__DIR__).'/bootstrap.php';
require_auth();
header('Content-Type: application/json; charset=utf-8');
$q=trim($_GET['q']??'');
if($q===''){echo json_encode(['ok'=>true,'items'=>[]],JSON_UNESCAPED_UNICODE);exit;}
$s=$db->prepare("SELECT id,codigo,descricao,versao FROM cids WHERE codigo LIKE ? OR descricao LIKE ? ORDER BY codigo LIMIT 50");
$like='%'.$q.'%';$s->execute([$like,$like]);echo json_encode(['ok'=>true,'items'=>$s->fetchAll(PDO::FETCH_ASSOC)],JSON_UNESCAPED_UNICODE);
