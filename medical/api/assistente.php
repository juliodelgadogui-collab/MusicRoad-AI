<?php
require dirname(__DIR__).'/bootstrap.php';
require_auth();
header('Content-Type: application/json; charset=utf-8');
if($_SERVER['REQUEST_METHOD']!=='POST'){http_response_code(405);echo json_encode(['ok'=>false,'error'=>'Método não permitido']);exit;}
verify_csrf();
$in=json_decode(file_get_contents('php://input'),true) ?: $_POST;
$texto=trim((string)($in['texto']??''));
if($texto===''){echo json_encode(['ok'=>false,'error'=>'Informe os dados do atendimento.']);exit;}
$out=['tipo'=>'apoio_documental','alertas'=>['Revisar identificação do paciente.','Confirmar diagnóstico e CID pelo médico.','Registrar repercussão funcional quando pertinente.','Confirmar período e conclusão antes da emissão.'],'estrutura_sugerida'=>['Resumo clínico baseado exclusivamente nos dados fornecidos.','Achados relevantes e exames apresentados.','Repercussão funcional descrita pelo médico.','Conclusão para revisão e confirmação médica.'],'observacao'=>'Sugestão de apoio. A decisão clínica e a emissão permanecem exclusivamente com o médico.'];
audit($db,auth()['id'],'consulta_ia','atendimentos',null,null,'apoio documental');
echo json_encode(['ok'=>true,'result'=>$out],JSON_UNESCAPED_UNICODE);
