<?php
declare(strict_types=1);
require __DIR__.'/bootstrap.php';
require_login();
require_once __DIR__.'/road_safety_pack_helpers.php';
require_once __DIR__.'/road_map_helpers.php';
require_once __DIR__.'/radar_db.php';
require_once __DIR__.'/road_hazard_db.php';
@set_time_limit(150);

header('Cache-Control: private, max-age=21600');

$kind=strtolower(trim((string)($_GET['kind']??'radars')));
$uf=strtoupper(trim((string)($_GET['uf']??'')));
$road=strtoupper(trim((string)($_GET['road']??'')));
if(!in_array($uf,['SP','MG','RJ','ES'],true))json_response(['ok'=>false,'error'=>'UF inválida.'],422);
if(!preg_match('/^(BR|SP|MG|RJ|ES)-(\d{3})$/',$road,$rm))json_response(['ok'=>false,'error'=>'Rodovia inválida.'],422);
if($rm[1]!=='BR'&&$rm[1]!==$uf)json_response(['ok'=>false,'error'=>'A rodovia selecionada não pertence ao estado.'],422);
if(!in_array($kind,['map','radars'],true))json_response(['ok'=>false,'error'=>'Tipo de pacote inválido.'],422);

$canonical=static function($raw): array {
    $raw=strtoupper(trim((string)$raw));$out=[];
    if(preg_match_all('/\b(BR|SP|MG|RJ|ES)\s*[- ]?\s*(\d{2,3})\b/u',$raw,$m,PREG_SET_ORDER)){
        foreach($m as $hit)$out[]=$hit[1].'-'.str_pad((string)((int)$hit[2]),3,'0',STR_PAD_LEFT);
    }
    return array_values(array_unique($out));
};
$matches=static function($raw) use ($canonical,$road): bool {return in_array($road,$canonical($raw),true);};

if($kind==='radars'){
    radar_ensure_tables();road_hazard_ensure_tables();
    [$minLat,$minLon,$maxLat,$maxLon]=ep2_state_bounds($uf);
    $items=[];$seen=[];
    $add=static function(array $r) use (&$items,&$seen,$uf,$matches): void {
        $rawRoad=(string)($r['rodovia']??$r['road']??'');if(!$matches($rawRoad))return;
        $lat=(float)($r['latitude']??0);$lon=(float)($r['longitude']??0);if(!$lat||!$lon)return;
        $rowUf=strtoupper(trim((string)($r['uf']??'')));if($rowUf!==''&&$rowUf!==$uf)return;
        $type=strtoupper(trim((string)($r['tipo']??$r['type']??'RADAR')));
        if(stripos($type,'QUEBRA')!==false||stripos($type,'LOMB')!==false)$type='QUEBRA_MOLAS';
        elseif(stripos($type,'SEM')===0)$type='SEMAFORO';
        elseif(stripos($type,'PED')===0)$type='PEDAGIO';
        elseif(stripos($type,'PASS')===0)$type='PASSAGEM_NIVEL';
        else $type='RADAR';
        $id=trim((string)($r['external_id']??$r['hazard_key']??$r['id']??''));if($id==='')$id='geo-'.$type.'-'.round($lat,5).'-'.round($lon,5);
        $geo=$type.'|'.round($lat,5).'|'.round($lon,5);if(isset($seen[$id])||isset($seen[$geo]))return;$seen[$id]=true;$seen[$geo]=true;
        $items[]=['external_id'=>$id,'latitude'=>$lat,'longitude'=>$lon,'rodovia'=>$rawRoad,'heading'=>isset($r['heading'])&&is_numeric($r['heading'])?(float)$r['heading']:ep2_heading($r['sentido']??null),'sentido'=>(string)($r['sentido']??''),'velocidade'=>ep2_speed($r['velocidade']??$r['speed']??null),'tipo'=>$type,'fonte'=>(string)($r['fonte']??$r['source']??'BASE_LOCAL'),'uf'=>$uf];
    };
    try{
        $stmt=db()->prepare('SELECT id,external_id,latitude,longitude,uf,rodovia,heading,sentido,velocidade,tipo,fonte FROM radars WHERE ativo=1 AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? ORDER BY id ASC LIMIT 50000');
        $stmt->execute([$minLat,$maxLat,$minLon,$maxLon]);foreach(($stmt->fetchAll()?:[]) as $r)$add($r);
    }catch(Throwable $e){}
    try{foreach(road_hazard_state_rows($uf,65000) as $r)$add($r);}catch(Throwable $e){}
    json_response(['ok'=>true,'kind'=>'radars','uf'=>$uf,'road'=>$road,'generated_at'=>gmdate('c'),'radars'=>$items,'total'=>count($items)]);
}

$features=[];$coordinateCount=0;$osmOk=false;$message='';
try{
    global $config;
    $digits=substr($road,3);$prefix=substr($road,0,2);
    $refRegex='(^|[; ,/])'.preg_quote($prefix,'/').'[- ]?0*'.((int)$digits).'($|[; ,/])';
    $query='[out:json][timeout:110];area["ISO3166-2"="BR-'.$uf.'"][boundary="administrative"]->.epmap;('
        .'way["highway"~"^(motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link)$"]["ref"~"'.$refRegex.'",i](area.epmap);'
        .');out tags geom qt;';
    $osm=http_json($config['routing']['overpass_url'],'data='.urlencode($query),['Content-Type: application/x-www-form-urlencoded']);
    if(is_array($osm)){[$features,$coordinateCount]=epm_features($osm['elements']??[],9000,160000);$osmOk=count($features)>0;}
}catch(Throwable $e){$message='Não foi possível atualizar esta rodovia agora.';}

json_response([
    'ok'=>$osmOk,
    'kind'=>'road_map',
    'uf'=>$uf,
    'road'=>$road,
    'generated_at'=>gmdate('c'),
    'expires_in_s'=>$osmOk?2592000:3600,
    'map'=>['roads'=>['type'=>'FeatureCollection','features'=>$features]],
    'coverage'=>['osm_ok'=>$osmOk,'features'=>count($features),'coordinates'=>$coordinateCount],
    'message'=>$message,
],$osmOk?200:502);
