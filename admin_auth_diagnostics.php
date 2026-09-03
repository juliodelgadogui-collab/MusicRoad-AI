<?php
declare(strict_types=1);

require __DIR__ . '/api/bootstrap.php';
require_once __DIR__ . '/api/native_auth.php';

ensure_default_users();
$admin = require_admin();
native_auth_ensure_schema();

function epcad_e(mixed $value): string { return htmlspecialchars((string)$value, ENT_QUOTES, 'UTF-8'); }
function epcad_mask(string $value): string {
    $value = trim($value);
    if ($value === '') return '—';
    if (strlen($value) <= 12) return substr($value, 0, 4) . '…';
    return substr($value, 0, 6) . '…' . substr($value, -5);
}
function epcad_fp(string $value): string { return $value === '' ? '' : substr(hash('sha256', $value), 0, 16); }
function epcad_age(?string $value): string {
    if (!$value) return '—';
    $ts = strtotime($value);
    if ($ts === false) return (string)$value;
    $s = max(0, time() - $ts);
    if ($s < 60) return $s . ' s';
    if ($s < 3600) return (string)floor($s / 60) . ' min';
    if ($s < 86400) return (string)floor($s / 3600) . ' h';
    return (string)floor($s / 86400) . ' d';
}
function epcad_expired(?string $value): bool {
    if (!$value) return true;
    $ts = strtotime($value);
    return $ts === false || $ts <= time();
}

$driver = (string)db()->getAttribute(PDO::ATTR_DRIVER_NAME);
$cfg = $GLOBALS['config'] ?? [];
$dbIdentity = $driver;
if ($driver === 'mysql') {
    $dbIdentity .= '|' . (string)($cfg['db']['mysql_dsn'] ?? '') . '|' . (string)($cfg['db']['mysql_user'] ?? '');
} else {
    $dbIdentity .= '|' . (string)($cfg['db']['sqlite_path'] ?? '');
}
$dbFp = epcad_fp($dbIdentity);
$salt = trim((string)app_setting('native_auth_salt_v207', ''));
$saltFp = epcad_fp($salt);

$expectedColumns = ['device_id','user_id','secret_hash','access_hash','access_expires_at','refresh_hash','refresh_expires_at','revoked_at','created_at','updated_at','last_used_at'];
$columns = schema_columns('native_device_credentials');
$missingColumns = array_values(array_diff($expectedColumns, $columns));

$credentialCount = 0;
$deviceCount = 0;
try { $credentialCount = (int)db()->query('SELECT COUNT(*) FROM native_device_credentials')->fetchColumn(); } catch (Throwable $e) {}
try { $deviceCount = (int)db()->query('SELECT COUNT(*) FROM client_device_state')->fetchColumn(); } catch (Throwable $e) {}

// Cross-request database write/read probe. It stores no credential or personal information.
$previousProbe = trim((string)app_setting('native_auth_diag_probe_v1', ''));
$probe = date('c') . '|' . bin2hex(random_bytes(5));
set_app_setting('native_auth_diag_probe_v1', $probe);
$probeRead = trim((string)app_setting('native_auth_diag_probe_v1', ''));
$dbWriteReadOk = hash_equals($probe, $probeRead);

// Filesystem baseline detects a database or salt identity change even if app_settings itself switches DBs.
$stateFile = __DIR__ . '/logs/native-auth-diagnostic-state.json';
$previousState = [];
if (is_file($stateFile)) {
    $decoded = json_decode((string)@file_get_contents($stateFile), true);
    if (is_array($decoded)) $previousState = $decoded;
}
$dbChanged = !empty($previousState['db_fp']) && !hash_equals((string)$previousState['db_fp'], $dbFp);
$saltChanged = !empty($previousState['salt_fp']) && !hash_equals((string)$previousState['salt_fp'], $saltFp);
$statePayload = [
    'checked_at' => date('c'),
    'db_fp' => $dbFp,
    'salt_fp' => $saltFp,
    'driver' => $driver,
    'credential_count' => $credentialCount,
];
$stateWriteOk = @file_put_contents($stateFile, json_encode($statePayload, JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT), LOCK_EX) !== false;

