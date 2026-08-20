<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';

header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
$user = require_login();
$userId = (int)$user['id'];
$action = (string)($_GET['action'] ?? 'status');
$method = strtoupper((string)($_SERVER['REQUEST_METHOD'] ?? 'GET'));
$google = is_array($config['google'] ?? null) ? $config['google'] : [];
$clientId = trim((string)($google['client_id'] ?? ''));
$clientSecret = trim((string)($google['client_secret'] ?? ''));
$redirect = trim((string)($google['redirect_uri'] ?? ''));
$scopes = is_array($google['scopes'] ?? null) ? $google['scopes'] : ['https://www.googleapis.com/auth/drive.readonly'];

function google_oauth_token_request(array $fields): array
{
    if (!function_exists('curl_init')) return ['ok'=>false,'error'=>'cURL não está disponível no servidor.'];
    $handle = curl_init('https://oauth2.googleapis.com/token');
    curl_setopt_array($handle,[
        CURLOPT_RETURNTRANSFER=>true,
        CURLOPT_POST=>true,
        CURLOPT_POSTFIELDS=>http_build_query($fields,'','&',PHP_QUERY_RFC3986),
        CURLOPT_CONNECTTIMEOUT=>10,
        CURLOPT_TIMEOUT=>25,
        CURLOPT_HTTPHEADER=>['Accept: application/json','Content-Type: application/x-www-form-urlencoded'],
    ]);
    $raw = curl_exec($handle);
    $status = (int)curl_getinfo($handle,CURLINFO_RESPONSE_CODE);
    $error = curl_error($handle);
    curl_close($handle);
    $data = is_string($raw) ? json_decode($raw,true) : null;
    if ($status < 200 || $status >= 300 || !is_array($data)) {
        $message = is_array($data) ? (string)($data['error_description'] ?? $data['error'] ?? '') : '';
        if ($message === '') $message = $error !== '' ? $error : 'O Google recusou a troca do token.';
        return ['ok'=>false,'error'=>$message,'status'=>$status];
    }
    return ['ok'=>true,'data'=>$data];
}

function google_oauth_redirect_result(bool $ok, string $message): never
{
    global $config;
    if (str_contains(strtolower((string)($_SERVER['HTTP_ACCEPT'] ?? '')),'application/json')) {
        json_response(['ok'=>$ok,'message'=>$message],$ok ? 200 : 400);
    }
    $base = rtrim((string)($config['app_url'] ?? ''),'/');
    $target = ($base !== '' ? $base : '..').'/index.php?drive='.($ok ? 'connected' : 'error').'&message='.rawurlencode($message);
    header('Location: '.$target);
    exit;
}

if ($action === 'status') {
    if ($method !== 'GET') json_response(['ok'=>false,'error'=>'Use GET.'],405);
    $stmt = db()->prepare('SELECT access_token,refresh_token,expires_at,scope,updated_at FROM google_tokens WHERE user_id=? LIMIT 1');
    $stmt->execute([$userId]);
    $token = $stmt->fetch();
    $connected = false;
    $expiresAt = null;
    if ($token) {
        if (!str_starts_with((string)$token['access_token'],'enc:v1:')) {
            $token['access_token'] = secret_encrypt((string)$token['access_token']);
            $token['refresh_token'] = (string)($token['refresh_token'] ?? '') !== '' ? secret_encrypt((string)$token['refresh_token']) : null;
            db()->prepare('UPDATE google_tokens SET access_token=?,refresh_token=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=?')->execute([$token['access_token'],$token['refresh_token'],$userId]);
        }
        $access = secret_decrypt((string)$token['access_token']);
        $refresh = secret_decrypt((string)($token['refresh_token'] ?? ''));
        $expiresAt = (int)$token['expires_at'];
        $connected = $access !== '' && ($expiresAt > time() || $refresh !== '');
    }
    $csrf = csrf_token();
    json_response([
        'ok'=>true,
        'connected'=>$connected,
        'configured'=>$clientId !== '' && $clientSecret !== '' && $redirect !== '',
        'expires_at'=>$expiresAt,
        'csrf'=>$csrf,
        'auth_url'=>'google_drive.php?action=auth&csrf='.rawurlencode($csrf),
    ]);
}

