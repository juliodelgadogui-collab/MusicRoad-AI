<?php
declare(strict_types=1);
require __DIR__.'/bootstrap.php';
require_login();
require __DIR__.'/road_safety_pack_helpers.php';
@set_time_limit(150);
header('Cache-Control: private, max-age=1800');

$uf=strtoupper(trim((string)($_GET['uf']??'')));
$allowed=['SP','RJ','MG','ES'];
if(!in_array($uf,$allowed,true))json_response(['ok'=>false,'error'=>'Estado ainda não disponível para pacote offline.'],422);

$items=[];$seen=[];$localCount=0;$osmOk=false;$message='';
[$minLat,$minLon,$maxLat,$maxLon]=ep2_state_bounds($uf);

try{
    $stmt=db()->prepare('SELECT id, external_id, latitude, longitude, uf, rodovia, heading, sentido, velocidade, tipo, fonte FROM radars WHERE ativo = 1 AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT 30000');
    $stmt->execute([$minLat,$maxLat,$minLon,$maxLon]);
    foreach(($stmt->fetchAll()?:[]) as $r){
        $rlat=(float)$r['latitude'];$rlon=(float)$r['longitude'];
        $storedUf=strtoupper(trim((string)($r['uf']??'')));
        if($storedUf!=='' && $storedUf!==$uf)continue;
        if($storedUf==='' && ep2_guess_uf($rlat,$rlon)!==$uf)continue;
        ep2_add($items,$seen,[
            'id'=>!empty($r['external_id'])?(string)$r['external_id']:'db-radar-'.(string)$r['id'],
            'type'=>'RADAR','lat'=>$rlat,'lon'=>$rlon,
            'road'=>$r['rodovia']??'','speed'=>ep2_speed($r['velocidade']??null),
            'heading'=>is_numeric($r['heading']??null)?(float)$r['heading']:ep2_heading($r['sentido']??null),
            'source'=>$r['fonte']??'BASE_LOCAL'
        ]);
        $localCount++;
    }
}catch(Throwable $e){$message='Base local indisponível: '.$e->getMessage();}

try{
    $selector='area["ISO3166-2"="BR-'.$uf.'"][boundary="administrative"]->.eparea;';
    $query=ep2_osm_query_for_area($selector);
    $osm=ep2_overpass_json($query,$uf==='ES'?120:105);
    if(is_array($osm)){
        $osmOk=true;
        foreach(($osm['elements']??[]) as $el){
            if(count($items)>=65000)break;
            $h=ep2_osm_to_hazard($el);if($h!==null)ep2_add($items,$seen,$h);
        }
    }else{
        $message=$message!==''?$message:'OpenStreetMap não respondeu a tempo; mantendo somente a base local.';
    }
}catch(Throwable $e){$message=$message!==''?$message:'Falha ao atualizar dados do OpenStreetMap.';}

$ok=count($items)>0;
json_response([
    'ok'=>$ok,
    'version'=>'1.1',
    'kind'=>'state',
    'uf'=>$uf,
    'generated_at'=>gmdate('c'),
    'expires_in_s'=>$osmOk?2592000:21600,
    'hazards'=>$items,
    'coverage'=>[
        'uf'=>$uf,
        'local_radars'=>$localCount,
        'osm_ok'=>$osmOk,
        'total'=>count($items),
        'truncated'=>count($items)>=65000,
        'bounds'=>['south'=>$minLat,'west'=>$minLon,'north'=>$maxLat,'east'=>$maxLon]
    ],
    'message'=>$message
],$ok?200:502);
