<?php
declare(strict_types=1);
require __DIR__.'/bootstrap.php';
require_login();
require_once dirname(__DIR__).'/lib/CnefeAddressIndex.php';

$action=strtolower(trim((string)($_GET['action']??'status')));
$method=strtoupper((string)($_SERVER['REQUEST_METHOD']??'GET'));
$code=trim((string)($_GET['city_id']??$_GET['municipality_code']??''));
$uf=strtoupper(trim((string)($_GET['uf']??'')));
$city=trim((string)($_GET['city']??''));
function address_public_pack(?array $pack): ?array {
    if($pack===null)return null;
    unset($pack['source_url'],$pack['zip_path'],$pack['last_error']);
    return $pack;
}
function address_public_result(array $result): array {
    unset($result['source_url'],$result['zip_path'],$result['last_error']);
    return $result;
}
if(in_array($action,['prepare','rebuild'],true)){
    if($method!=='POST')json_response(['ok'=>false,'error'=>'Use POST.'],405);
    if($action==='rebuild')require_admin();
    require_csrf();require_session_rate_limit('address-prepare',8,3600);
}else require_session_rate_limit('address-read',180,60);
session_write_close();
try{
    if($action==='status'){
        if($code!==''){ $p=address_public_pack(cnefe_pack($code)); json_response(['ok'=>true,'pack'=>$p,'version'=>MUSICROAD_VERSION]); }
        $user=current_user();if(($user['role']??'')!=='admin')json_response(['ok'=>true,'packs'=>[],'version'=>MUSICROAD_VERSION]);
        json_response(['ok'=>true,'packs'=>array_map('address_public_pack',cnefe_status_list()),'version'=>MUSICROAD_VERSION]);
    }
    if($action==='prepare'){
        if($code===''||$uf===''||$city==='')json_response(['ok'=>false,'error'=>'Selecione estado e município antes de preparar a base local.'],422);
        $r=address_public_result(cnefe_prepare_city($code,$uf,$city,false));json_response(['ok'=>true]+$r+['version'=>MUSICROAD_VERSION]);
    }
    if($action==='search'){
        $q=trim((string)($_GET['q']??''));if(mb_strlen($q)<2)json_response(['ok'=>true,'results'=>[],'version'=>MUSICROAD_VERSION]);
        if($code===''||$uf===''||$city==='')json_response(['ok'=>false,'error'=>'Selecione estado e município antes de buscar um endereço.','select_city_required'=>true],422);
        $r=cnefe_search($code,$uf,$city,$q,15);json_response(['ok'=>true]+$r+['version'=>MUSICROAD_VERSION]);
    }
    if($action==='rebuild'){
        if($code===''||$uf===''||$city==='')json_response(['ok'=>false,'error'=>'Município inválido.'],422);
        $r=address_public_result(cnefe_prepare_city($code,$uf,$city,true));json_response(['ok'=>true]+$r+['version'=>MUSICROAD_VERSION]);
    }
    json_response(['ok'=>false,'error'=>'Ação inválida.'],422);
}catch(Throwable $e){$incident=report_runtime_exception('address_local',$e);json_response(['ok'=>false,'error'=>'Não foi possível concluir a operação com a base local de endereços.','incident'=>$incident,'version'=>MUSICROAD_VERSION],500);}
