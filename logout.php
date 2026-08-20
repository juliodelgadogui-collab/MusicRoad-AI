<?php
require __DIR__ . '/api/bootstrap.php';
header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
$method=strtoupper((string)($_SERVER['REQUEST_METHOD']??'GET'));
if($method==='POST')require_csrf();
elseif($method==='GET'){
    // Compatibilidade com a tela de licença antiga: apenas navegação iniciada na própria origem.
    $site=strtolower((string)($_SERVER['HTTP_SEC_FETCH_SITE']??''));$mode=strtolower((string)($_SERVER['HTTP_SEC_FETCH_MODE']??''));
    $sameOrigin=in_array($site,['same-origin','none'],true);
    if($site===''){$ref=parse_url((string)($_SERVER['HTTP_REFERER']??''));$app=parse_url((string)($config['app_url']??''));$sameOrigin=is_array($ref)&&is_array($app)&&strcasecmp((string)($ref['host']??''),(string)($app['host']??''))===0;}
    if(!$sameOrigin||($mode!==''&&$mode!=='navigate')){http_response_code(405);header('Allow: POST');exit('Use o botão Sair dentro do MusicRoad.');}
}else{http_response_code(405);header('Allow: POST');exit('Método inválido.');}
audit_log('auth.logout', ['user_id' => $_SESSION['user_id'] ?? null]);
$_SESSION = [];
if(ini_get('session.use_cookies')){
    $params=session_get_cookie_params();
    setcookie(session_name(),'',[
        'expires'=>time()-42000,'path'=>$params['path'],'domain'=>$params['domain'],
        'secure'=>$params['secure'],'httponly'=>$params['httponly'],'samesite'=>$params['samesite']??'Lax'
    ]);
}
session_destroy();
header('Location: login.php');
