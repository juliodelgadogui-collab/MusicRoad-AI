<?php
declare(strict_types=1);
require __DIR__ . '/api/bootstrap.php';
require_once __DIR__ . '/lib/LightMap.php';
require_once __DIR__ . '/api/radar_import_helpers.php';
$user = require_admin();
ensure_schema();
refresh_expired_licenses();

$section = (string)($_GET['section'] ?? 'dashboard');
$allowedSections = ['dashboard','users','plans','payments','radars','library','system'];
if (!in_array($section,$allowedSections,true)) $section='dashboard';
$message=''; $error='';

function admin_redirect(string $section, string $msg=''): never {
    $url='admin.php?section='.rawurlencode($section);
    if($msg!=='')$url.='&msg='.rawurlencode($msg);
    header('Location: '.$url); exit;
}
function post_int(string $key,int $default=0): int { return (int)($_POST[$key] ?? $default); }
function money_br(int $cents): string { return 'R$ '.number_format($cents/100,2,',','.'); }
function money_to_cents(string $raw): int {
    $v=preg_replace('/[^0-9,.-]/','',trim($raw)) ?? '0';
    $comma=strrpos($v,','); $dot=strrpos($v,'.');
    if($comma!==false && $dot!==false){
        if($comma>$dot){$v=str_replace('.','',$v);$v=str_replace(',','.',$v);}else{$v=str_replace(',','',$v);}
    }elseif($comma!==false){$v=str_replace('.','',$v);$v=str_replace(',','.',$v);}
    elseif(substr_count($v,'.')>1){$last=strrpos($v,'.');$v=str_replace('.','',substr($v,0,$last)).substr($v,$last);}
    return max(0,(int)round(((float)$v)*100));
}
function radar_type_label(?string $type): string {
    $t=strtoupper(trim((string)$type));
    return match(true){
        str_contains($t,'PORTAT') || str_contains($t,'MOVEL') || str_contains($t,'MÓVEL') => 'Portátil',
        str_contains($t,'VIDEO') || str_contains($t,'OCR') || str_contains($t,'MONITOR') => 'Vídeo',
        str_contains($t,'SEMAF') || str_contains($t,'SIGNAL') => 'Semáforo',
        str_contains($t,'QUEBRA') || str_contains($t,'LOMB') || str_contains($t,'BUMP') => 'Quebra-mola',
        str_contains($t,'ACIDENTE') => 'Acidente',
        str_contains($t,'OBRA') => 'Obra',
        str_contains($t,'BURACO') => 'Buraco',
        default => 'Radar',
    };
}
function radar_badge_class(?string $type): string {
    return match(radar_type_label($type)){
        'Quebra-mola'=>'bump','Portátil'=>'warn','Semáforo'=>'signal','Vídeo'=>'video','Acidente'=>'danger','Obra'=>'warn','Buraco'=>'danger',default=>'radar'
    };
}
function radar_import_file(array $file,string $source): array {
    if (($file['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_OK) throw new RuntimeException('Selecione um arquivo CSV, TXT ou KML válido.');
    $size=(int)($file['size'] ?? 0); if($size<=0||$size>30*1024*1024) throw new RuntimeException('O arquivo precisa ter até 30 MB.');
    $name=(string)($file['name'] ?? 'radares.csv'); $ext=strtolower(pathinfo($name,PATHINFO_EXTENSION));
    if(!in_array($ext,['csv','txt','kml'],true)) throw new RuntimeException('Formato não suportado. Use CSV, TXT ou KML.');
    $raw=(string)file_get_contents((string)$file['tmp_name']);
    $rows=mr_import_parse_bytes($raw,$name,$source); if(!$rows) throw new RuntimeException('Não encontrei coordenadas válidas no arquivo.');
    $db=db();$inserted=0;$updated=0;$skipped=0;
    $find=$db->prepare('SELECT id FROM radars WHERE external_id=? LIMIT 1');
    $ins=$db->prepare('INSERT INTO radars (external_id,latitude,longitude,uf,cidade,rodovia,km,sentido,heading,velocidade,tipo,situacao,fonte,data_fonte,data_importacao,confiabilidade,quantidade_fontes,ativo) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,?,?,1)');
    $upd=$db->prepare('UPDATE radars SET latitude=?,longitude=?,heading=?,velocidade=?,tipo=?,situacao="ATIVO",fonte=?,data_importacao=CURRENT_TIMESTAMP,confiabilidade=?,ativo=1 WHERE id=?');
    $db->beginTransaction();
    try{
        foreach($rows as $r){
            try{
                $find->execute([$r['external_id']]);$id=$find->fetchColumn();
                if($id){$upd->execute([$r['latitude'],$r['longitude'],$r['heading'],$r['velocidade'],$r['tipo'],$r['fonte'],$r['confiabilidade'],$id]);$updated++;}
                else{$ins->execute([$r['external_id'],$r['latitude'],$r['longitude'],null,null,null,null,null,$r['heading'],$r['velocidade'],$r['tipo'],'ATIVO',$r['fonte'],date('Y-m-d'),$r['confiabilidade'],1]);$inserted++;}
            }catch(Throwable){$skipped++;}
        }
        $db->commit();
    }catch(Throwable $e){if($db->inTransaction())$db->rollBack();throw $e;}
    audit_log('radar_import',['source'=>$source,'file'=>$name,'parsed'=>count($rows),'inserted'=>$inserted,'updated'=>$updated,'skipped'=>$skipped]);
    return compact('inserted','updated','skipped')+['parsed'=>count($rows)];
}

if (!empty($_GET['msg'])) $message = (string)$_GET['msg'];
if ($_SERVER['REQUEST_METHOD']==='POST') {
    require_csrf();
    require_session_rate_limit('admin-write',60,600);
    $action=(string)($_POST['action'] ?? '');
    try {
        if($action==='create_user'){
            $name=trim((string)($_POST['name']??''));
            $username=strtolower(trim((string)($_POST['username']??'')));
            $username=preg_replace('/[^a-z0-9._-]/','',$username) ?: '';
            $email=strtolower(trim((string)($_POST['email']??'')));
            $password=(string)($_POST['password']??'');
            $days=max(1,min(3650,post_int('days',30))); $planId=post_int('plan_id') ?: null;
            if(strlen($name)<2||strlen($username)<3||strlen($password)<8) throw new RuntimeException('Preencha nome, login (3+) e senha (8+).');
            if($email==='')$email=$username.'@cliente.musicroad.local';
            if(!filter_var($email,FILTER_VALIDATE_EMAIL))throw new RuntimeException('E-mail inválido.');
            $exists=db()->prepare('SELECT id FROM users WHERE username=? OR email=? LIMIT 1');$exists->execute([$username,$email]);
            if($exists->fetchColumn())throw new RuntimeException('Login ou e-mail já cadastrado.');
            db()->beginTransaction();
            $st=db()->prepare("INSERT INTO users(name,email,username,password_hash,role,status,created_at,updated_at) VALUES(?,?,?,?,?,'active',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            $st->execute([$name,$email,$username,secure_password_hash($password),'client']);
            $uid=(int)db()->lastInsertId(); grant_license($uid,$days,'admin',$planId,'Criada no painel administrativo'); db()->commit();
            audit_log('admin.user.create',['user_id'=>$uid,'days'=>$days]); admin_redirect('users','Usuário criado com '.$days.' dias de acesso.');
        }
        if($action==='extend_license'){
            $uid=post_int('user_id');$days=max(1,min(3650,post_int('days',30))); grant_license($uid,$days,'admin',null,'Extensão manual');
            audit_log('admin.license.extend',['user_id'=>$uid,'days'=>$days]); admin_redirect('users','Acesso prorrogado por '.$days.' dias.');
        }
        if($action==='set_user_status'){
            $uid=post_int('user_id');$status=(string)($_POST['status']??'active'); if(!in_array($status,['active','suspended','disabled'],true))throw new RuntimeException('Status inválido.');
            db()->prepare('UPDATE users SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND role<>\'admin\'')->execute([$status,$uid]);
            audit_log('admin.user.status',['user_id'=>$uid,'status'=>$status]); admin_redirect('users','Status do usuário atualizado.');
        }
        if($action==='reset_user_password'){
            $uid=post_int('user_id');$password=(string)($_POST['new_password']??''); if(strlen($password)<8)throw new RuntimeException('A nova senha precisa ter 8 caracteres ou mais.');
            db()->prepare('UPDATE users SET password_hash=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND role<>\'admin\'')->execute([secure_password_hash($password),$uid]);
            audit_log('admin.user.password_reset',['user_id'=>$uid]); admin_redirect('users','Senha do usuário alterada.');
        }
        if($action==='save_payment_settings'){
            $enabled=isset($_POST['payment_enabled'])?'1':'0'; $purchase=isset($_POST['license_purchase_enabled'])?'1':'0';
            $token=trim((string)($_POST['access_token']??''));$pk=trim((string)($_POST['public_key']??''));$secret=trim((string)($_POST['webhook_secret']??''));
            $effectiveToken=$token!==''?$token:trim((string)app_setting('mercadopago_access_token','')); $effectiveSecret=$secret!==''?$secret:trim((string)app_setting('mercadopago_webhook_secret',''));
            if($enabled==='1' && $effectiveToken==='')throw new RuntimeException('Informe o Access Token antes de ativar pagamentos.');
            if($enabled==='1' && $effectiveSecret==='')throw new RuntimeException('Informe o Webhook Secret antes de ativar pagamentos.');
            if($purchase==='1' && $enabled!=='1')throw new RuntimeException('Ative a API Mercado Pago antes de liberar compra de licença.');
            if($purchase==='1' && (int)db()->query('SELECT COUNT(*) FROM plans WHERE active=1 AND price_cents>0')->fetchColumn()===0)throw new RuntimeException('Defina preço maior que zero em pelo menos um plano antes de liberar compras.');
            set_app_setting('payment_enabled',$enabled);set_app_setting('license_purchase_enabled',$purchase);
            if($token!=='')set_app_setting('mercadopago_access_token',$token); if($pk!=='')set_app_setting('mercadopago_public_key',$pk); if($secret!=='')set_app_setting('mercadopago_webhook_secret',$secret);
            audit_log('admin.payment.settings',['enabled'=>$enabled,'purchase'=>$purchase,'mode'=>'pix']); admin_redirect('payments','Configuração do Mercado Pago PIX salva.');
        }
        if($action==='save_trial_settings'){
            $enabled=isset($_POST['trial_registration_enabled'])?'1':'0';
            $networkLimit=max(1,min(20,post_int('trial_network_limit',2)));$networkDays=max(1,min(90,post_int('trial_network_days',7)));
            set_app_setting('trial_registration_enabled',$enabled);set_app_setting('trial_network_limit',(string)$networkLimit);set_app_setting('trial_network_days',(string)$networkDays);
            audit_log('admin.trial.settings',['enabled'=>$enabled,'hours'=>24,'network_limit'=>$networkLimit,'network_days'=>$networkDays]);admin_redirect('payments','Regras do teste gratuito salvas.');
        }
        if($action==='create_plan'){
            $code=strtoupper(preg_replace('/[^A-Za-z0-9_-]/','',trim((string)($_POST['code']??''))) ?: ''); $name=trim((string)($_POST['name']??''));
            $days=max(1,min(3650,post_int('duration_days',30)));$price=money_to_cents((string)($_POST['price']??'0')); if($code===''||$name==='')throw new RuntimeException('Informe código e nome do plano.');
            db()->prepare('INSERT INTO plans(code,name,duration_days,price_cents,active,created_at,updated_at) VALUES(?,?,?,?,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)')->execute([$code,$name,$days,max(0,$price)]);
            admin_redirect('plans','Plano criado.');
        }
        if($action==='save_plan'){
            $pid=post_int('plan_id');$days=max(1,min(3650,post_int('duration_days',30)));$price=money_to_cents((string)($_POST['price']??'0'));$active=isset($_POST['active'])?1:0;
            db()->prepare('UPDATE plans SET duration_days=?,price_cents=?,active=?,updated_at=CURRENT_TIMESTAMP WHERE id=?')->execute([$days,max(0,$price),$active,$pid]); admin_redirect('plans','Plano atualizado.');
        }
        if($action==='add_drive_folder'){
            $name=trim((string)($_POST['folder_name']??''));$link=trim((string)($_POST['folder_link']??''));$folderId=drive_folder_id_from_link($link); if(!$folderId)throw new RuntimeException('Link de pasta do Google Drive inválido.');
            db()->prepare("INSERT INTO drive_folders(name,folder_id,folder_link,active,created_at) VALUES(?,?,?,1,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE name=VALUES(name),folder_link=VALUES(folder_link),active=1")->execute([$name?:'Pasta Drive',$folderId,$link]);
            admin_redirect('library','Pasta do Drive salva.');
        }
        if($action==='toggle_drive_folder'){
            $id=post_int('folder_id');db()->prepare('UPDATE drive_folders SET active=CASE WHEN active=1 THEN 0 ELSE 1 END WHERE id=?')->execute([$id]); admin_redirect('library','Status da pasta atualizado.');
        }
        if($action==='create_radar'){
            $lat=(float)($_POST['latitude']??0);$lon=(float)($_POST['longitude']??0); if($lat<-34.5||$lat>6.5||$lon<-74.5||$lon>-33.0)throw new RuntimeException('Coordenadas fora da área esperada do Brasil.');
            $speedRaw=trim((string)($_POST['velocidade']??''));$speed=$speedRaw!==''&&is_numeric($speedRaw)?(int)$speedRaw:null;if($speed!==null&&($speed<10||$speed>180))throw new RuntimeException('Velocidade precisa estar entre 10 e 180 km/h.');
            $headingRaw=trim((string)($_POST['heading']??''));$heading=$headingRaw!==''&&is_numeric($headingRaw)?fmod((float)$headingRaw+3600.0,360.0):null;
            $uf=strtoupper(trim((string)($_POST['uf']??'')));if($uf!==''&&!preg_match('/^[A-Z]{2}$/',$uf))throw new RuntimeException('UF inválida.');
            $confidence=strtoupper(trim((string)($_POST['confiabilidade']??'BAIXA')));if(!in_array($confidence,['BAIXA','MÉDIA','MEDIA','ALTA','CONFIRMADA'],true))$confidence='BAIXA';
            $stmt=db()->prepare('INSERT INTO radars (external_id, latitude, longitude, uf, cidade, rodovia, km, sentido, heading, velocidade, tipo, situacao, fonte, data_fonte, data_importacao, confiabilidade, quantidade_fontes, ativo) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?)');
            $stmt->execute([
                mb_substr(trim((string)($_POST['external_id']??'')),0,190) ?: null,$lat,$lon,$uf ?: null,mb_substr(trim((string)($_POST['cidade']??'')),0,190) ?: null,
                trim((string)($_POST['rodovia']??'')) ?: null,trim((string)($_POST['km']??'')) ?: null,trim((string)($_POST['sentido']??'')) ?: null,
                $heading,$speed,mb_substr(trim((string)($_POST['tipo']??'RADAR_FIXO')),0,100) ?: 'RADAR_FIXO',mb_substr(trim((string)($_POST['situacao']??'ATIVO')),0,100) ?: 'ATIVO',
                mb_substr(trim((string)($_POST['fonte']??'ADMIN')),0,255) ?: 'ADMIN',mb_substr(trim((string)($_POST['data_fonte']??'')),0,40) ?: null,$confidence,max(1,min(999,post_int('quantidade_fontes',1))),isset($_POST['ativo'])?1:0
            ]);
            $id=(int)db()->lastInsertId(); audit_log('radars.admin_create',['id'=>$id]); admin_redirect('radars','Ponto #'.$id.' cadastrado no banco de fiscalização.');
        }
        if($action==='toggle_radar'){
            $id=post_int('radar_id'); db()->prepare('UPDATE radars SET ativo=CASE WHEN ativo=1 THEN 0 ELSE 1 END WHERE id=?')->execute([$id]); audit_log('radars.admin_toggle',['id'=>$id]); admin_redirect('radars','Status do ponto atualizado.');
        }
        if($action==='confirm_radar'){
            $id=post_int('radar_id'); db()->prepare("UPDATE radars SET ativo=1,situacao='ATIVO',ultima_confirmacao=CURRENT_TIMESTAMP,quantidade_fontes=GREATEST(1,quantidade_fontes)+1,confiabilidade=CASE WHEN quantidade_fontes>=2 THEN 'CONFIRMADA' ELSE 'ALTA' END WHERE id=?")->execute([$id]);
            audit_log('radars.admin_confirm',['id'=>$id]); admin_redirect('radars','Ponto confirmado e reforçado na base.');
        }
        if($action==='approve_road_report'){
            $reportId=post_int('report_id');if($reportId<=0)throw new RuntimeException('Relato inválido.');
            $note=mb_substr(trim((string)($_POST['review_note']??'')),0,1000);
            $pdo=db();$pdo->beginTransaction();
            $find=$pdo->prepare("SELECT * FROM road_reports WHERE id=? AND status IN ('PENDENTE','NAO_CONFIRMADO') FOR UPDATE");$find->execute([$reportId]);$report=$find->fetch();
            if(!$report)throw new RuntimeException('Este relato já foi revisado ou não existe.');
            $payload=json_decode((string)($report['payload_json']??''),true);if(!is_array($payload))$payload=[];
            $lat=(float)$report['latitude'];$lon=(float)$report['longitude'];$box=0.0008;
            $expiryHours=match((string)$report['type']){'ACIDENTE'=>12,'RADAR_MOVEL','FISCALIZACAO_PORTATIL'=>24,'OBRA'=>168,'BURACO'=>72,default=>0};$expiresAt=$expiryHours>0?date('Y-m-d H:i:s',time()+$expiryHours*3600):null;
            $near=$pdo->prepare('SELECT * FROM radars WHERE tipo=? AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? ORDER BY id DESC LIMIT 30');
            $near->execute([(string)$report['type'],$lat-$box,$lat+$box,$lon-$box,$lon+$box]);$radarId=0;
            foreach($near->fetchAll() as $candidate){if(haversine_m($lat,$lon,(float)$candidate['latitude'],(float)$candidate['longitude'])<=75){$radarId=(int)$candidate['id'];break;}}
            if($radarId>0){
                $pdo->prepare("UPDATE radars SET ativo=1,situacao='ATIVO',ultima_confirmacao=CURRENT_TIMESTAMP,quantidade_fontes=GREATEST(1,quantidade_fontes)+1,confiabilidade=CASE WHEN quantidade_fontes>=2 THEN 'CONFIRMADA' ELSE 'ALTA' END,velocidade=COALESCE(velocidade,?),expires_at=CASE WHEN ? IS NULL THEN expires_at WHEN expires_at IS NULL OR expires_at<? THEN ? ELSE expires_at END WHERE id=?")
                    ->execute([$report['speed']!==null?(int)$report['speed']:null,$expiresAt,$expiresAt,$expiresAt,$radarId]);
            }else{
                $external=trim((string)($report['external_id']??''));if($external==='')$external='community-report-'.$reportId;
                $insert=$pdo->prepare("INSERT INTO radars(external_id,latitude,longitude,uf,cidade,rodovia,km,sentido,heading,velocidade,tipo,situacao,fonte,data_fonte,data_importacao,ultima_confirmacao,confiabilidade,quantidade_fontes,ativo,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,'ATIVO','MUSICROAD_COMUNIDADE_VALIDADA',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'MÉDIA',1,1,?)");
                $insert->execute([$external,$lat,$lon,$payload['uf']??null,$payload['cidade']??null,$payload['rodovia']??null,$payload['km']??null,$payload['sentido']??null,$report['heading']!==null?(float)$report['heading']:null,$report['speed']!==null?(int)$report['speed']:null,(string)$report['type'],date('Y-m-d'),$expiresAt]);
                $radarId=(int)$pdo->lastInsertId();
            }
            $pdo->prepare("UPDATE road_reports SET status='APROVADO',reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP,review_note=? WHERE id=?")->execute([(int)$user['id'],$note?:null,$reportId]);
            $pdo->commit();audit_log('road_report.approved',['report_id'=>$reportId,'radar_id'=>$radarId]);admin_redirect('radars','Relato #'.$reportId.' aprovado e incluído na navegação.');
        }
        if($action==='reject_road_report'){
            $reportId=post_int('report_id');if($reportId<=0)throw new RuntimeException('Relato inválido.');
            $note=mb_substr(trim((string)($_POST['review_note']??'')),0,1000);
            $stmt=db()->prepare("UPDATE road_reports SET status='REJEITADO',reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP,review_note=? WHERE id=? AND status IN ('PENDENTE','NAO_CONFIRMADO')");
            $stmt->execute([(int)$user['id'],$note?:null,$reportId]);if($stmt->rowCount()===0)throw new RuntimeException('Este relato já foi revisado ou não existe.');
            audit_log('road_report.rejected',['report_id'=>$reportId]);admin_redirect('radars','Relato #'.$reportId.' rejeitado.');
        }
        if($action==='import_radars'){
            $source=((string)($_POST['source']??''))==='MAPARADAR_USUARIO'?'MAPARADAR_USUARIO':'IMPORT_USUARIO'; $r=radar_import_file($_FILES['file']??[],$source);
            admin_redirect('radars','Importação concluída: '.$r['inserted'].' novos, '.$r['updated'].' atualizados, '.$r['skipped'].' ignorados.');
        }
        if($action==='save_system'){
            if(isset($_POST['clear_google_api_key'])) set_app_setting('google_api_key',''); else { $google=trim((string)($_POST['google_api_key']??'')); if($google!=='')set_app_setting('google_api_key',$google); }
            $mapLimit=max(32,min(2048,post_int('light_map_cache_mb',256))); set_app_setting('light_map_cache_mb',(string)$mapLimit); lightmap_prune($mapLimit);
            admin_redirect('system','Configurações do sistema salvas.');
        }
        if($action==='save_mapbox'){
            $enabled=isset($_POST['mapbox_enabled']);$clear=isset($_POST['clear_mapbox_public_token']);$submitted=trim((string)($_POST['mapbox_public_token']??''));
            $token=$clear?'':($submitted!==''?$submitted:mapbox_public_token());$style=trim((string)($_POST['mapbox_style']??'mapbox://styles/mapbox/navigation-night-v1'));
            if($submitted!==''&&!mapbox_public_token_valid($submitted))throw new RuntimeException('Informe somente um token público Mapbox válido iniciado por pk. Tokens sk. são proibidos.');
            if($enabled&&!mapbox_public_token_valid($token))throw new RuntimeException('Informe um token público pk. antes de ativar o Mapbox.');
            if(!mapbox_style_valid($style))throw new RuntimeException('O identificador do estilo Mapbox é inválido.');
            set_app_setting('mapbox_public_token',$token);set_app_setting('mapbox_style',$style);set_app_setting('mapbox_enabled',$enabled?'1':'0');
            audit_log('system.mapbox',['enabled'=>$enabled,'style'=>$style,'token_changed'=>$submitted!==''||$clear]);admin_redirect('system','Configuração Mapbox salva.');
        }
        if($action==='change_admin_password'){
            $pass=(string)($_POST['password']??'');if(strlen($pass)<10)throw new RuntimeException('Use pelo menos 10 caracteres.'); db()->prepare('UPDATE users SET password_hash=?,updated_at=CURRENT_TIMESTAMP WHERE id=?')->execute([secure_password_hash($pass),(int)$user['id']]);
            admin_redirect('system','Senha do administrador alterada.');
        }
    } catch(PDOException $e){
        try{if(db()->inTransaction())db()->rollBack();}catch(Throwable $ignored){}
        $incident=report_runtime_exception('admin_database',$e);$error='Não foi possível concluir a operação. Incidente '.$incident.'.';
    } catch(RuntimeException $e){
        try{if(db()->inTransaction())db()->rollBack();}catch(Throwable $ignored){}
        $error=$e->getMessage();
    } catch(Throwable $e){
        try{if(db()->inTransaction())db()->rollBack();}catch(Throwable $ignored){}
        $incident=report_runtime_exception('admin',$e);$error='Não foi possível concluir a operação. Incidente '.$incident.'.';
    }
}

$counts=[
 'users'=>(int)db()->query("SELECT COUNT(*) FROM users WHERE role='client'")->fetchColumn(),
 'active'=>(int)db()->query("SELECT COUNT(DISTINCT u.id) FROM users u JOIN licenses l ON l.user_id=u.id WHERE u.role='client' AND u.status='active' AND l.status='active' AND l.starts_at<=CURRENT_TIMESTAMP AND l.ends_at>=CURRENT_TIMESTAMP")->fetchColumn(),
 'expiring'=>(int)db()->query("SELECT COUNT(DISTINCT u.id) FROM users u JOIN licenses l ON l.user_id=u.id WHERE u.role='client' AND l.status='active' AND l.ends_at BETWEEN CURRENT_TIMESTAMP AND DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 7 DAY)")->fetchColumn(),
 'expired'=>(int)db()->query("SELECT COUNT(*) FROM users u WHERE u.role='client' AND NOT EXISTS(SELECT 1 FROM licenses l WHERE l.user_id=u.id AND l.status='active' AND l.starts_at<=CURRENT_TIMESTAMP AND l.ends_at>=CURRENT_TIMESTAMP)")->fetchColumn(),
 'tracks'=>(int)db()->query('SELECT COUNT(*) FROM music_library')->fetchColumn(),
 'radars'=>(int)db()->query('SELECT COUNT(*) FROM radars WHERE ativo=1 AND (expires_at IS NULL OR expires_at>=CURRENT_TIMESTAMP)')->fetchColumn(),
 'address_cities'=>(int)db()->query("SELECT COUNT(*) FROM address_local_packs WHERE status IN ('ready','indexed')")->fetchColumn(),
 'address_streets'=>(int)db()->query('SELECT COUNT(*) FROM address_local_streets')->fetchColumn(),
 'reports'=>(int)db()->query("SELECT COUNT(*) FROM road_reports WHERE status IN ('PENDENTE','NAO_CONFIRMADO') AND (expires_at IS NULL OR expires_at>=CURRENT_TIMESTAMP)")->fetchColumn(),
];
$users=db()->query("SELECT u.*, (SELECT ends_at FROM licenses l WHERE l.user_id=u.id ORDER BY ends_at DESC,id DESC LIMIT 1) license_ends,(SELECT status FROM licenses l WHERE l.user_id=u.id ORDER BY ends_at DESC,id DESC LIMIT 1) license_status FROM users u WHERE u.role='client' ORDER BY u.id DESC LIMIT 300")->fetchAll();
$plans=db()->query('SELECT * FROM plans ORDER BY duration_days')->fetchAll();
$orders=db()->query("SELECT o.*,u.name user_name,p.name plan_name FROM payment_orders o JOIN users u ON u.id=o.user_id JOIN plans p ON p.id=o.plan_id ORDER BY o.id DESC LIMIT 100")->fetchAll();
$folders=db()->query('SELECT * FROM drive_folders ORDER BY active DESC,id DESC')->fetchAll();
$recentLogs=db()->query('SELECT action,ip,created_at FROM audit_logs ORDER BY id DESC LIMIT 12')->fetchAll();
$paymentOn=payment_enabled(); $purchaseOn=app_setting('license_purchase_enabled','0')==='1'; $maskedToken=app_setting('mercadopago_access_token','')!==''?'Configurado':'Não configurado'; $lightMapAdminStats=lightmap_stats();
$trialOn=trial_registration_enabled();$trialStats=['total'=>(int)db()->query('SELECT COUNT(*) FROM trial_claims')->fetchColumn(),'active'=>(int)db()->query("SELECT COUNT(*) FROM trial_claims WHERE expires_at>=CURRENT_TIMESTAMP")->fetchColumn(),'android'=>(int)db()->query("SELECT COUNT(*) FROM trial_claims WHERE source='android'")->fetchColumn(),'blocked'=>(int)db()->query("SELECT COUNT(*) FROM audit_logs WHERE action='trial.blocked' AND created_at>=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 7 DAY)")->fetchColumn()];

$radarSummary=[
 'total'=>(int)db()->query('SELECT COUNT(*) FROM radars')->fetchColumn(),
 'active'=>(int)db()->query('SELECT COUNT(*) FROM radars WHERE ativo=1 AND (expires_at IS NULL OR expires_at>=CURRENT_TIMESTAMP)')->fetchColumn(),
 'no_speed'=>(int)db()->query('SELECT COUNT(*) FROM radars WHERE ativo=1 AND (expires_at IS NULL OR expires_at>=CURRENT_TIMESTAMP) AND (velocidade IS NULL OR velocidade=0)')->fetchColumn(),
 'no_direction'=>(int)db()->query('SELECT COUNT(*) FROM radars WHERE ativo=1 AND (expires_at IS NULL OR expires_at>=CURRENT_TIMESTAMP) AND (sentido IS NULL OR sentido="")')->fetchColumn(),
 'pending'=>$counts['reports'],
];
$pendingReports=db()->query("SELECT rr.*,u.name AS user_name,u.username FROM road_reports rr LEFT JOIN users u ON u.id=rr.user_id WHERE rr.status IN ('PENDENTE','NAO_CONFIRMADO') AND (rr.expires_at IS NULL OR rr.expires_at>=CURRENT_TIMESTAMP) ORDER BY rr.id DESC LIMIT 100")->fetchAll();
$radarSources=db()->query('SELECT COALESCE(NULLIF(fonte,""),"SEM_FONTE") fonte, COUNT(*) qtd FROM radars GROUP BY COALESCE(NULLIF(fonte,""),"SEM_FONTE") ORDER BY qtd DESC LIMIT 8')->fetchAll();
$radarTypeRows=db()->query('SELECT COALESCE(NULLIF(tipo,""),"RADAR") tipo, COUNT(*) qtd FROM radars WHERE ativo=1 AND (expires_at IS NULL OR expires_at>=CURRENT_TIMESTAMP) GROUP BY COALESCE(NULLIF(tipo,""),"RADAR") ORDER BY qtd DESC LIMIT 8')->fetchAll();

$radarQ=trim((string)($_GET['q']??'')); $radarUf=strtoupper(trim((string)($_GET['uf']??''))); $radarType=trim((string)($_GET['tipo']??'')); $radarStatus=(string)($_GET['status']??'');
$radarWhere=[];$radarParams=[];
if($radarQ!==''){$radarWhere[]='(cidade LIKE ? OR rodovia LIKE ? OR external_id LIKE ? OR fonte LIKE ?)';$like='%'.$radarQ.'%';array_push($radarParams,$like,$like,$like,$like);}
if($radarUf!==''){$radarWhere[]='uf=?';$radarParams[]=$radarUf;}
if($radarType!==''){$radarWhere[]='tipo=?';$radarParams[]=$radarType;}
if($radarStatus==='active')$radarWhere[]='ativo=1 AND (expires_at IS NULL OR expires_at>=CURRENT_TIMESTAMP)'; elseif($radarStatus==='inactive')$radarWhere[]='(ativo=0 OR expires_at<CURRENT_TIMESTAMP)';
$radarSql='SELECT * FROM radars'.($radarWhere?' WHERE '.implode(' AND ',$radarWhere):'').' ORDER BY id DESC LIMIT 300';
$radarStmt=db()->prepare($radarSql);$radarStmt->execute($radarParams);$radars=$radarStmt->fetchAll();
$radarUfs=db()->query("SELECT DISTINCT uf FROM radars WHERE uf IS NOT NULL AND uf<>'' ORDER BY uf")->fetchAll(PDO::FETCH_COLUMN);
$radarTypes=db()->query("SELECT DISTINCT tipo FROM radars WHERE tipo IS NOT NULL AND tipo<>'' ORDER BY tipo")->fetchAll(PDO::FETCH_COLUMN);

$nav=[
 'dashboard'=>['DB','Visão geral','Operação e saúde'],
 'users'=>['US','Clientes','Acessos e licenças'],
 'plans'=>['PL','Planos','Produtos e validade'],
 'payments'=>['PG','Financeiro','PIX, testes e pagamentos'],
 'radars'=>['RD','Radares','Fiscalização e alertas'],
 'library'=>['MU','Música','Biblioteca e Drive'],
 'system'=>['ST','Sistema','Mapa, endereços e segurança'],
];
$current=$nav[$section];
?>
<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#070b10">
<title><?=htmlspecialchars($current[1])?> · MusicRoad Admin</title>
<link rel="stylesheet" href="assets/css/admin-console.css?v=1.2.0">
<link rel="stylesheet" href="assets/css/premium-admin.css?v=1.2.0">
<style>.sidebar-actions form{margin:0}.sidebar-actions button{width:100%;border:0;background:transparent;color:#7f94a5;font-size:10px;padding:9px 6px;border-radius:10px;cursor:pointer}.sidebar-actions button:hover{background:#101b25;color:#fff}</style>
</head>
<body>
<div class="console-shell">
  <aside class="console-sidebar" id="adminSidebar">
    <a class="console-brand" href="admin.php">
      <span class="brand-road"><i></i><i></i><i></i></span>
      <span><strong>MusicRoad</strong><small>CONTROL CENTER</small></span>
    </a>
    <div class="nav-label">GESTÃO</div>
    <nav class="console-nav">
      <?php foreach($nav as $key=>$item): ?>
      <a href="?section=<?=$key?>" class="<?=$section===$key?'active':''?>"><b><?=$item[0]?></b><span><strong><?=$item[1]?></strong><small><?=$item[2]?></small></span></a>
      <?php endforeach; ?>
    </nav>
    <div class="sidebar-status">
      <div><span class="live-dot"></span><b>Sistema online</b></div>
      <small>Servidor v1.2.0 SecureDB</small>
    </div>
    <div class="sidebar-actions"><a href="index.php">Abrir aplicativo</a><form method="post" action="logout.php"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token(),ENT_QUOTES)?>"><button type="submit">Sair da conta</button></form></div>
  </aside>

  <main class="console-main">
    <header class="console-topbar">
      <button class="menu-button" id="adminMenuToggle" type="button" aria-label="Abrir menu">☰</button>
      <div class="top-title"><small>PAINEL ADMINISTRATIVO</small><h1><?=htmlspecialchars($current[1])?></h1><p><?=htmlspecialchars($current[2])?></p></div>
      <div class="top-actions"><a class="ghost-btn" href="index.php">↗ Abrir app</a><div class="admin-avatar"><span><?=htmlspecialchars(strtoupper(substr((string)($user['name']??'A'),0,1)))?></span><div><b><?=htmlspecialchars((string)($user['name']??'Admin'))?></b><small>Administrador</small></div></div></div>
    </header>

    <div class="console-page">
      <?php if($message): ?><div class="notice success"><b>✓</b><span><?=htmlspecialchars($message)?></span></div><?php endif; ?>
      <?php if($error): ?><div class="notice error"><b>!</b><span><?=htmlspecialchars($error)?></span></div><?php endif; ?>

      <?php if($section==='dashboard'): ?>
      <section class="hero-panel">
        <div><span class="eyebrow">CENTRAL DE OPERAÇÃO</span><h2>O MusicRoad inteiro em uma tela.</h2><p>Clientes, navegação, radares, mapas, música e faturamento organizados em um único centro administrativo.</p></div>
        <div class="hero-actions"><a class="primary-btn" href="?section=radars">Gerenciar radares</a><a class="ghost-btn" href="?section=system">Ver sistema</a></div>
      </section>
      <div class="metric-grid">
        <article class="metric-card"><div class="metric-icon">US</div><span>Clientes</span><strong><?=$counts['users']?></strong><small><?=$counts['active']?> com acesso ativo</small></article>
        <article class="metric-card"><div class="metric-icon radar">RD</div><span>Radares ativos</span><strong><?=$counts['radars']?></strong><small><?=$counts['reports']?> relatos aguardando validação</small></article>
        <article class="metric-card"><div class="metric-icon music">MU</div><span>Músicas</span><strong><?=$counts['tracks']?></strong><small><?=count($folders)?> pastas conectadas</small></article>
        <article class="metric-card"><div class="metric-icon map">MP</div><span>Cidades preparadas</span><strong><?=$counts['address_cities']?></strong><small><?=number_format($counts['address_streets'],0,',','.')?> vias indexadas</small></article>
      </div>
      <div class="dashboard-grid">
        <section class="panel span-2"><div class="panel-head"><div><span class="eyebrow">SAÚDE DO NEGÓCIO</span><h3>Licenças e clientes</h3></div><a href="?section=users">Abrir clientes →</a></div>
          <div class="mini-metrics"><div><span>Ativas</span><b><?=$counts['active']?></b></div><div><span>Vencem em 7 dias</span><b class="warning-text"><?=$counts['expiring']?></b></div><div><span>Sem acesso</span><b class="danger-text"><?=$counts['expired']?></b></div></div>
        </section>
        <section class="panel"><div class="panel-head"><div><span class="eyebrow">FINANCEIRO</span><h3>Mercado Pago PIX</h3></div><span class="status-chip <?=$paymentOn?'ok':'off'?>"><?=$paymentOn?'ATIVO':'DESATIVADO'?></span></div><p class="muted">Compra de licença: <b><?=$purchaseOn?'liberada':'bloqueada'?></b></p><a class="secondary-btn full" href="?section=payments">Configurar pagamentos</a></section>
        <section class="panel span-3"><div class="panel-head"><div><span class="eyebrow">AUDITORIA</span><h3>Atividade recente</h3></div></div><div class="table-wrap"><table><thead><tr><th>Data</th><th>Ação</th><th>IP</th></tr></thead><tbody><?php foreach($recentLogs as $l): ?><tr><td><?=htmlspecialchars((string)$l['created_at'])?></td><td><span class="event-name"><?=htmlspecialchars((string)$l['action'])?></span></td><td class="mono"><?=htmlspecialchars((string)$l['ip'])?></td></tr><?php endforeach; ?></tbody></table></div></section>
      </div>
      <?php endif; ?>

      <?php if($section==='users'): ?>
      <div class="metric-grid compact"><article class="metric-card"><span>Total</span><strong><?=$counts['users']?></strong><small>clientes cadastrados</small></article><article class="metric-card"><span>Ativos</span><strong><?=$counts['active']?></strong><small>licença válida</small></article><article class="metric-card"><span>Vencem logo</span><strong><?=$counts['expiring']?></strong><small>próximos 7 dias</small></article><article class="metric-card"><span>Sem acesso</span><strong><?=$counts['expired']?></strong><small>expirados ou sem licença</small></article></div>
      <section class="panel"><div class="panel-head"><div><span class="eyebrow">NOVO ACESSO</span><h3>Criar cliente</h3><p>Conta e licença são criadas juntas.</p></div></div><form method="post" class="form-grid three"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token())?>"><input type="hidden" name="action" value="create_user"><label>Nome<input name="name" required></label><label>Login<input name="username" required></label><label>E-mail<input type="email" name="email" placeholder="opcional"></label><label>Senha<input type="password" name="password" minlength="8" required></label><label>Período<select name="days"><option value="7">7 dias</option><option value="15">15 dias</option><option value="30" selected>30 dias</option><option value="90">90 dias</option><option value="180">180 dias</option><option value="365">365 dias</option></select></label><label>Plano<select name="plan_id"><option value="">Licença manual</option><?php foreach($plans as $p): ?><option value="<?=$p['id']?>"><?=htmlspecialchars($p['name'])?> · <?=$p['duration_days']?> dias</option><?php endforeach; ?></select></label><div class="form-submit"><button class="primary-btn">Criar usuário</button></div></form></section>
      <section class="panel"><div class="panel-head"><div><span class="eyebrow">BASE DE CLIENTES</span><h3>Usuários e validade</h3></div></div><div class="table-wrap"><table class="wide-table"><thead><tr><th>Cliente</th><th>Acesso</th><th>Validade</th><th>Prorrogar</th><th>Conta</th><th>Senha</th></tr></thead><tbody><?php foreach($users as $u): $valid=$u['license_ends'] && strtotime((string)$u['license_ends'])>=time() && $u['license_status']==='active' && $u['status']==='active'; ?><tr><td><div class="person-cell"><span><?=htmlspecialchars(strtoupper(substr((string)$u['name'],0,1)))?></span><div><b><?=htmlspecialchars($u['name'])?></b><small>@<?=htmlspecialchars($u['username'])?> · <?=htmlspecialchars($u['email'])?></small></div></div></td><td><span class="status-chip <?=$valid?'ok':'off'?>"><?=$valid?'ATIVO':'SEM ACESSO'?></span><small class="subline"><?=htmlspecialchars($u['status'])?></small></td><td><?=$u['license_ends']?htmlspecialchars(date('d/m/Y H:i',strtotime((string)$u['license_ends']))):'Nunca liberado'?></td><td><form method="post" class="inline-form"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token())?>"><input type="hidden" name="action" value="extend_license"><input type="hidden" name="user_id" value="<?=$u['id']?>"><select name="days"><option value="7">+7</option><option value="30" selected>+30</option><option value="90">+90</option><option value="365">+365</option></select><button class="tiny-btn">Somar</button></form></td><td><form method="post" class="inline-form"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token())?>"><input type="hidden" name="action" value="set_user_status"><input type="hidden" name="user_id" value="<?=$u['id']?>"><select name="status"><option value="active" <?=$u['status']==='active'?'selected':''?>>Ativo</option><option value="suspended" <?=$u['status']==='suspended'?'selected':''?>>Suspenso</option><option value="disabled" <?=$u['status']==='disabled'?'selected':''?>>Desativado</option></select><button class="tiny-btn">Salvar</button></form></td><td><form method="post" class="inline-form"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token())?>"><input type="hidden" name="action" value="reset_user_password"><input type="hidden" name="user_id" value="<?=$u['id']?>"><input name="new_password" type="password" placeholder="nova senha"><button class="tiny-btn">Trocar</button></form></td></tr><?php endforeach; ?><?php if(!$users): ?><tr><td colspan="6" class="empty">Nenhum cliente criado.</td></tr><?php endif; ?></tbody></table></div></section>
      <?php endif; ?>

      <?php if($section==='plans'): ?>
      <section class="panel"><div class="panel-head"><div><span class="eyebrow">CATÁLOGO</span><h3>Novo plano</h3><p>Defina duração e preço para venda ou liberação manual.</p></div></div><form method="post" class="form-grid three"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token())?>"><input type="hidden" name="action" value="create_plan"><label>Código<input name="code" placeholder="MENSAL" required></label><label>Nome<input name="name" placeholder="30 dias" required></label><label>Duração<input type="number" name="duration_days" value="30" min="1" required></label><label>Preço (R$)<input name="price" value="0,00" inputmode="decimal" required></label><div class="form-submit"><button class="primary-btn">Criar plano</button></div></form></section>
      <section class="panel"><div class="panel-head"><div><span class="eyebrow">PLANOS</span><h3>Produtos cadastrados</h3></div></div><div class="table-wrap"><table><thead><tr><th>Plano</th><th>Dias</th><th>Preço</th><th>Disponível</th><th></th></tr></thead><tbody><?php foreach($plans as $p): $formId='plan-'.(int)$p['id']; ?><tr><td><b><?=htmlspecialchars($p['name'])?></b><small class="subline mono"><?=htmlspecialchars($p['code'])?></small><form id="<?=$formId?>" method="post"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token())?>"><input type="hidden" name="action" value="save_plan"><input type="hidden" name="plan_id" value="<?=$p['id']?>"></form></td><td><input form="<?=$formId?>" class="cell-input" type="number" name="duration_days" min="1" value="<?=$p['duration_days']?>"></td><td><input form="<?=$formId?>" class="cell-input money-input" name="price" value="<?=number_format($p['price_cents']/100,2,',','')?>"></td><td><label class="switch"><input form="<?=$formId?>" type="checkbox" name="active" <?=$p['active']?'checked':''?>><span></span></label></td><td><button form="<?=$formId?>" class="tiny-btn">Salvar</button></td></tr><?php endforeach; ?></tbody></table></div></section>
      <?php endif; ?>

      <?php if($section==='payments'): ?>
      <div class="metric-grid compact"><article class="metric-card"><span>Testes criados</span><strong><?=$trialStats['total']?></strong><small><?=$trialStats['active']?> ainda no período grátis</small></article><article class="metric-card"><span>Android protegido</span><strong><?=$trialStats['android']?></strong><small>identificador nativo</small></article><article class="metric-card"><span>Bloqueios 7 dias</span><strong><?=$trialStats['blocked']?></strong><small>tentativas repetidas</small></article><article class="metric-card"><span>PIX</span><strong class="text-value"><?=$paymentOn?'ATIVO':'OFF'?></strong><small><?=$purchaseOn?'vendas liberadas':'vendas bloqueadas'?></small></article></div>
      <section class="panel <?=$paymentOn?'':'panel-warning'?>"><div class="panel-head"><div><span class="eyebrow">PIX DIRETO</span><h3>Mercado Pago PIX</h3><p>Gera QR Code e PIX Copia e Cola dentro do MusicRoad. A licença é liberada após confirmação do pagamento.</p></div><span class="status-chip <?=$paymentOn?'ok':'warn'?>"><?=$paymentOn?'ATIVO':'DESATIVADO'?></span></div><form method="post" class="stack"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token())?>"><input type="hidden" name="action" value="save_payment_settings"><label class="toggle-card"><input type="checkbox" name="payment_enabled" <?=$paymentOn?'checked':''?>><span><b>Ativar Mercado Pago PIX</b><small>Usa a API /v1/payments com idempotência e confirmação do servidor.</small></span></label><label class="toggle-card"><input type="checkbox" name="license_purchase_enabled" <?=$purchaseOn?'checked':''?>><span><b>Liberar venda de planos por PIX</b><small>Exibe “Pagar com PIX” para clientes.</small></span></label><div class="form-grid"><label>Access Token de produção<input type="password" name="access_token" placeholder="<?=$maskedToken?>" autocomplete="new-password"><small>Fica somente no servidor.</small></label><label>Public Key (opcional)<input name="public_key" placeholder="<?=app_setting('mercadopago_public_key','')!==''?'Configurada':'APP_USR-...'?>"><small>O PIX direto do MusicRoad não depende dela.</small></label><label>Webhook Secret<input type="password" name="webhook_secret" placeholder="<?=app_setting('mercadopago_webhook_secret','')!==''?'Configurado':'Chave secreta do webhook'?>"><small>Usado para validar X-Signature.</small></label><label>Webhook URL<input value="<?=htmlspecialchars(rtrim((string)($config['app_url']??''),'/').'/api/mercadopago_webhook.php')?>" readonly><small>Configure o evento Pagamentos no painel Mercado Pago.</small></label></div><div class="form-submit"><button class="primary-btn">Salvar Mercado Pago PIX</button></div></form></section>
      <section class="panel"><div class="panel-head"><div><span class="eyebrow">CADASTRO + TESTE</span><h3>Teste grátis com proteção antiabuso</h3><p>Uma conta nova recebe o período de teste uma única vez por dispositivo/instalação. A rede funciona como segunda barreira contra criação em massa.</p></div><span class="status-chip <?=$trialOn?'ok':'off'?>"><?=$trialOn?'ATIVO':'PAUSADO'?></span></div><form method="post" class="form-grid"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token())?>"><input type="hidden" name="action" value="save_trial_settings"><label class="toggle-card"><input type="checkbox" name="trial_registration_enabled" <?=$trialOn?'checked':''?>><span><b>Permitir cadastro no app</b><small>Mostra “Criar conta · testar” na entrada.</small></span></label><label>Duração do teste<input value="24 horas" readonly><small>Fixo: um dia completo a partir do cadastro.</small></label><label>Máximo de testes por rede<input type="number" min="1" max="20" name="trial_network_limit" value="<?=trial_network_limit()?>"><small>Proteção para tentativas com várias contas.</small></label><label>Janela da rede (dias)<input type="number" min="1" max="90" name="trial_network_days" value="<?=trial_network_days()?>"><small>Padrão: 2 testes por rede em 7 dias.</small></label><div class="form-submit"><button class="primary-btn">Salvar regras do teste</button></div></form><p class="muted">No APK 1.0 o MusicRoad usa um identificador derivado do Android ID, sem gravar o valor bruto. Reinstalar o app normalmente não cria outro teste. Reset de fábrica ou outro aparelho ainda podem gerar uma identidade diferente; nenhum mecanismo local consegue impedir isso 100% sem validação externa de CPF/telefone.</p></section>
      <section class="panel"><div class="panel-head"><div><span class="eyebrow">HISTÓRICO</span><h3>Pedidos PIX</h3></div></div><div class="table-wrap"><table><thead><tr><th>Data</th><th>Cliente</th><th>Plano</th><th>Valor</th><th>Status</th><th>Pagamento</th></tr></thead><tbody><?php foreach($orders as $o): ?><tr><td><?=htmlspecialchars($o['created_at'])?></td><td><?=htmlspecialchars($o['user_name'])?></td><td><?=htmlspecialchars($o['plan_name'])?></td><td class="money"><?=money_br((int)$o['amount_cents'])?></td><td><span class="status-chip neutral"><?=htmlspecialchars($o['status'])?></span></td><td class="mono"><?=htmlspecialchars((string)($o['provider_payment_id']?:$o['provider_preference_id']))?></td></tr><?php endforeach; ?><?php if(!$orders): ?><tr><td colspan="6" class="empty">Nenhum PIX criado.</td></tr><?php endif; ?></tbody></table></div></section>
      <?php endif; ?>

      <?php if($section==='radars'): ?>
      <div class="radar-command">
        <div><span class="eyebrow">BASE DE FISCALIZAÇÃO</span><h2>Radares agora fazem parte do painel.</h2><p>Cadastro, importação, fontes, validação e status no mesmo design administrativo.</p></div>
        <div class="command-actions"><button type="button" class="secondary-btn" id="radarHealthBtn">Testar fontes</button><button type="button" class="primary-btn" id="radarSyncBtn">Sincronizar Brasil</button></div>
      </div>
      <div id="radarApiResult" class="api-result" hidden></div>
      <div class="metric-grid compact"><article class="metric-card"><span>Total na base</span><strong><?=$radarSummary['total']?></strong><small>todos os registros</small></article><article class="metric-card"><span>Ativos na rota</span><strong><?=$radarSummary['active']?></strong><small>usados nos alertas</small></article><article class="metric-card"><span>Sem velocidade</span><strong><?=$radarSummary['no_speed']?></strong><small>revisar quando possível</small></article><article class="metric-card"><span>Aguardando análise</span><strong><?=$radarSummary['pending']?></strong><small>relatos da comunidade</small></article></div>
      <div class="radar-layout">
        <section class="panel"><div class="panel-head"><div><span class="eyebrow">CADASTRO MANUAL</span><h3>Novo ponto</h3><p>Cadastre radar, quebra-mola, semáforo ou ocorrência.</p></div></div><form method="post" class="form-grid three"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token())?>"><input type="hidden" name="action" value="create_radar"><label>Latitude<input name="latitude" required inputmode="decimal" placeholder="-21.123456"></label><label>Longitude<input name="longitude" required inputmode="decimal" placeholder="-41.123456"></label><label>Tipo<select name="tipo"><option value="RADAR_FIXO">Radar fixo</option><option value="FISCALIZACAO_PORTATIL">Fiscalização portátil</option><option value="VIDEOMONITORAMENTO">Videomonitoramento</option><option value="FISCALIZACAO_SEMAFORICA">Fiscalização semafórica</option><option value="QUEBRA_MOLA">Quebra-mola</option><option value="ACIDENTE">Acidente</option><option value="OBRA">Obra</option><option value="BURACO">Buraco</option></select></label><label>UF<input name="uf" maxlength="2" placeholder="RJ"></label><label>Cidade<input name="cidade" placeholder="Cidade"></label><label>Rodovia / via<input name="rodovia" placeholder="BR-101 ou Av. Brasil"></label><label>KM<input name="km" placeholder="153"></label><label>Sentido<input name="sentido" placeholder="Norte / Sul"></label><label>Heading<input name="heading" type="number" min="0" max="360" step="0.1" placeholder="0–360"></label><label>Velocidade<input name="velocidade" type="number" min="10" max="180" placeholder="60"></label><label>Situação<select name="situacao"><option>ATIVO</option><option>AGUARDANDO_VALIDACAO</option><option>INATIVO</option></select></label><label>Confiabilidade<select name="confiabilidade"><option>BAIXA</option><option>MÉDIA</option><option>ALTA</option><option>CONFIRMADA</option></select></label><label>Fonte<input name="fonte" value="ADMIN"></label><label>Data da fonte<input name="data_fonte" type="date"></label><label>External ID<input name="external_id" placeholder="opcional"></label><label>Quantidade de fontes<input name="quantidade_fontes" type="number" value="1" min="1"></label><label class="toggle-card mini"><input name="ativo" type="checkbox" checked><span><b>Usar nas rotas</b><small>Ponto fica ativo imediatamente.</small></span></label><div class="form-submit"><button class="primary-btn">Salvar ponto</button></div></form></section>
        <aside class="panel radar-side"><div class="panel-head"><div><span class="eyebrow">IMPORTAÇÃO</span><h3>Adicionar base</h3></div></div><form method="post" enctype="multipart/form-data" class="stack"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token())?>"><input type="hidden" name="action" value="import_radars"><label>Arquivo CSV, TXT ou KML<input type="file" name="file" accept=".csv,.txt,.kml" required></label><label>Origem<select name="source"><option value="IMPORT_USUARIO">Importação própria</option><option value="MAPARADAR_USUARIO">Maparadar / iGO</option></select></label><button class="secondary-btn full">Importar arquivo</button></form><div class="divider"></div><span class="eyebrow">PRINCIPAIS FONTES</span><div class="source-list"><?php foreach($radarSources as $s): ?><div><span><?=htmlspecialchars((string)$s['fonte'])?></span><b><?=number_format((int)$s['qtd'],0,',','.')?></b></div><?php endforeach; ?><?php if(!$radarSources): ?><p class="muted">Nenhuma fonte cadastrada.</p><?php endif; ?></div><div class="divider"></div><span class="eyebrow">TIPOS ATIVOS</span><div class="source-list"><?php foreach($radarTypeRows as $t): ?><div><span><?=htmlspecialchars(radar_type_label((string)$t['tipo']))?></span><b><?=number_format((int)$t['qtd'],0,',','.')?></b></div><?php endforeach; ?></div></aside>
      </div>
      <section class="panel">
        <div class="panel-head radar-filter-head"><div><span class="eyebrow">MODERAÇÃO</span><h3>Relatos da comunidade</h3><p>Somente pontos aprovados entram nos alertas de navegação.</p></div><span class="result-count"><?=count($pendingReports)?> pendente(s)</span></div>
        <div class="table-wrap"><table class="wide-table"><thead><tr><th>Relato</th><th>Motorista</th><th>Posição</th><th>Vel.</th><th>Enviado</th><th>Ações</th></tr></thead><tbody>
        <?php foreach($pendingReports as $report): $reportPayload=json_decode((string)($report['payload_json']??''),true);if(!is_array($reportPayload))$reportPayload=[]; ?>
          <tr>
            <td><b><?=htmlspecialchars(radar_type_label((string)$report['type']))?></b><small class="subline">#<?=(int)$report['id']?> · <?=htmlspecialchars((string)$report['type'])?></small></td>
            <td><?=htmlspecialchars((string)($report['user_name']?:'Conta removida'))?><small class="subline">@<?=htmlspecialchars((string)($report['username']?:'—'))?></small></td>
            <td><a class="clear-filter" target="_blank" rel="noopener noreferrer" href="https://www.openstreetmap.org/?mlat=<?=rawurlencode((string)$report['latitude'])?>&amp;mlon=<?=rawurlencode((string)$report['longitude'])?>#map=19/<?=rawurlencode((string)$report['latitude'])?>/<?=rawurlencode((string)$report['longitude'])?>"><?=number_format((float)$report['latitude'],5,'.','')?>, <?=number_format((float)$report['longitude'],5,'.','')?></a><small class="subline"><?=htmlspecialchars(trim((string)($reportPayload['cidade']??'').' '.(string)($reportPayload['uf']??''))?:'Local não informado')?></small></td>
            <td><span class="speed-badge"><?=$report['speed']?(int)$report['speed'].'<small>km/h</small>':'—'?></span></td>
            <td><?=htmlspecialchars((string)$report['created_at'])?></td>
            <td><div class="row-actions"><form method="post"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token())?>"><input type="hidden" name="action" value="approve_road_report"><input type="hidden" name="report_id" value="<?=(int)$report['id']?>"><button class="icon-btn" title="Aprovar e ativar">✓</button></form><form method="post" class="reject-road-report"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token())?>"><input type="hidden" name="action" value="reject_road_report"><input type="hidden" name="report_id" value="<?=(int)$report['id']?>"><button class="icon-btn" title="Rejeitar">×</button></form></div></td>
          </tr>
        <?php endforeach; ?>
        <?php if(!$pendingReports): ?><tr><td colspan="6" class="empty">Nenhum relato aguardando validação.</td></tr><?php endif; ?>
        </tbody></table></div>
      </section>
      <section class="panel"><div class="panel-head radar-filter-head"><div><span class="eyebrow">BANCO DE PONTOS</span><h3>Radares e alertas cadastrados</h3><p>Exibindo até 300 registros por consulta.</p></div><span class="result-count"><?=count($radars)?> resultados</span></div>
        <form method="get" class="filter-bar"><input type="hidden" name="section" value="radars"><label>Buscar<input name="q" value="<?=htmlspecialchars($radarQ)?>" placeholder="cidade, rodovia, fonte ou ID"></label><label>UF<select name="uf"><option value="">Todas</option><?php foreach($radarUfs as $uf): ?><option value="<?=htmlspecialchars((string)$uf)?>" <?=$radarUf===$uf?'selected':''?>><?=htmlspecialchars((string)$uf)?></option><?php endforeach; ?></select></label><label>Tipo<select name="tipo"><option value="">Todos</option><?php foreach($radarTypes as $type): ?><option value="<?=htmlspecialchars((string)$type)?>" <?=$radarType===$type?'selected':''?>><?=htmlspecialchars(radar_type_label((string)$type))?> · <?=htmlspecialchars((string)$type)?></option><?php endforeach; ?></select></label><label>Status<select name="status"><option value="">Todos</option><option value="active" <?=$radarStatus==='active'?'selected':''?>>Ativos</option><option value="inactive" <?=$radarStatus==='inactive'?'selected':''?>>Inativos</option></select></label><button class="secondary-btn">Filtrar</button><?php if($radarQ!==''||$radarUf!==''||$radarType!==''||$radarStatus!==''): ?><a class="clear-filter" href="?section=radars">Limpar</a><?php endif; ?></form>
        <div class="table-wrap"><table class="wide-table radar-table"><thead><tr><th>Ponto</th><th>Local</th><th>Via</th><th>Vel.</th><th>Fonte</th><th>Confiança</th><th>Status</th><th>Ações</th></tr></thead><tbody><?php foreach($radars as $r): ?><tr><td><div class="radar-kind"><span class="kind-icon <?=radar_badge_class((string)$r['tipo'])?>"></span><div><b><?=htmlspecialchars(radar_type_label((string)$r['tipo']))?></b><small>#<?=(int)$r['id']?> · <?=htmlspecialchars((string)($r['external_id']?:'sem ID externo'))?></small></div></div></td><td><b><?=htmlspecialchars((string)($r['cidade']?:'—'))?></b><small class="subline"><?=htmlspecialchars((string)($r['uf']?:'--'))?> · <?=number_format((float)$r['latitude'],5,'.','')?>, <?=number_format((float)$r['longitude'],5,'.','')?></small></td><td><?=htmlspecialchars((string)($r['rodovia']?:'—'))?><small class="subline"><?=$r['km']!==null&&$r['km']!==''?'KM '.htmlspecialchars((string)$r['km']):htmlspecialchars((string)($r['sentido']?:''))?></small></td><td><span class="speed-badge"><?=$r['velocidade']?(int)$r['velocidade'].'<small>km/h</small>':'—'?></span></td><td><?=htmlspecialchars((string)($r['fonte']?:'—'))?></td><td><span class="status-chip neutral"><?=htmlspecialchars((string)$r['confiabilidade'])?></span><small class="subline"><?=max(1,(int)$r['quantidade_fontes'])?> fonte(s)</small></td><td><span class="status-chip <?=$r['ativo']?'ok':'off'?>"><?=$r['ativo']?'ATIVO':'INATIVO'?></span></td><td><div class="row-actions"><form method="post"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token())?>"><input type="hidden" name="action" value="confirm_radar"><input type="hidden" name="radar_id" value="<?=$r['id']?>"><button class="icon-btn" title="Confirmar ponto">✓</button></form><form method="post"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token())?>"><input type="hidden" name="action" value="toggle_radar"><input type="hidden" name="radar_id" value="<?=$r['id']?>"><button class="icon-btn" title="<?=$r['ativo']?'Desativar':'Ativar'?>"><?=$r['ativo']?'Ⅱ':'▶'?></button></form></div></td></tr><?php endforeach; ?><?php if(!$radars): ?><tr><td colspan="8" class="empty">Nenhum ponto encontrado com esses filtros.</td></tr><?php endif; ?></tbody></table></div>
      </section>
      <?php endif; ?>

      <?php if($section==='library'): ?>
      <div class="metric-grid compact"><article class="metric-card"><span>Músicas</span><strong><?=$counts['tracks']?></strong><small>catalogadas</small></article><article class="metric-card"><span>Pastas Drive</span><strong><?=count($folders)?></strong><small>configuradas</small></article><article class="metric-card"><span>Radares ativos</span><strong><?=$counts['radars']?></strong><small>na base de rota</small></article><article class="metric-card"><span>Banco</span><strong class="text-value">MariaDB</strong><small>InnoDB · SecureDB</small></article></div>
      <section class="panel"><div class="panel-head"><div><span class="eyebrow">GOOGLE DRIVE</span><h3>Biblioteca pública</h3><p>Cadastre uma pasta pública; os arquivos continuam hospedados no Drive.</p></div></div><form method="post" class="form-grid"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token())?>"><input type="hidden" name="action" value="add_drive_folder"><label>Nome da pasta<input name="folder_name" placeholder="Ex.: Sertanejo"></label><label>Link público do Drive<input name="folder_link" placeholder="https://drive.google.com/drive/folders/..." required></label><div class="form-submit"><button class="primary-btn">Salvar pasta</button></div></form></section>
      <section class="panel"><div class="panel-head"><div><span class="eyebrow">FONTES DE MÚSICA</span><h3>Pastas cadastradas</h3></div></div><div class="table-wrap"><table><thead><tr><th>Nome</th><th>ID Drive</th><th>Status</th><th>Última importação</th><th></th></tr></thead><tbody><?php foreach($folders as $f): ?><tr><td><b><?=htmlspecialchars((string)$f['name'])?></b></td><td class="mono"><?=htmlspecialchars((string)$f['folder_id'])?></td><td><span class="status-chip <?=$f['active']?'ok':'off'?>"><?=$f['active']?'ATIVA':'PAUSADA'?></span></td><td><?=htmlspecialchars((string)$f['last_import_at'])?></td><td><form method="post"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token())?>"><input type="hidden" name="action" value="toggle_drive_folder"><input type="hidden" name="folder_id" value="<?=$f['id']?>"><button class="tiny-btn"><?=$f['active']?'Pausar':'Ativar'?></button></form></td></tr><?php endforeach; ?><?php if(!$folders): ?><tr><td colspan="5" class="empty">Nenhuma pasta cadastrada.</td></tr><?php endif; ?></tbody></table></div></section>
      <?php endif; ?>

      <?php if($section==='system'): ?>
      <section class="panel"><div class="panel-head"><div><span class="eyebrow">MAPA PREMIUM</span><h3>Mapbox GL JS</h3><p>Mapa-base online opcional com fallback automático para OpenStreetMap e mapas locais.</p></div><span class="status-chip <?=mapbox_client_config()['enabled']?'ok':'neutral'?>"><?=mapbox_client_config()['enabled']?'ATIVO':'OPCIONAL'?></span></div><form method="post" class="form-grid" autocomplete="off"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token())?>"><input type="hidden" name="action" value="save_mapbox"><label class="toggle-card mini"><input type="checkbox" name="mapbox_enabled" <?=app_setting('mapbox_enabled','0')==='1'?'checked':''?>><span><b>Ativar Mapbox</b><small>Se estiver indisponível, o mapa existente continua funcionando.</small></span></label><label>Token público Mapbox<input type="password" name="mapbox_public_token" placeholder="<?=mapbox_public_token()!==''?'Configurado — deixe vazio para manter':'pk.••••••••'?>" autocomplete="new-password"><small>Aceita somente pk. Nunca cole um token secreto sk.</small></label><label>Estilo Mapbox<input name="mapbox_style" value="<?=htmlspecialchars((string)app_setting('mapbox_style','mapbox://styles/mapbox/navigation-night-v1'))?>"><small>Ex.: mapbox://styles/mapbox/navigation-night-v1</small></label><label class="toggle-card mini"><input type="checkbox" name="clear_mapbox_public_token"><span><b>Remover token salvo</b><small>Também desative o Mapbox antes de salvar.</small></span></label><div class="form-submit"><button class="primary-btn">Salvar Mapbox</button></div></form><p class="muted">Nesta arquitetura WebView, a cobrança é por carregamento do Mapbox GL JS (franquia publicada de 50 mil/mês), não pela franquia de 25 mil MAU do SDK móvel nativo. Rotas e buscas têm medidores separados.</p></section>
      <div class="system-grid"><section class="panel"><div class="panel-head"><div><span class="eyebrow">ENDEREÇOS</span><h3>CNEFE local</h3><p>Busca de ruas sem Mapbox e sem Google.</p></div><span class="status-chip ok">LOCAL</span></div><div class="mini-metrics"><div><span>Municípios</span><b><?=$counts['address_cities']?></b></div><div><span>Vias indexadas</span><b><?=number_format($counts['address_streets'],0,',','.')?></b></div></div><p class="muted">Cada município é preparado sob demanda e fica indexado no MariaDB/MySQL do servidor.</p></section><section class="panel"><div class="panel-head"><div><span class="eyebrow">BRASIL LIGHT</span><h3>Mapa sob demanda</h3><p>Cache municipal com limite automático.</p></div><span class="status-chip ok"><?=number_format(((int)($lightMapAdminStats['size_bytes']??0))/1048576,1,',','.')?> MB</span></div><div class="mini-metrics"><div><span>Cidades no cache</span><b><?=((int)($lightMapAdminStats['city_count']??0))?></b></div><div><span>Limite</span><b><?=htmlspecialchars((string)app_setting('light_map_cache_mb','256'))?> MB</b></div></div></section></div>
      <section class="panel"><div class="panel-head"><div><span class="eyebrow">CONFIGURAÇÃO</span><h3>Sistema e armazenamento</h3></div></div><form method="post" class="form-grid"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token())?>"><input type="hidden" name="action" value="save_system"><label>Limite do mapa Brasil Light (MB)<input type="number" min="32" max="2048" step="16" name="light_map_cache_mb" value="<?=htmlspecialchars((string)app_setting('light_map_cache_mb','256'))?>"><small>Padrão 256 MB. O cache remove cidades menos usadas ao atingir o limite.</small></label><label>Google API Key opcional<input type="password" name="google_api_key" placeholder="<?=google_api_key()!==''?'Configurada — deixe vazio para manter':'Não configurada'?>"><small>Não é necessária para o CNEFE local.</small></label><label class="toggle-card mini"><input type="checkbox" name="clear_google_api_key"><span><b>Remover Google API Key</b><small>Marque somente se quiser apagar a chave salva.</small></span></label><div class="form-submit"><button class="primary-btn">Salvar sistema</button></div></form></section>
      <div class="system-grid"><section class="panel"><div class="panel-head"><div><span class="eyebrow">SEGURANÇA</span><h3>Senha do administrador</h3></div></div><form method="post" class="stack"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token())?>"><input type="hidden" name="action" value="change_admin_password"><label>Nova senha<input type="password" name="password" minlength="10" required></label><button class="secondary-btn">Alterar senha</button></form></section><section class="panel"><div class="panel-head"><div><span class="eyebrow">INSTALAÇÃO</span><h3>MusicRoad 1.2.0 · SecureDB 1.2.0</h3></div><span class="status-chip ok">INSTALADO</span></div><p class="muted">Mapas estaduais no dispositivo, endereços CNEFE, rota resiliente, radares integrados, Control Center administrativo, Mercado Pago PIX e teste gratuito protegido.</p><a class="secondary-btn full" href="index.php">Abrir MusicRoad</a></section></div>
      <?php endif; ?>
    </div>
  </main>