if ($action === 'auth') {
    if ($method !== 'GET') json_response(['ok'=>false,'error'=>'Use GET.'],405);
    $csrf = (string)($_GET['csrf'] ?? '');
    if ($csrf === '' || !hash_equals(csrf_token(),$csrf)) json_response(['ok'=>false,'error'=>'Sessão expirada. Atualize a página.'],419);
    if ($clientId === '' || $clientSecret === '' || $redirect === '') {
        json_response(['ok'=>false,'error'=>'Configure Client ID, Client Secret e Redirect URI.'],422);
    }
    $state = bin2hex(random_bytes(32));
    $_SESSION['google_oauth'] = ['state'=>$state,'user_id'=>$userId,'created_at'=>time()];
    $params = http_build_query([
        'client_id'=>$clientId,
        'redirect_uri'=>$redirect,
        'response_type'=>'code',
        'scope'=>implode(' ',array_map('strval',$scopes)),
        'access_type'=>'offline',
        'include_granted_scopes'=>'true',
        'prompt'=>'consent',
        'state'=>$state,
    ],'','&',PHP_QUERY_RFC3986);
    header('Location: https://accounts.google.com/o/oauth2/v2/auth?'.$params);
    exit;
}

if ($action === 'callback') {
    if ($method !== 'GET') json_response(['ok'=>false,'error'=>'Use GET.'],405);
    $oauth = is_array($_SESSION['google_oauth'] ?? null) ? $_SESSION['google_oauth'] : [];
    unset($_SESSION['google_oauth']);
    $state = (string)($_GET['state'] ?? '');
    if (($oauth['created_at'] ?? 0) < time()-600 || (int)($oauth['user_id'] ?? 0) !== $userId || $state === '' || !hash_equals((string)($oauth['state'] ?? ''),$state)) {
        google_oauth_redirect_result(false,'Estado OAuth inválido ou expirado.');
    }
    if (isset($_GET['error'])) google_oauth_redirect_result(false,'Autorização cancelada pelo Google.');
    $code = trim((string)($_GET['code'] ?? ''));
    if ($code === '') google_oauth_redirect_result(false,'O Google não enviou o código de autorização.');
    session_write_close();
    $exchange = google_oauth_token_request([
        'code'=>$code,
        'client_id'=>$clientId,
        'client_secret'=>$clientSecret,
        'redirect_uri'=>$redirect,
        'grant_type'=>'authorization_code',
    ]);
    if (empty($exchange['ok'])) google_oauth_redirect_result(false,(string)($exchange['error'] ?? 'Falha ao conectar o Google Drive.'));
    $data = $exchange['data'];
    $accessToken = trim((string)($data['access_token'] ?? ''));
    if ($accessToken === '') google_oauth_redirect_result(false,'Resposta do Google sem token de acesso.');
    $existing = db()->prepare('SELECT refresh_token FROM google_tokens WHERE user_id=? LIMIT 1');
    $existing->execute([$userId]);
    $storedRefresh = (string)($existing->fetchColumn() ?: '');
    if ($storedRefresh !== '' && !str_starts_with($storedRefresh,'enc:v1:')) $storedRefresh = secret_encrypt($storedRefresh);
    $refreshToken = trim((string)($data['refresh_token'] ?? ''));
    $refreshEncrypted = $refreshToken !== '' ? secret_encrypt($refreshToken) : $storedRefresh;
    $expiresAt = time()+max(60,(int)($data['expires_in'] ?? 3600))-30;
    $stmt = db()->prepare('INSERT INTO google_tokens(user_id,access_token,refresh_token,expires_at,scope,created_at,updated_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE access_token=VALUES(access_token),refresh_token=VALUES(refresh_token),expires_at=VALUES(expires_at),scope=VALUES(scope),updated_at=CURRENT_TIMESTAMP');
    $stmt->execute([$userId,secret_encrypt($accessToken),$refreshEncrypted,$expiresAt,(string)($data['scope'] ?? implode(' ',$scopes))]);
    audit_log('google_drive.connected',['user_id'=>$userId,'expires_at'=>$expiresAt]);
    google_oauth_redirect_result(true,'Google Drive conectado com segurança.');
}

if ($action === 'disconnect') {
    if ($method !== 'POST') json_response(['ok'=>false,'error'=>'Use POST.'],405);
    require_csrf();
    db()->prepare('DELETE FROM google_tokens WHERE user_id=?')->execute([$userId]);
    audit_log('google_drive.disconnected',['user_id'=>$userId]);
    json_response(['ok'=>true,'connected'=>false]);
}

json_response(['ok'=>false,'error'=>'Ação inválida.'],400);