$rows = [];
try {
    $sql = "SELECT d.user_id,d.device_id,d.device_name,d.last_seen_at,
                   u.name user_name,u.username,u.email,u.status user_status,
                   c.created_at secure_since,c.updated_at secure_updated_at,c.last_used_at,c.revoked_at,
                   c.access_expires_at,c.refresh_expires_at
            FROM client_device_state d
            JOIN users u ON u.id=d.user_id
            LEFT JOIN native_device_credentials c ON c.device_id=d.device_id AND c.user_id=d.user_id
            ORDER BY d.last_seen_at DESC LIMIT 150";
    $rows = db()->query($sql)->fetchAll() ?: [];
} catch (Throwable $e) {}

$events = [];
try {
    $s = db()->query("SELECT action,payload,created_at FROM audit_logs WHERE action LIKE 'native.%' ORDER BY id DESC LIMIT 40");
    foreach (($s->fetchAll() ?: []) as $event) {
        $payload = json_decode((string)($event['payload'] ?? ''), true);
        $events[] = [
            'action' => (string)($event['action'] ?? ''),
            'created_at' => (string)($event['created_at'] ?? ''),
            'user_id' => is_array($payload) ? (int)($payload['user_id'] ?? 0) : 0,
            'secure_device' => is_array($payload) ? (bool)($payload['secure_device'] ?? false) : false,
        ];
    }
} catch (Throwable $e) {}

$critical = [];
$warnings = [];
if ($salt === '') $critical[] = 'native_auth_salt_v207 não está persistido em app_settings.';
if ($missingColumns) $critical[] = 'Tabela native_device_credentials incompleta: ' . implode(', ', $missingColumns) . '.';
if (!$dbWriteReadOk) $critical[] = 'Falha ao gravar e reler app_settings na mesma requisição.';
if ($dbChanged) $critical[] = 'A identidade do banco mudou entre requisições.';
if ($saltChanged) $critical[] = 'O fingerprint do salt de autenticação mudou entre requisições.';
if (!$stateWriteOk) $warnings[] = 'Não foi possível gravar o baseline em logs/. A comparação entre requisições ficará limitada.';
if ($previousProbe === '') $warnings[] = 'Primeira execução do probe. Atualize esta página uma vez para confirmar persistência entre requisições.';
if ($credentialCount === 0) $warnings[] = 'Nenhuma credencial segura existe em native_device_credentials.';
if ($deviceCount > 0 && $credentialCount < 1) $warnings[] = 'Há aparelhos cadastrados, mas nenhum vínculo seguro persistido.';

