<?php
declare(strict_types=1);
require __DIR__ . '/api/bootstrap.php';
header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
header('Pragma: no-cache');
if(current_user()){ $u=current_user(); header('Location: '.(($u['role']??'')==='admin'?'admin.php':(user_has_access($u)?'index.php':'license.php'))); exit; }
$error='';
if($_SERVER['REQUEST_METHOD']==='POST'){
    require_csrf();
    $name=trim((string)($_POST['name']??''));
    $email=strtolower(trim((string)($_POST['email']??'')));
    $username=strtolower(trim((string)($_POST['username']??'')));
    $password=(string)($_POST['password']??'');
    $confirm=(string)($_POST['confirm_password']??'');
    $deviceToken=trim((string)($_POST['device_token']??''));
    try{
        if(mb_strlen($name)<2||mb_strlen($name)>90)throw new RuntimeException('Informe seu nome.');
        if(!filter_var($email,FILTER_VALIDATE_EMAIL)||strlen($email)>160)throw new RuntimeException('Informe um e-mail válido.');
        if(!preg_match('/^[a-z0-9._-]{3,32}$/',$username))throw new RuntimeException('O login deve ter 3 a 32 caracteres: letras, números, ponto, hífen ou underline.');
        if(strlen($password)<8)throw new RuntimeException('A senha precisa ter pelo menos 8 caracteres.');
        if($password!==$confirm)throw new RuntimeException('As senhas não conferem.');
        $elig=trial_check_eligibility($deviceToken);
        if(empty($elig['ok'])){audit_log('trial.blocked',['reason'=>$elig['reason']??'blocked']);throw new RuntimeException((string)($elig['reason']??'Teste indisponível.'));}
        $exists=db()->prepare('SELECT id FROM users WHERE username=? OR email=? LIMIT 1');$exists->execute([$username,$email]);
        if($exists->fetchColumn())throw new RuntimeException('Este login ou e-mail já está cadastrado. Use Entrar.');
        db()->beginTransaction();
        $st=db()->prepare("INSERT INTO users(name,email,username,password_hash,role,status,created_at,updated_at) VALUES(?,?,?,?, 'client','active',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        $st->execute([$name,$email,$username,secure_password_hash($password)]);
        $uid=(int)db()->lastInsertId();
        $hours=record_trial_claim($uid,$elig);
        grant_license($uid,1,'promo',null,'Teste gratuito de '.$hours.' horas · 1 por dispositivo/instalação');
        if(str_starts_with($deviceToken,'android:'))registered_device_bind($uid,$deviceToken,(string)($_POST['device_name']??'Android'),(string)($_POST['app_version']??''));
        db()->commit();
        session_regenerate_id(true);$_SESSION['user_id']=$uid;
        audit_log('trial.claimed',['user_id'=>$uid,'hours'=>$hours,'source'=>$elig['source']??'web']);
        header('Location: index.php?welcome=trial');exit;
    }catch(PDOException $e){
        try{if(db()->inTransaction())db()->rollBack();}catch(Throwable $ignored){}
        report_runtime_exception('register_database',$e);$error='Não foi possível criar a conta agora. Tente novamente.';
    }catch(RuntimeException $e){
        try{if(db()->inTransaction())db()->rollBack();}catch(Throwable $ignored){}
        $error=$e->getMessage();
    }catch(Throwable $e){
        try{if(db()->inTransaction())db()->rollBack();}catch(Throwable $ignored){}
        report_runtime_exception('register',$e);$error='Não foi possível criar a conta agora. Tente novamente.';
    }
}
?>
<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><meta name="theme-color" content="#070b10"><title>Criar conta · MusicRoad</title><link rel="stylesheet" href="assets/css/login-v15.css?v=1.2.0"><style>.trial-pill{display:inline-flex;align-items:center;gap:7px;padding:7px 10px;border-radius:999px;background:#0d2d21;color:#7ee2b8;font-size:11px;font-weight:900;margin-bottom:14px}.actions{display:grid;grid-template-columns:1fr 1fr;gap:10px}.secondary{display:flex;align-items:center;justify-content:center;border:1px solid var(--line);border-radius:12px;padding:13px;color:#d8e3eb;text-decoration:none;font-weight:800;font-size:12px}.device{font-size:11px;color:var(--muted);padding:10px 12px;border-radius:10px;background:#08141e;border:1px solid #1d3446}.privacy{font-size:10px;color:#718797;line-height:1.45;margin-top:10px}@media(max-width:600px){.actions{grid-template-columns:1fr}}</style></head><body><main class="wrap"><section class="login"><div class="brand"><div class="mark">MR</div><div><b>MusicRoad</b><small>CADASTRO · TESTE GRATUITO</small></div></div><span class="trial-pill">24 HORAS PARA TESTAR</span><h1>Crie sua conta.</h1><p>O teste começa na criação da conta e dura 24 horas. Para reduzir abuso, cada dispositivo/instalação pode receber o teste gratuito uma única vez.</p><?php if($error): ?><div class="alert"><?=htmlspecialchars($error)?></div><?php endif; ?><form method="post" id="registerForm" autocomplete="off"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token())?>"><input type="hidden" name="device_token" id="deviceToken" required><input type="hidden" name="device_name" id="deviceName"><input type="hidden" name="app_version" id="appVersion"><label>Nome<input name="name" maxlength="90" required value="<?=htmlspecialchars((string)($_POST['name']??''))?>"></label><label>E-mail<input type="email" name="email" autocomplete="email" required value="<?=htmlspecialchars((string)($_POST['email']??''))?>"></label><label>Login<input name="username" maxlength="32" autocomplete="username" required value="<?=htmlspecialchars((string)($_POST['username']??''))?>"></label><label>Senha<input type="password" name="password" minlength="8" autocomplete="new-password" required></label><label>Confirmar senha<input type="password" name="confirm_password" minlength="8" autocomplete="new-password" required></label><div class="device" id="deviceStatus">Validando esta instalação…</div><button id="createBtn" disabled>CRIAR CONTA E TESTAR 24H</button></form><div class="actions"><a class="secondary" href="login.php">JÁ TENHO CONTA</a><a class="secondary" href="license.php">PLANOS</a></div><div class="privacy">O MusicRoad não envia o identificador bruto do Android ao servidor: o APK gera um identificador derivado para limitar o teste. No navegador é usado um identificador desta instalação e um limite de rede como proteção adicional.</div><div class="foot">MusicRoad 1.2 · dispositivo vinculado automaticamente</div></section></main><script nonce="<?=htmlspecialchars(csp_nonce(),ENT_QUOTES)?>">
(function(){const field=document.getElementById('deviceToken'),status=document.getElementById('deviceStatus'),btn=document.getElementById('createBtn');function webToken(){let t='';try{t=localStorage.getItem('mr_trial_install_v1')||'';if(!t){const r=(crypto.randomUUID?crypto.randomUUID():Date.now().toString(36)+'-'+Math.random().toString(36).slice(2)+'-'+Math.random().toString(36).slice(2));t='web:'+r;localStorage.setItem('mr_trial_install_v1',t)}}catch(e){t='web:'+Date.now().toString(36)+'-'+Math.random().toString(36).slice(2)}return t}let token='';try{if(window.MusicRoadAndroid&&typeof window.MusicRoadAndroid.getTrialDeviceToken==='function')token=String(window.MusicRoadAndroid.getTrialDeviceToken()||'')}catch(e){}if(!token)token=webToken();field.value=token;try{document.getElementById('deviceName').value=String(window.MusicRoadAndroid?.deviceName?.()||'Android');document.getElementById('appVersion').value=String(window.MusicRoadAndroid?.version?.()||'')}catch(_){}const native=token.startsWith('android:');status.textContent=native?'Dispositivo Android reconhecido · teste único por aparelho/instalação.':'Navegador reconhecido · teste protegido por instalação e limite de rede.';btn.disabled=false;})();
</script></body></html>
