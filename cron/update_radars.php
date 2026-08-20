<?php
declare(strict_types=1);
if (PHP_SAPI !== 'cli') { http_response_code(403); exit("CLI only\n"); }

require dirname(__DIR__) . '/api/bootstrap.php';
require_once dirname(__DIR__) . '/api/radar_route_helpers.php';
require_once dirname(__DIR__) . '/api/radar_official_es.php';
require_once dirname(__DIR__) . '/api/radar_brazil_sources.php';

$started=microtime(true);
$result=['time'=>date(DATE_ATOM),'version'=>MUSICROAD_VERSION];
try{$result['antt']=mr_sync_antt_national_to_db();}catch(Throwable $e){$result['antt']=['ok'=>false,'error'=>$e->getMessage()];}
try{$result['der_es']=mr_sync_der_es_to_db();}catch(Throwable $e){$result['der_es']=['ok'=>false,'error'=>$e->getMessage()];}

$lifecycle=[];
try{
    $lifecycle['expired_reports']=db()->exec("UPDATE road_reports SET status='EXPIRADO',reviewed_at=COALESCE(reviewed_at,CURRENT_TIMESTAMP),review_note=COALESCE(review_note,'Prazo de validação encerrado automaticamente.') WHERE status IN ('PENDENTE','NAO_CONFIRMADO') AND expires_at<CURRENT_TIMESTAMP");
    $lifecycle['expired_alerts']=db()->exec("UPDATE radars SET ativo=0,situacao='EXPIRADO' WHERE ativo=1 AND expires_at IS NOT NULL AND expires_at<CURRENT_TIMESTAMP");
    $lifecycle['stale_osm']=db()->exec("UPDATE radars SET ativo=0,situacao='REVALIDAR_FONTE' WHERE ativo=1 AND fonte='OPENSTREETMAP' AND data_importacao<DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 180 DAY)");
    $lifecycle['old_auth_attempts']=db()->exec("DELETE FROM auth_attempts WHERE created_at<DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 90 DAY)");
    $pruned=0;
    foreach([
        dirname(__DIR__).'/storage/offline-cities'=>30*86400,
        dirname(__DIR__).'/storage/offline-states'=>14*86400,
        dirname(__DIR__).'/storage/offline-regions'=>30*86400,
        dirname(__DIR__).'/storage/cache/radars'=>14*86400,
    ] as $dir=>$maxAge){
        if(!is_dir($dir))continue;
        foreach(new DirectoryIterator($dir) as $file){
            if($file->isDot()||!$file->isFile())continue;
            if(!preg_match('/\.(?:json|meta|lock|cache)$/',$file->getFilename()))continue;
            if($file->getMTime()>=time()-$maxAge)continue;
            if(@unlink($file->getPathname()))$pruned++;
        }
    }
    $cnefePruned=0;$cnefeDir=dirname(__DIR__).'/storage/cnefe';
    if(is_dir($cnefeDir))foreach(new DirectoryIterator($cnefeDir) as $file){
        if($file->isDot()||!$file->isFile())continue;
        if(str_ends_with($file->getFilename(),'.part')){if($file->getMTime()<time()-86400)@unlink($file->getPathname());continue;}
        if(!preg_match('/^(\d{7})\.zip$/',$file->getFilename(),$match)||$file->getMTime()>=time()-30*86400)continue;
        $code=$match[1];if(!@unlink($file->getPathname()))continue;$cnefePruned++;
        db()->prepare("UPDATE address_local_packs SET zip_path=NULL,status='indexed',updated_at=CURRENT_TIMESTAMP WHERE municipality_code=?")->execute([$code]);
        db()->prepare('DELETE FROM address_local_points WHERE municipality_code=?')->execute([$code]);
        db()->prepare('DELETE FROM address_local_street_loads WHERE municipality_code=?')->execute([$code]);
    }
    $lifecycle['offline_cache_files_pruned']=$pruned;
    $lifecycle['cnefe_archives_pruned']=$cnefePruned;
    $lifecycle['ok']=true;
}catch(Throwable $e){$lifecycle=['ok'=>false,'error'=>$e->getMessage()];}
$result['lifecycle']=$lifecycle;
$result['ok']=(bool)($result['antt']['ok']??false)||(bool)($result['der_es']['ok']??false);
$result['timing_ms']=(int)round((microtime(true)-$started)*1000);
echo json_encode($result,JSON_PRETTY_PRINT|JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES).PHP_EOL;
