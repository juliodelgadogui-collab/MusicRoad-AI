<?php
require __DIR__ . '/bootstrap.php';
require_login();

$stateId=preg_replace('/\D+/','',(string)($_GET['state_id']??''));
$uf=strtoupper(trim((string)($_GET['uf']??'')));
$state=trim((string)($_GET['state']??$uf));
if($stateId===''||!preg_match('/^[A-Z]{2}$/',$uf))json_response(['ok'=>false,'error'=>'Selecione um estado.'],422);
$ufStateIds=['RO'=>'11','AC'=>'12','AM'=>'13','RR'=>'14','PA'=>'15','AP'=>'16','TO'=>'17','MA'=>'21','PI'=>'22','CE'=>'23','RN'=>'24','PB'=>'25','PE'=>'26','AL'=>'27','SE'=>'28','BA'=>'29','MG'=>'31','ES'=>'32','RJ'=>'33','SP'=>'35','PR'=>'41','SC'=>'42','RS'=>'43','MS'=>'50','MT'=>'51','GO'=>'52','DF'=>'53'];
if(($ufStateIds[$uf]??'')!==$stateId)json_response(['ok'=>false,'error'=>'Estado e UF não correspondem.'],422);
$state=mb_substr($state,0,80);
require_session_rate_limit('offline-state-manifest',10,3600);
session_write_close();

$url='https://servicodados.ibge.gov.br/api/v1/localidades/estados/'.rawurlencode($stateId).'/municipios?orderBy=nome';
$rows=http_json($url,null,[],35);
if(!is_array($rows))json_response(['ok'=>false,'error'=>'Não foi possível obter os municípios do estado agora.'],502);
$municipalities=[];
foreach($rows as $r){
    $id=preg_replace('/\D+/','',(string)($r['id']??''));
    $name=trim((string)($r['nome']??''));
    if(strlen($id)!==7||$name==='')continue;
    $municipalities[]=['id'=>$id,'name'=>$name];
}
header('Cache-Control: no-store, max-age=0');
json_response([
    'ok'=>true,
    'version'=>2,
    'state'=>['id'=>$stateId,'uf'=>$uf,'name'=>$state],
    'municipalities'=>$municipalities,
    'generated_at'=>gmdate('c'),
    'storage'=>'device',
    'note'=>'Endpoint mantido apenas para compatibilidade. O MusicRoad 1.2 baixa a base estadual e detalha somente a cidade selecionada.'
]);