</div>
<script nonce="<?=htmlspecialchars(csp_nonce(),ENT_QUOTES)?>">
(function(){
  var result=document.getElementById('radarApiResult');
  function show(text,ok){if(!result)return;result.hidden=false;result.className='api-result '+(ok?'ok':'bad');result.textContent=text;}
  async function api(url,method){try{show('Processando...',true);var headers={'Accept':'application/json'};if((method||'GET')!=='GET')headers['X-CSRF-Token']='<?=htmlspecialchars(csrf_token(),ENT_QUOTES)?>';var r=await fetch(url,{method:method||'GET',credentials:'same-origin',headers:headers});var d=await r.json();if(url.indexOf('health')>-1){var db=d.checks&&d.checks.database;show((d.ok?'✓ Fontes operacionais':'⚠ Verificação parcial')+(db?' · '+db.active+' radares ativos no banco':''),!!d.ok);}else{show((d.ok?'✓ ':'⚠ ')+(d.message||'Sincronização concluída.'),!!d.ok);if(d.ok)setTimeout(function(){location.reload();},1200);}}catch(e){show('Falha ao comunicar com o servidor: '+e.message,false);}}
  var hb=document.getElementById('radarHealthBtn'),sb=document.getElementById('radarSyncBtn');
  if(hb)hb.addEventListener('click',function(){api('api/radar_health.php','GET')});
  if(sb)sb.addEventListener('click',function(){api('api/radar_sync_brazil.php','POST')});
  var menu=document.getElementById('adminMenuToggle');
  if(menu)menu.addEventListener('click',function(){document.body.classList.toggle('nav-open')});
  document.querySelectorAll('.reject-road-report').forEach(function(form){form.addEventListener('submit',function(event){if(!confirm('Rejeitar este relato?'))event.preventDefault();});});
  document.addEventListener('click',function(e){if(document.body.classList.contains('nav-open')&&!e.target.closest('.console-sidebar')&&!e.target.closest('.menu-button'))document.body.classList.remove('nav-open');});
})();
</script>
</body>
</html>
