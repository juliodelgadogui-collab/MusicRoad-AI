<?php
declare(strict_types=1);
require __DIR__ . '/api/bootstrap.php';
header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
header('Pragma: no-cache');
$error='';
if(current_user()){ $u=current_user(); header('Location: '.(($u['role']??'')==='admin'?'admin.php':(user_has_access($u)?'index.php':'license.php'))); exit; }
if($_SERVER['REQUEST_METHOD']==='POST'){
    require_csrf();
    $login=trim((string)($_POST['login']??''));
    $password=(string)($_POST['password']??'');
    $deviceToken=trim((string)($_POST['device_token']??''));
    $deviceName=trim((string)($_POST['device_name']??''));
    $appVersion=trim((string)($_POST['app_version']??''));
    if(auth_rate_limited($login)){
        usleep(450000);$error='Muitas tentativas. Aguarde alguns minutos e tente novamente.';
    }else{
        $stmt=db()->prepare('SELECT * FROM users WHERE username=? OR email=? LIMIT 1');$stmt->execute([$login,$login]);$found=$stmt->fetch();
        $passwordHash=$found?(string)$found['password_hash']:'$2y$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2uheWG/igi.';$passwordValid=password_verify($password,$passwordHash);
        if($found && ($found['status']??'active')==='active' && $passwordValid){
            session_regenerate_id(true);$_SESSION['user_id']=(int)$found['id'];
            if(password_needs_rehash((string)$found['password_hash'],defined('PASSWORD_ARGON2ID')?PASSWORD_ARGON2ID:PASSWORD_DEFAULT,defined('PASSWORD_ARGON2ID')?['memory_cost'=>65536,'time_cost'=>4,'threads'=>2]:[])){
                db()->prepare('UPDATE users SET password_hash=?,last_login_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?')->execute([secure_password_hash($password),(int)$found['id']]);
            }else db()->prepare('UPDATE users SET last_login_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?')->execute([(int)$found['id']]);
            try{
                if(str_starts_with($deviceToken,'android:'))registered_device_bind((int)$found['id'],$deviceToken,$deviceName?:'Android',$appVersion);
                auth_record_attempt($login,true);audit_log('auth.login',['username'=>$found['username']]);
                if(($found['role']??'')==='admin'){header('Location: admin.php');exit;}
                header('Location: '.(user_has_access($found)?'index.php':'license.php'));exit;
            }catch(Throwable $bindError){
                unset($_SESSION['user_id']);auth_record_attempt($login,false);
                $message=$bindError->getMessage();
                if($bindError instanceof RuntimeException && !($bindError instanceof PDOException) && str_starts_with($message,'Este aparelho já está vinculado'))$error=$message;
                else{report_runtime_exception('login_bind',$bindError);$error='Não foi possível vincular este dispositivo agora. Tente novamente.';}
            }
        }else{
            auth_record_attempt($login,false);usleep(250000);$error='Usuário, senha ou status de acesso inválido.';
        }
    }
}
?>
<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><meta name="theme-color" content="#070b10"><title>Entrar · MusicRoad</title><link rel="stylesheet" href="assets/css/login-v15.css?v=1.2.0"><style>.signup{display:flex;justify-content:center;align-items:center;border:1px solid #315069;border-radius:12px;padding:13px;color:#f1f6f9;text-decoration:none;font-weight:900;font-size:12px;margin-top:10px}.trial-note{display:flex;justify-content:space-between;gap:8px;align-items:center;background:#0b271d;border:1px solid #1a503b;color:#8ce6be;padding:10px 12px;border-radius:11px;font-size:11px;margin-bottom:14px}.trial-note b{color:#d8fbed}</style></head><body><main class="wrap"><section class="login"><div class="brand"><div class="mark">MR</div><div><b>MusicRoad</b><small>NAVEGAÇÃO · ALERTAS · MÚSICA</small></div></div><div class="trial-note"><span>Novo por aqui?</span><b>TESTE GRÁTIS 24H</b></div><h1>Seu caminho começa aqui.</h1><p>Entre para abrir seu painel de bordo. A validade do acesso é verificada automaticamente.</p><?php if($error): ?><div class="alert"><?=htmlspecialchars($error)?></div><?php endif; ?><form method="post" autocomplete="off"><input type="hidden" name="csrf" value="<?=htmlspecialchars(csrf_token())?>"><input type="hidden" name="device_token" id="deviceToken"><input type="hidden" name="device_name" id="deviceName"><input type="hidden" name="app_version" id="appVersion"><label>Usuário ou e-mail<input name="login" autocomplete="username" required></label><label>Senha<input type="password" name="password" autocomplete="current-password" required></label><button>ENTRAR NO MUSICROAD</button></form><?php if(trial_registration_enabled()): ?><a class="signup" href="register.php">CRIAR CONTA · TESTAR POR 24 HORAS</a><?php endif; ?><div class="foot">MusicRoad 1.2 · acesso por conta e dispositivo</div></section></main><script nonce="<?=htmlspecialchars(csp_nonce(),ENT_QUOTES)?>">(function(){try{if(window.MusicRoadAndroid?.getTrialDeviceToken){document.getElementById('deviceToken').value=String(window.MusicRoadAndroid.getTrialDeviceToken()||'');document.getElementById('deviceName').value=String(window.MusicRoadAndroid.deviceName?.()||'Android');document.getElementById('appVersion').value=String(window.MusicRoadAndroid.version?.()||'')}}catch(_){}})();</script></body></html>
