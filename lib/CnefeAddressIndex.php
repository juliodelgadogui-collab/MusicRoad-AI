<?php
declare(strict_types=1);

// MusicRoad AI v1.2.0 — índice local de endereços baseado nos arquivos públicos CNEFE 2022 do IBGE.
// Não usa Mapbox/Google para geocodificação. O arquivo do município é baixado uma vez e pesquisado localmente.

function cnefe_storage_dir(): string {
    $dir = dirname(__DIR__) . '/storage/cnefe';
    if (!is_dir($dir)) @mkdir($dir, 0775, true);
    return $dir;
}

function cnefe_ensure_tables(): void { ensure_schema(); }

function cnefe_fold(string $s): string {
    $s = trim($s);
    if ($s === '') return '';
    if (!mb_check_encoding($s, 'UTF-8')) {
        $s = mb_convert_encoding($s, 'UTF-8', ['Windows-1252','ISO-8859-1','UTF-8']);
    }
    $s = mb_strtoupper($s, 'UTF-8');
    if (class_exists('Transliterator')) {
        $tr = Transliterator::create('NFD; [:Nonspacing Mark:] Remove; NFC');
        if ($tr) $s = (string)$tr->transliterate($s);
    } else {
        $ascii = @iconv('UTF-8', 'ASCII//TRANSLIT//IGNORE', $s);
        if (is_string($ascii) && $ascii !== '') $s = $ascii;
    }
    $s = preg_replace('/[^A-Z0-9]+/u', ' ', $s) ?? $s;
    return trim(preg_replace('/\s+/u', ' ', $s) ?? $s);
}

function cnefe_utf8(string $s): string {
    if ($s === '') return '';
    $s = preg_replace('/^\xEF\xBB\xBF/', '', $s) ?? $s;
    if (mb_check_encoding($s, 'UTF-8')) return trim($s);
    return trim((string)mb_convert_encoding($s, 'UTF-8', ['Windows-1252','ISO-8859-1']));
}

function cnefe_decimal($v): ?float {
    if ($v === null) return null;
    $s = trim((string)$v);
    if ($s === '') return null;
    if (str_contains($s, ',') && !str_contains($s, '.')) $s = str_replace(',', '.', $s);
    $s = preg_replace('/[^0-9.\-]/', '', $s) ?? '';
    if ($s === '' || !is_numeric($s)) return null;
    $n = (float)$s;
    return is_finite($n) ? $n : null;
}

function cnefe_http_text(string $url, int $timeout = 30): ?string {
    $maxBytes=2097152;
    if (function_exists('curl_init')) {
        $ch = curl_init($url);$raw='';$tooLarge=false;
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER=>false, CURLOPT_FOLLOWLOCATION=>true,
            CURLOPT_CONNECTTIMEOUT=>10, CURLOPT_TIMEOUT=>$timeout,
            CURLOPT_ENCODING=>'', CURLOPT_HTTPHEADER=>['User-Agent: MusicRoad-AI/1.2.0','Accept: text/html,*/*'],
            CURLOPT_WRITEFUNCTION=>static function($handle,string $chunk)use(&$raw,&$tooLarge,$maxBytes):int{$length=strlen($chunk);if(strlen($raw)+$length>$maxBytes){$tooLarge=true;return 0;}$raw.=$chunk;return $length;}
        ]);
        $ok = curl_exec($ch); $status=(int)curl_getinfo($ch,CURLINFO_RESPONSE_CODE); curl_close($ch);
        return $ok!==false&&!$tooLarge&&$status>=200&&$status<400 ? $raw : null;
    }
    $ctx=stream_context_create(['http'=>['timeout'=>$timeout,'header'=>"User-Agent: MusicRoad-AI/1.2.0\r\n"]]);
    $raw=@file_get_contents($url,false,$ctx,0,$maxBytes+1); return is_string($raw)&&strlen($raw)<=$maxBytes?$raw:null;
}