$status = $critical ? 'FALHA' : ($warnings ? 'ATENÇÃO' : 'OK');
$statusClass = $critical ? 'bad' : ($warnings ? 'warn' : 'ok');
?><!doctype html>
<html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="theme-color" content="#080507"><title>EPC · Diagnóstico da autenticação</title>
<style>
:root{--bg:#080507;--panel:#160b0e;--panel2:#211014;--line:#4b2931;--text:#f5eee2;--muted:#ae9792;--red:#d4142f;--gold:#e2b94c;--green:#45d483;--blue:#5da9ff}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif}.shell{max-width:1280px;margin:auto;padding:24px}.top{display:flex;justify-content:space-between;gap:18px;align-items:flex-start}.kicker{color:var(--red);font-size:11px;font-weight:850;letter-spacing:.14em}.top h1{margin:4px 0 5px;font-size:29px}.muted{color:var(--muted)}a{color:var(--text);text-decoration:none}.actions{display:flex;gap:8px;flex-wrap:wrap}.btn{border:1px solid var(--line);border-radius:11px;background:var(--panel2);padding:10px 14px;font-weight:750}.grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:11px;margin:20px 0}.card{background:var(--panel);border:1px solid #3d2228;border-radius:16px;padding:16px}.metric{font-size:26px;font-weight:850}.label{font-size:10px;color:var(--muted);letter-spacing:.1em;text-transform:uppercase}.notice{border:1px solid;border-radius:13px;padding:14px 16px;margin:10px 0}.notice.ok{background:#102c21;border-color:#286748}.notice.warn{background:#302610;border-color:#786128}.notice.bad{background:#3a1118;border-color:#8e2735}.mono{font-family:ui-monospace,SFMono-Regular,Consolas,monospace}.tablewrap{overflow:auto;border:1px solid #3d2228;border-radius:16px;background:var(--panel);margin-top:14px}table{width:100%;border-collapse:collapse;min-width:1050px}th,td{padding:12px;border-bottom:1px solid #321b20;text-align:left;vertical-align:top}th{font-size:10px;text-transform:uppercase;letter-spacing:.08em;color:var(--muted);background:#10080a}.pill{display:inline-block;border:1px solid #554047;border-radius:99px;padding:4px 8px;font-size:10px;font-weight:850}.pill.ok{color:var(--green);border-color:#286748;background:#102c21}.pill.warn{color:var(--gold);border-color:#786128;background:#302610}.pill.bad{color:#ff8790;border-color:#8e2735;background:#3a1118}.section{margin-top:24px}.section h2{margin:0 0 6px;font-size:20px}.small{font-size:12px}.steps{line-height:1.55}.audit{display:grid;grid-template-columns:180px 1fr 110px;gap:8px;padding:9px 0;border-bottom:1px solid #321b20}.audit:last-child{border:0}@media(max-width:800px){.shell{padding:15px}.top{flex-direction:column}.grid{grid-template-columns:repeat(2,1fr)}.audit{grid-template-columns:1fr}}
</style></head><body><main class="shell">
<header class="top"><div><div class="kicker">EPC · AUTH DIAGNOSTICS V1</div><h1>Diagnóstico da sessão do aparelho</h1><div class="muted">Somente administrador. Nenhum token, senha, segredo ou hash completo é exibido.</div></div><div class="actions"><a class="btn" href="admin_devices.php">Aparelhos</a><a class="btn" href="admin_epc.php">Painel EPC</a><a class="btn" href="admin_auth_diagnostics.php">Atualizar</a></div></header>

<div class="notice <?=$statusClass?>"><strong>RESULTADO: <?=epcad_e($status)?></strong><br><?php if(!$critical && !$warnings):?>Banco, salt, schema e persistência básica estão consistentes nesta verificação.<?php else:?><?php foreach($critical as $x):?><div>• <?=epcad_e($x)?></div><?php endforeach;?><?php foreach($warnings as $x):?><div>• <?=epcad_e($x)?></div><?php endforeach;?><?php endif;?></div>

<section class="grid">
<div class="card"><div class="label">Driver</div><div class="metric"><?=epcad_e(strtoupper($driver))?></div><div class="small muted">DB fp <span class="mono"><?=epcad_e($dbFp)?></span></div></div>
<div class="card"><div class="label">Salt de autenticação</div><div class="metric"><?=$salt!==''?'PRESENTE':'AUSENTE'?></div><div class="small muted">salt fp <span class="mono"><?=epcad_e($saltFp?:'—')?></span></div></div>
<div class="card"><div class="label">Credenciais seguras</div><div class="metric"><?=$credentialCount?></div><div class="small muted">aparelhos conhecidos: <?=$deviceCount?></div></div>
<div class="card"><div class="label">Probe app_settings</div><div class="metric"><?=$dbWriteReadOk?'OK':'FALHA'?></div><div class="small muted">anterior: <?=$previousProbe!==''?'encontrado':'primeira execução'?></div></div>
</section>

<section class="section"><h2>Aparelhos e credenciais</h2><div class="muted small">Procure pelo modelo do seu celular. Após fazer login, “credencial” deve ficar SEGURA e “último uso seguro” deve avançar quando o app é reaberto.</div>
<div class="tablewrap"><table><thead><tr><th>Usuário</th><th>Aparelho</th><th>Credencial</th><th>Última presença</th><th>Último uso seguro</th><th>Refresh</th><th>Leitura</th></tr></thead><tbody>
<?php foreach($rows as $r):
$has=!empty($r['secure_since']);$rev=!empty($r['revoked_at']);$refreshExpired=epcad_expired($r['refresh_expires_at']??null);
$reading='OK';$cls='ok';
if(!$has){$reading='SEM CREDENCIAL';$cls='bad';}
elseif($rev){$reading='REVOGADA';$cls='bad';}
elseif($refreshExpired){$reading='REFRESH EXPIRADO';$cls='bad';}
elseif(empty($r['last_used_at'])){$reading='NUNCA REVALIDOU';$cls='warn';}
?>
<tr><td><strong><?=epcad_e($r['user_name']?:$r['username'])?></strong><br><span class="muted small"><?=epcad_e($r['username'])?></span></td>
<td><strong><?=epcad_e($r['device_name']?:'Android')?></strong><br><span class="mono small"><?=epcad_e(epcad_mask((string)$r['device_id']))?></span></td>
<td><?php if($rev):?><span class="pill bad">REVOGADA</span><?php elseif($has):?><span class="pill ok">SEGURA</span><?php else:?><span class="pill warn">LEGADA</span><?php endif;?><br><span class="muted small"><?=epcad_e($r['secure_since']?:'—')?></span></td>
<td><?=epcad_e($r['last_seen_at']?:'—')?><br><span class="muted small">há <?=epcad_e(epcad_age($r['last_seen_at']??null))?></span></td>
<td><?=epcad_e($r['last_used_at']?:'—')?><br><span class="muted small">há <?=epcad_e(epcad_age($r['last_used_at']??null))?></span></td>
<td><?=epcad_e($r['refresh_expires_at']?:'—')?><?php if($has):?><br><span class="pill <?=$refreshExpired?'bad':'ok'?>"><?=$refreshExpired?'EXPIRADO':'VÁLIDO'?></span><?php endif;?></td>
<td><span class="pill <?=$cls?>"><?=epcad_e($reading)?></span></td></tr>
<?php endforeach;?><?php if(!$rows):?><tr><td colspan="7" class="muted">Nenhum aparelho registrado.</td></tr><?php endif;?></tbody></table></div></section>

<section class="section"><h2>Últimos eventos de autenticação</h2><div class="card">
<?php foreach($events as $ev):?><div class="audit"><span class="mono small"><?=epcad_e($ev['created_at'])?></span><strong><?=epcad_e($ev['action'])?></strong><span class="small muted">user #<?=epcad_e((string)$ev['user_id'])?></span></div><?php endforeach;?><?php if(!$events):?><div class="muted">Nenhum evento native.* encontrado no audit_logs.</div><?php endif;?></div></section>

<section class="section"><h2>Como confirmar o defeito</h2><div class="card steps small">
1. Atualize esta página uma vez. O resultado não deve acusar mudança de <span class="mono">DB fp</span> ou <span class="mono">salt fp</span>.<br>
2. No celular, faça login no Estrada Play uma vez.<br>
3. Atualize esta página: seu aparelho deve aparecer como <strong>SEGURA</strong> e com refresh válido.<br>
4. Feche completamente o app e abra novamente.<br>
5. Atualize esta página de novo. Se o app pedir senha e o aparelho continuar SEGURA, compare “último uso seguro”. Se não avançar, o servidor rejeitou a revalidação; se a credencial sumir, o banco/tabela não está persistindo; se salt/db fp mudar, a causa está confirmada no backend.<br><br>
Baseline em arquivo: <strong><?=$stateWriteOk?'OK':'NÃO GRAVOU'?></strong>. PHP <?=epcad_e(PHP_VERSION)?> · servidor <?=epcad_e(date('Y-m-d H:i:s'))?>.
</div></section>
</main></body></html>
