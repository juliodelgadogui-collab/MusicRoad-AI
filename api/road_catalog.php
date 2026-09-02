<?php
declare(strict_types=1);
require __DIR__.'/bootstrap.php';
require_login();
require_once __DIR__.'/radar_db.php';
require_once __DIR__.'/road_hazard_db.php';

header('Cache-Control: private, max-age=21600');

$uf=strtoupper(trim((string)($_GET['uf']??'')));
$allowed=['SP','MG','RJ','ES'];
if(!in_array($uf,$allowed,true))json_response(['ok'=>false,'error'=>'Rodovias disponíveis nesta fase apenas para SP, MG, RJ e ES.'],422);

$defaults=[
    'SP'=>['BR-050','BR-101','BR-116','BR-153','BR-381','SP-070','SP-075','SP-160','SP-270','SP-280','SP-300','SP-310','SP-330','SP-348'],
    'MG'=>['BR-040','BR-050','BR-116','BR-135','BR-153','BR-251','BR-262','BR-265','BR-267','BR-365','BR-381','BR-459','MG-010','MG-050','MG-290','MG-424'],
    'RJ'=>['BR-040','BR-101','BR-116','BR-393','BR-465','RJ-104','RJ-106','RJ-116','RJ-124'],
    'ES'=>['BR-101','BR-259','BR-262','ES-010','ES-060','ES-080','ES-164','ES-248'],
];

$counts=[];
$addRoads=static function($raw,int $weight=1) use (&$counts,$allowed): void {
    $raw=strtoupper(trim((string)$raw));
    if($raw==='')return;
    if(!preg_match_all('/\b(BR|SP|MG|RJ|ES)\s*[- ]?\s*(\d{2,3})\b/u',$raw,$m,PREG_SET_ORDER))return;
    foreach($m as $hit){
        $prefix=$hit[1];
        if($prefix!=='BR'&&!in_array($prefix,$allowed,true))continue;
        $road=$prefix.'-'.str_pad((string)((int)$hit[2]),3,'0',STR_PAD_LEFT);
        $counts[$road]=($counts[$road]??0)+max(1,$weight);
    }
};

foreach($defaults[$uf] as $r)$counts[$r]=0;

try{
    radar_ensure_tables();
    [$s,$w,$n,$e]=ep2_state_bounds($uf);
    $stmt=db()->prepare("SELECT rodovia,COUNT(*) total FROM radars WHERE ativo=1 AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? AND TRIM(COALESCE(rodovia,''))<>'' GROUP BY rodovia ORDER BY total DESC LIMIT 1200");
    $stmt->execute([$s,$n,$w,$e]);
    foreach(($stmt->fetchAll()?:[]) as $r)$addRoads($r['rodovia']??'',(int)($r['total']??1));
}catch(Throwable $e){}

try{
    road_hazard_ensure_tables();
    $stmt=db()->prepare("SELECT road,COUNT(*) total FROM road_hazards WHERE active=1 AND UPPER(COALESCE(uf,''))=? AND TRIM(COALESCE(road,''))<>'' GROUP BY road ORDER BY total DESC LIMIT 2500");
    $stmt->execute([$uf]);
    foreach(($stmt->fetchAll()?:[]) as $r)$addRoads($r['road']??'',(int)($r['total']??1));
}catch(Throwable $e){}

$roads=[];
foreach($counts as $road=>$count){
    $prefix=substr($road,0,2);
    if($prefix!=='BR'&&$prefix!==$uf)continue;
    $roads[]=['key'=>$road,'label'=>$road,'count'=>$count];
}
usort($roads,static function(array $a,array $b): int {
    $ac=(int)($a['count']??0);$bc=(int)($b['count']??0);
    if($ac!==$bc)return $bc<=>$ac;
    $ap=str_starts_with((string)$a['key'],'BR-')?0:1;$bp=str_starts_with((string)$b['key'],'BR-')?0:1;
    if($ap!==$bp)return $ap<=>$bp;
    return strnatcasecmp((string)$a['key'],(string)$b['key']);
});

json_response([
    'ok'=>true,
    'uf'=>$uf,
    'roads'=>$roads,
    'total'=>count($roads),
    'generated_at'=>gmdate('c'),
]);