function cnefe_download_file(string $url, string $path, int $timeout = 240): bool {
    $maxBytes=1073741824;$tmp=$path.'.part'; @unlink($tmp);
    $fp=@fopen($tmp,'wb'); if(!$fp)return false;
    $ok=false;$written=0;$tooLarge=false;
    if(function_exists('curl_init')){
        $ch=curl_init($url);
        curl_setopt_array($ch,[
          CURLOPT_RETURNTRANSFER=>false,CURLOPT_FOLLOWLOCATION=>true,CURLOPT_CONNECTTIMEOUT=>15,CURLOPT_TIMEOUT=>$timeout,
          CURLOPT_HTTPHEADER=>['User-Agent: MusicRoad-AI/1.2.0','Accept: application/zip,*/*'],CURLOPT_ENCODING=>'',
          CURLOPT_WRITEFUNCTION=>static function($handle,string $chunk)use($fp,&$written,&$tooLarge,$maxBytes):int{$length=strlen($chunk);if($written+$length>$maxBytes){$tooLarge=true;return 0;}$out=fwrite($fp,$chunk);if($out===false)return 0;$written+=$out;return $out;}
        ]);
        $res=curl_exec($ch);$status=(int)curl_getinfo($ch,CURLINFO_RESPONSE_CODE);curl_close($ch);
        $ok=$res!==false&&!$tooLarge&&$status>=200&&$status<400;
    }else{
        $ctx=stream_context_create(['http'=>['timeout'=>$timeout,'header'=>"User-Agent: MusicRoad-AI/1.2.0\r\n"]]);
        $in=@fopen($url,'rb',false,$ctx);if($in){$copied=stream_copy_to_stream($in,$fp,$maxBytes+1);fclose($in);$ok=is_int($copied)&&$copied>0&&$copied<=$maxBytes;}
    }
    fclose($fp);
    if(!$ok||!is_file($tmp)||filesize($tmp)<100||filesize($tmp)>$maxBytes){@unlink($tmp);return false;}
    @chmod($tmp,0660);if(!@rename($tmp,$path)){@unlink($tmp);return false;}return is_file($path);
}

function cnefe_source_url(string $municipalityCode, string $uf): ?string {
    if (!preg_match('/^\d{7}$/',$municipalityCode) || !preg_match('/^[A-Z]{2}$/',$uf)) return null;
    $stateCode=substr($municipalityCode,0,2);
    $base='https://ftp.ibge.gov.br/Cadastro_Nacional_de_Enderecos_para_Fins_Estatisticos/Censo_Demografico_2022/Arquivos_CNEFE/CSV/Municipio/'.$stateCode.'_'.$uf.'/';
    $html=cnefe_http_text($base,35); if($html===null)return null;
    if(!preg_match_all('~href=["\']([^"\']+\.zip)["\']~i',$html,$m))return null;
    foreach($m[1] as $href){
        $decoded=rawurldecode(html_entity_decode((string)$href,ENT_QUOTES|ENT_HTML5,'UTF-8'));
        $name=basename($decoded);
        if(preg_match('/^'.preg_quote($municipalityCode,'/').'_.*\.zip$/i',$name)){
            return $base.rawurlencode($name);
        }
    }
    return null;
}

function cnefe_pack(string $municipalityCode): ?array {
    cnefe_ensure_tables();
    $st=db()->prepare('SELECT * FROM address_local_packs WHERE municipality_code=? LIMIT 1');$st->execute([$municipalityCode]);$r=$st->fetch();return $r?:null;
}

