<?php
declare(strict_types=1);

require_once __DIR__.'/road_safety_pack_helpers.php';

function epm_road_query_for_line(array $line,int $widthM): string {
    $parts=[];
    foreach($line as $p){
        $parts[]=rtrim(rtrim(number_format((float)$p[0],6,'.',''),'0'),'.').','.rtrim(rtrim(number_format((float)$p[1],6,'.',''),'0'),'.');
    }
    $around='(around:'.max(7000,min(22000,$widthM)).','.implode(',',$parts).')';
    return '[out:json][timeout:80];('
        .'way["highway"~"^(motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link)$"]'.$around.';'
        .');out tags geom qt;';
}

function epm_road_query_for_state(string $uf): string {
    $uf=strtoupper($uf);
    return '[out:json][timeout:110];'
        .'area["ISO3166-2"="BR-'.$uf.'"][boundary="administrative"]->.epmap;('
        .'way["highway"~"^(motorway|motorway_link|trunk|trunk_link|primary|primary_link)$"](area.epmap);'
        .');out tags geom qt;';
}

function epm_geometry(array $geom,int $maxPoints=900): array {
    $out=[];$lastLat=null;$lastLon=null;$minGap=55.0;
    $count=count($geom);
    foreach($geom as $i=>$p){
        if(!isset($p['lat'],$p['lon'])||!is_numeric($p['lat'])||!is_numeric($p['lon']))continue;
        $lat=(float)$p['lat'];$lon=(float)$p['lon'];
        $must=$i===0||$i===$count-1;
        if(!$must&&$lastLat!==null&&ep2_haversine($lastLat,$lastLon,$lat,$lon)<$minGap)continue;
        $out[]=[$lon,$lat];$lastLat=$lat;$lastLon=$lon;
        if(count($out)>=$maxPoints&&$i<$count-1)break;
    }
    if(count($out)<2&&$count>=2){
        $a=$geom[0];$b=$geom[$count-1];
        if(isset($a['lat'],$a['lon'],$b['lat'],$b['lon']))$out=[[(float)$a['lon'],(float)$a['lat']],[(float)$b['lon'],(float)$b['lat']]];
    }
    return $out;
}

function epm_feature_from_way(array $el): ?array {
    $geom=is_array($el['geometry']??null)?$el['geometry']:[];
    $coords=epm_geometry($geom);
    if(count($coords)<2)return null;
    $tags=is_array($el['tags']??null)?$el['tags']:[];
    $class=(string)($tags['highway']??'road');
    $ref=trim((string)($tags['ref']??''));
    $name=trim((string)($tags['name']??''));
    $oneway=strtolower((string)($tags['oneway']??''));
    return [
        'type'=>'Feature',
        'id'=>'osm-way-'.(string)($el['id']??md5(json_encode($coords))),
        'properties'=>[
            'class'=>$class,
            'ref'=>$ref,
            'name'=>$name,
            'oneway'=>in_array($oneway,['yes','1','true','-1'],true),
            'maxspeed'=>ep2_speed($tags['maxspeed']??null),
            'source'=>'OpenStreetMap'
        ],
        'geometry'=>['type'=>'LineString','coordinates'=>$coords]
    ];
}

function epm_features(array $elements,int $maxFeatures=12000,int $maxCoordinates=180000): array {
    $features=[];$coords=0;
    foreach($elements as $el){
        if(($el['type']??'')!=='way')continue;
        $f=epm_feature_from_way($el);if($f===null)continue;
        $n=count($f['geometry']['coordinates']);
        if($coords+$n>$maxCoordinates)break;
        $features[]=$f;$coords+=$n;
        if(count($features)>=$maxFeatures)break;
    }
    return [$features,$coords];
}

function epm_bounds_from_line(array $line,int $padM): array {
    $lats=array_column($line,0);$lons=array_column($line,1);
    $midLat=array_sum($lats)/max(1,count($lats));
    $padLat=$padM/110540.0;
    $padLon=$padM/(111320.0*max(0.25,cos(deg2rad($midLat))));
    return [min($lats)-$padLat,min($lons)-$padLon,max($lats)+$padLat,max($lons)+$padLon];
}
