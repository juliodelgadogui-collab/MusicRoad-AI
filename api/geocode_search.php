<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
require_login();
require_once dirname(__DIR__).'/lib/CnefeAddressIndex.php';

$q=trim((string)($_GET['q']??''));
$type=strtolower(trim((string)($_GET['type']??'address')));
$uf=strtoupper(trim((string)($_GET['uf']??'')));
$city=trim((string)($_GET['city']??''));
$cityId=trim((string)($_GET['city_id']??''));
if(mb_strlen($q)<2)json_response(['ok'=>true,'results'=>[],'version'=>MUSICROAD_VERSION]);
if(mb_strlen($q)>180)json_response(['ok'=>false,'error'=>'Pesquisa muito longa.'],422);
if(!in_array($type,['city','address'],true))$type='address';
if($uf!==''&&!preg_match('/^[A-Z]{2}$/',$uf))json_response(['ok'=>false,'error'=>'UF inválida.'],422);
require_session_rate_limit('geocode-search',90,60);
session_write_close();

try{
    if($type==='address'){
        if($cityId===''||$city===''||$uf===''){
            json_response(['ok'=>true,'results'=>[],'select_city_required'=>true,'message'=>'Selecione estado e município para pesquisar na base local do IBGE.','source'=>'IBGE CNEFE local','version'=>MUSICROAD_VERSION]);
        }
        $r=cnefe_search($cityId,$uf,$city,$q,15);
        json_response(['ok'=>true,'results'=>$r['results']??[],'status'=>$r['status']??'ready','message'=>$r['message']??null,'source'=>'IBGE CNEFE 2022 local','api_key_required'=>false,'version'=>MUSICROAD_VERSION]);
    }

    // Pesquisa de município sem geocodificador: usa a lista do próprio IBGE que o servidor já mantém em cache.
    $cache=dirname(__DIR__).'/storage/cache/locations/municipios-'.$uf.'.json';
    $rows=is_file($cache)?json_decode((string)file_get_contents($cache),true):[];
    $wanted=cnefe_fold($q);$scored=[];
    foreach(is_array($rows)?$rows:[] as $r){
        $name=trim((string)($r['nome']??''));if($name==='')continue;$f=cnefe_fold($name);$score=0;
        if($f===$wanted)$score=100;elseif(str_starts_with($f,$wanted))$score=80;elseif(str_contains($f,$wanted))$score=55;else{$lev=levenshtein($wanted,$f);if($lev<=2)$score=35-$lev*5;}
        if($score<=0)continue;$scored[]=['score'=>$score,'name'=>$name,'id'=>(string)($r['id']??''),'uf'=>$uf];
    }
    usort($scored,fn($a,$b)=>$b['score']<=>$a['score'] ?: strnatcasecmp($a['name'],$b['name']));
    $out=[];foreach(array_slice($scored,0,12) as $x)$out[]=['name'=>$x['name'],'city'=>$x['name'],'uf'=>$x['uf'],'label'=>$x['name'].', '.$x['uf'],'lat'=>null,'lon'=>null,'ibge_id'=>$x['id'],'source'=>'IBGE local'];
    json_response(['ok'=>true,'results'=>$out,'source'=>'IBGE/cache local','version'=>MUSICROAD_VERSION]);
}catch(Throwable $e){
    error_log('[MusicRoad geocode_search] '.substr(hash('sha256',$e->getMessage()),0,12));
    json_response(['ok'=>false,'error'=>'Não foi possível pesquisar a base local agora.','source'=>'IBGE CNEFE local','version'=>MUSICROAD_VERSION],500);
}