function cnefe_set_pack(string $code,string $uf,string $city,array $fields): void {
    cnefe_ensure_tables();
    $existing=cnefe_pack($code)?:[];
    $row=array_merge(['source_url'=>null,'zip_path'=>null,'status'=>'new','source_rows'=>0,'street_count'=>0,'downloaded_at'=>null,'indexed_at'=>null,'last_error'=>null],$existing,$fields);
    $st=db()->prepare("INSERT INTO address_local_packs(municipality_code,uf,city,source_url,zip_path,status,source_rows,street_count,downloaded_at,indexed_at,last_error,updated_at)
      VALUES(?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
      ON DUPLICATE KEY UPDATE uf=VALUES(uf),city=VALUES(city),source_url=VALUES(source_url),zip_path=VALUES(zip_path),status=VALUES(status),source_rows=VALUES(source_rows),street_count=VALUES(street_count),downloaded_at=VALUES(downloaded_at),indexed_at=VALUES(indexed_at),last_error=VALUES(last_error),updated_at=CURRENT_TIMESTAMP");
    $st->execute([$code,$uf,$city,$row['source_url'],$row['zip_path'],$row['status'],(int)$row['source_rows'],(int)$row['street_count'],$row['downloaded_at'],$row['indexed_at'],$row['last_error']]);
}

function cnefe_open_csv(string $zipPath): array {
    if(!class_exists('ZipArchive'))throw new RuntimeException('Extensão PHP ZipArchive não está habilitada no servidor.');
    $zip=new ZipArchive();$r=$zip->open($zipPath);if($r!==true)throw new RuntimeException('Não foi possível abrir a base CNEFE baixada.');
    $entry='';for($i=0;$i<$zip->numFiles;$i++){ $n=(string)$zip->getNameIndex($i); if(preg_match('/\.csv$/i',$n)){ $entry=$n;break; } }
    if($entry===''){ $zip->close();throw new RuntimeException('O arquivo CNEFE não contém CSV.'); }
    $stream=$zip->getStream($entry);if(!is_resource($stream)){ $zip->close();throw new RuntimeException('Não foi possível ler o CSV do CNEFE.'); }
    return [$zip,$stream,$entry];
}

function cnefe_csv_header($stream): array {
    $line=fgets($stream);if($line===false)throw new RuntimeException('CSV do CNEFE vazio.');
    $counts=[';'=>substr_count($line,';'),','=>substr_count($line,','),"\t"=>substr_count($line,"\t"),'|'=>substr_count($line,'|')];
    arsort($counts);$delimiter=(string)array_key_first($counts);if(($counts[$delimiter]??0)<3)$delimiter=';';
    $header=str_getcsv($line,$delimiter,'"','\\');$map=[];
    foreach($header as $i=>$name){$n=mb_strtoupper(cnefe_utf8((string)$name),'UTF-8');$n=preg_replace('/[^A-Z0-9_]/','',$n)??$n;$map[$n]=$i;}
    return [$map,$delimiter,count($header)];
}

function cnefe_val(array $row,array $map,string $key): string {
    $i=$map[$key]??null;return $i===null?'':cnefe_utf8((string)($row[$i]??''));
}

function cnefe_street(array $row,array $map): array {
    $type=cnefe_val($row,$map,'NOM_TIPO_SEGLOGR');$title=cnefe_val($row,$map,'NOM_TITULO_SEGLOGR');$name=cnefe_val($row,$map,'NOM_SEGLOGR');
    $parts=array_values(array_filter([$type,$title,$name],fn($x)=>trim((string)$x)!==''));
    $street=trim(preg_replace('/\s+/u',' ',implode(' ',$parts))??implode(' ',$parts));
    return [$street,$name];
}

function cnefe_index_zip(string $code,string $uf,string $city,string $zipPath,string $sourceUrl=''): array {
    cnefe_ensure_tables();
    [$zip,$stream]=cnefe_open_csv($zipPath);[$map,$delimiter]=cnefe_csv_header($stream);
    foreach(['NOM_SEGLOGR','LATITUDE','LONGITUDE'] as $required){if(!isset($map[$required])){fclose($stream);$zip->close();throw new RuntimeException('Base CNEFE sem coluna obrigatória '.$required.'.');}}
    $streets=[];$rows=0;
    while(($row=fgetcsv($stream,0,$delimiter,'"','\\'))!==false){
        $rows++;[$street,$streetName]=cnefe_street($row,$map);if($street==='')continue;
        $streetFold=cnefe_fold($street);$nameFold=cnefe_fold($streetName);if($streetFold==='')continue;
        $locality=cnefe_val($row,$map,'DSC_LOCALIDADE');$locFold=cnefe_fold($locality);$cep=preg_replace('/\D/','',cnefe_val($row,$map,'CEP'))??'';
        $lat=cnefe_decimal(cnefe_val($row,$map,'LATITUDE'));$lon=cnefe_decimal(cnefe_val($row,$map,'LONGITUDE'));$geo=(int)cnefe_val($row,$map,'NV_GEO_COORD');
        $key=$streetFold.'|'.$locFold;
        if(!isset($streets[$key]))$streets[$key]=[$street,$streetFold,$nameFold,$locality,$locFold,$cep,0.0,0.0,0,$geo>0?$geo:99,0];
        if($cep!==''&&$streets[$key][5]==='')$streets[$key][5]=$cep;
        if($geo>0&&$geo<$streets[$key][9])$streets[$key][9]=$geo;
        if($lat!==null&&$lon!==null&&abs($lat)<=90&&abs($lon)<=180){$streets[$key][6]+=$lat;$streets[$key][7]+=$lon;$streets[$key][8]++;}
        $streets[$key][10]++;
    }
    fclose($stream);$zip->close();
    $pdo=db();$pdo->beginTransaction();
    try{
        $del=$pdo->prepare('DELETE FROM address_local_streets WHERE municipality_code=?');$del->execute([$code]);
        $pdo->prepare('DELETE FROM address_local_points WHERE municipality_code=?')->execute([$code]);
        $pdo->prepare('DELETE FROM address_local_street_loads WHERE municipality_code=?')->execute([$code]);
        $ins=$pdo->prepare('INSERT INTO address_local_streets(municipality_code,uf,city,street,street_fold,street_name_fold,locality,locality_fold,cep,latitude,longitude,geo_level,sample_count) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)');
        foreach($streets as $v){$n=$v[8];$lat=$n>0?$v[6]/$n:null;$lon=$n>0?$v[7]/$n:null;$ins->execute([$code,$uf,$city,$v[0],$v[1],$v[2],$v[3],$v[4],$v[5],$lat,$lon,$v[9]===99?null:$v[9],$v[10]]);}
        $pdo->commit();
    }catch(Throwable $e){if($pdo->inTransaction())$pdo->rollBack();throw $e;}
    cnefe_set_pack($code,$uf,$city,['source_url'=>$sourceUrl,'zip_path'=>$zipPath,'status'=>'ready','source_rows'=>$rows,'street_count'=>count($streets),'downloaded_at'=>date('Y-m-d H:i:s',(int)@filemtime($zipPath)),'indexed_at'=>date('Y-m-d H:i:s'),'last_error'=>null]);
    return ['status'=>'ready','source_rows'=>$rows,'street_count'=>count($streets),'zip_path'=>$zipPath];
}

function cnefe_prepare_city(string $code,string $uf,string $city,bool $force=false): array {
    cnefe_ensure_tables();$code=trim($code);$uf=strtoupper(trim($uf));$city=trim($city);
    if(!preg_match('/^\d{7}$/',$code))throw new InvalidArgumentException('Código IBGE do município inválido.');
    if(!preg_match('/^[A-Z]{2}$/',$uf))throw new InvalidArgumentException('UF inválida.');
    $pack=cnefe_pack($code);$zipPath=cnefe_storage_dir().'/'.$code.'.zip';
    $cachedStatus=(string)($pack['status']??'');
    if(!$force&&$pack&&in_array($cachedStatus,['ready','indexed'],true)&&(int)($pack['street_count']??0)>0){
        return ['status'=>'ready','street_count'=>(int)$pack['street_count'],'source_rows'=>(int)$pack['source_rows'],'cached'=>true,'archive_available'=>is_file($zipPath)];
    }
    $lockPath=cnefe_storage_dir().'/'.$code.'.lock';$lock=@fopen($lockPath,'c+');
    if(!$lock||!flock($lock,LOCK_EX|LOCK_NB)){if($lock)fclose($lock);return ['status'=>'preparing','message'=>'Base do município já está sendo preparada.'];}
    ignore_user_abort(true);@set_time_limit(300);
    try{
        cnefe_set_pack($code,$uf,$city,['status'=>'downloading','last_error'=>null]);
        $source=$pack['source_url']??'';if(!is_string($source)||$source==='')$source=cnefe_source_url($code,$uf)??'';
        if($source==='')throw new RuntimeException('Não foi possível localizar o arquivo público do município no IBGE.');
        if($force||!is_file($zipPath)||filesize($zipPath)<100){if(!cnefe_download_file($source,$zipPath,260))throw new RuntimeException('Não foi possível baixar a base pública do município no IBGE.');}
        cnefe_set_pack($code,$uf,$city,['source_url'=>$source,'zip_path'=>$zipPath,'status'=>'indexing','downloaded_at'=>date('Y-m-d H:i:s'),'last_error'=>null]);
        return cnefe_index_zip($code,$uf,$city,$zipPath,$source);
    }catch(Throwable $e){cnefe_set_pack($code,$uf,$city,['source_url'=>$source??null,'zip_path'=>is_file($zipPath)?$zipPath:null,'status'=>'error','last_error'=>$e->getMessage()]);throw $e;}
    finally{flock($lock,LOCK_UN);fclose($lock);}
}

function cnefe_query_streets(string $code,string $q,int $limit=15): array {
    $fold=cnefe_fold($q);if($fold==='')return[];$contains='%'.$fold.'%';$prefix=$fold.'%';$raw=preg_replace('/\D/','',$q)??'';
    $sql="SELECT *, CASE WHEN street_fold=:exact THEN 0 WHEN street_name_fold=:exact THEN 1 WHEN street_fold LIKE :prefix THEN 2 WHEN street_name_fold LIKE :prefix THEN 3 WHEN locality_fold LIKE :prefix THEN 4 ELSE 5 END AS rank_score
      FROM address_local_streets WHERE municipality_code=:code AND (street_fold LIKE :contains OR street_name_fold LIKE :contains OR locality_fold LIKE :contains".($raw!==''?" OR cep LIKE :cep":"").") ORDER BY rank_score ASC, sample_count DESC, street ASC LIMIT ".max(1,min(30,$limit));
    $st=db()->prepare($sql);$params=[':exact'=>$fold,':prefix'=>$prefix,':code'=>$code,':contains'=>$contains];if($raw!=='')$params[':cep']=$raw.'%';$st->execute($params);return $st->fetchAll()?:[];
}

function cnefe_parse_query(string $q): array {
    $q=trim(preg_replace('/\s+/u',' ',$q)??$q);$number='';$streetQ=$q;
    if(preg_match('/^(.*?)[,\s]+(\d{1,6}[A-Za-z]?)$/u',$q,$m)&&mb_strlen(trim($m[1]))>=2){$streetQ=trim($m[1]);$number=trim($m[2]);}
    return [$streetQ,$number];
}

function cnefe_load_points_for_streets(string $code,array $streetFolds): void {
    cnefe_ensure_tables();$streetFolds=array_values(array_unique(array_filter(array_map('strval',$streetFolds))));if(!$streetFolds)return;
    $loaded=[];$st=db()->prepare('SELECT street_fold FROM address_local_street_loads WHERE municipality_code=? AND street_fold=?');
    foreach($streetFolds as $f){$st->execute([$code,$f]);if($st->fetchColumn()!==false)$loaded[$f]=true;}
    $need=array_values(array_filter($streetFolds,fn($f)=>!isset($loaded[$f])));if(!$need)return;
    $pack=cnefe_pack($code);$zipPath=(string)($pack['zip_path']??'');if($zipPath===''||!is_file($zipPath))throw new RuntimeException('Base local do município precisa ser baixada novamente.');@touch($zipPath);
    [$zip,$stream]=cnefe_open_csv($zipPath);[$map,$delimiter]=cnefe_csv_header($stream);$wanted=array_fill_keys($need,true);$counts=array_fill_keys($need,0);
    $pdo=db();$pdo->beginTransaction();$ins=$pdo->prepare('INSERT IGNORE INTO address_local_points(municipality_code,street_fold,street,number_text,number_int,locality,cep,latitude,longitude,geo_level) VALUES(?,?,?,?,?,?,?,?,?,?)');
    try{
        while(($row=fgetcsv($stream,0,$delimiter,'"','\\'))!==false){[$street]=cnefe_street($row,$map);if($street==='')continue;$fold=cnefe_fold($street);if(!isset($wanted[$fold]))continue;
            $lat=cnefe_decimal(cnefe_val($row,$map,'LATITUDE'));$lon=cnefe_decimal(cnefe_val($row,$map,'LONGITUDE'));if($lat===null||$lon===null||abs($lat)>90||abs($lon)>180)continue;
            $num=cnefe_val($row,$map,'NUM_ENDERECO');$mod=cnefe_val($row,$map,'DSC_MODIFICADOR');$numberText=trim($num.($mod!==''?' '.$mod:''));$numInt=null;if(preg_match('/\d+/',str_replace('.','',$num),$m))$numInt=(int)$m[0];
            $locality=cnefe_val($row,$map,'DSC_LOCALIDADE');$cep=preg_replace('/\D/','',cnefe_val($row,$map,'CEP'))??'';$geo=(int)cnefe_val($row,$map,'NV_GEO_COORD');
            $ins->execute([$code,$fold,$street,$numberText,$numInt,$locality,$cep,$lat,$lon,$geo>0?$geo:null]);$counts[$fold]++;
        }
        $mark=$pdo->prepare("INSERT INTO address_local_street_loads(municipality_code,street_fold,loaded_at,point_count) VALUES(?,?,CURRENT_TIMESTAMP,?) ON DUPLICATE KEY UPDATE loaded_at=CURRENT_TIMESTAMP,point_count=VALUES(point_count)");
        foreach($need as $f)$mark->execute([$code,$f,$counts[$f]??0]);$pdo->commit();
    }catch(Throwable $e){if($pdo->inTransaction())$pdo->rollBack();throw $e;}finally{fclose($stream);$zip->close();}
}

function cnefe_number_fold(string $s): string { return preg_replace('/[^A-Z0-9]/','',cnefe_fold($s)) ?? ''; }

function cnefe_label(string $street,string $number,string $locality,string $city,string $uf,string $cep=''): string {
    $a=trim($street.($number!==''?', '.$number:''));$parts=[$a];if($locality!==''&&cnefe_fold($locality)!==cnefe_fold($city))$parts[]=$locality;$parts[]=$city.', '.$uf;if($cep!=='')$parts[]='CEP '.$cep;return implode(' — ',array_filter($parts));
}

function cnefe_search(string $code,string $uf,string $city,string $q,int $limit=15): array {
    cnefe_ensure_tables();$prep=cnefe_prepare_city($code,$uf,$city,false);if(($prep['status']??'')!=='ready')return ['status'=>$prep['status']??'preparing','results'=>[],'message'=>$prep['message']??'Preparando base local do município.'];
    [$streetQ,$number]=cnefe_parse_query($q);$streets=cnefe_query_streets($code,$streetQ,max(15,$limit));$results=[];
    if($number!==''){
        $pack=cnefe_pack($code);$zipPath=(string)($pack['zip_path']??'');if($zipPath===''||!is_file($zipPath)){$prep=cnefe_prepare_city($code,$uf,$city,true);if(($prep['status']??'')!=='ready')return ['status'=>$prep['status']??'preparing','results'=>[],'message'=>$prep['message']??'Reconstruindo a base local do município.'];}
        $top=array_slice($streets,0,4);$folds=array_map(fn($r)=>(string)$r['street_fold'],$top);cnefe_load_points_for_streets($code,$folds);$nInt=preg_match('/\d+/',$number,$m)?(int)$m[0]:null;
        foreach($top as $s){
            if(count($results)>=$limit)break;
            $sql='SELECT * FROM address_local_points WHERE municipality_code=? AND street_fold=?';$params=[$code,$s['street_fold']];
            if($nInt!==null){$sql.=' ORDER BY CASE WHEN number_int=? THEN 0 ELSE 1 END, ABS(COALESCE(number_int,999999999)-?) ASC, geo_level ASC LIMIT 5';$params[]=$nInt;$params[]=$nInt;}else{$sql.=' ORDER BY geo_level ASC LIMIT 5';}
            $st=db()->prepare($sql);$st->execute($params);$points=$st->fetchAll()?:[];$wantedNumber=cnefe_number_fold($number);
            usort($points,function($a,$b)use($wantedNumber,$nInt){$ae=$wantedNumber!==''&&cnefe_number_fold((string)$a['number_text'])===$wantedNumber;$be=$wantedNumber!==''&&cnefe_number_fold((string)$b['number_text'])===$wantedNumber;if($ae!==$be)return $ae?-1:1;$an=$nInt!==null&&(int)$a['number_int']===$nInt;$bn=$nInt!==null&&(int)$b['number_int']===$nInt;if($an!==$bn)return $an?-1:1;return ((int)($a['geo_level']??99))<=>((int)($b['geo_level']??99));});
            foreach($points as $p){$textExact=$wantedNumber!==''&&cnefe_number_fold((string)$p['number_text'])===$wantedNumber;$exact=$textExact||($nInt!==null&&preg_match('/^\d+$/',$number)&&(int)$p['number_int']===$nInt);$label=cnefe_label((string)$p['street'],(string)$p['number_text'],(string)$p['locality'],$city,$uf,(string)$p['cep']);$results[]=['name'=>trim((string)$p['street'].((string)$p['number_text']!==''?', '.$p['number_text']:'')),'city'=>$city,'uf'=>$uf,'label'=>$label,'lat'=>(float)$p['latitude'],'lon'=>(float)$p['longitude'],'source'=>$exact?'IBGE CNEFE · local':'IBGE CNEFE · próximo','approximate'=>!$exact,'geo_level'=>$p['geo_level']];if(count($results)>=$limit)break;}
        }
    }else{
        foreach(array_slice($streets,0,$limit) as $s){if(!is_numeric($s['latitude'])||!is_numeric($s['longitude']))continue;$label=cnefe_label((string)$s['street'],'',(string)$s['locality'],$city,$uf,(string)$s['cep']);$results[]=['name'=>(string)$s['street'],'city'=>$city,'uf'=>$uf,'label'=>$label,'lat'=>(float)$s['latitude'],'lon'=>(float)$s['longitude'],'source'=>'IBGE CNEFE · local','street_result'=>true,'geo_level'=>$s['geo_level']];}
    }
    return ['status'=>'ready','results'=>$results,'street_count'=>(int)($prep['street_count']??0),'source'=>'IBGE CNEFE 2022 local'];
}

function cnefe_status_list(): array {
    cnefe_ensure_tables();return db()->query('SELECT municipality_code,uf,city,status,source_rows,street_count,downloaded_at,indexed_at,last_error,updated_at FROM address_local_packs ORDER BY updated_at DESC')->fetchAll()?:[];
}
