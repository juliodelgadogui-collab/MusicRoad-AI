<?php
declare(strict_types=1);
require __DIR__ . '/api/bootstrap.php';
require_once __DIR__ . '/api/native_auth.php';
ensure_default_users();
$admin = require_admin();
native_auth_ensure_schema();

function epcd_e(mixed $value): string { return htmlspecialchars((string)$value, ENT_QUOTES, 'UTF-8'); }
function epcd_mask(string $value): string {
    $value = trim($value);
    return $value === '' ? '—' : substr($value,0,6) . '…' . substr($value,-5);
}
function epcd_online(?string $value): bool {
    if (!$value) return false;
    $ts = strtotime($value);
    return $ts !== false && time() - $ts <= 900;
}

$message = '';
$error = '';
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    require_csrf();
    $action = strtolower(trim((string)($_POST['action'] ?? '')));
    try {
        if ($action === 'revoke_device') {
            $device = trim((string)($_POST['device_id'] ?? ''));
            if (!preg_match('/^[a-f0-9]{32,128}$/i',$device)) throw new RuntimeException('Identificador do aparelho inválido.');
            if (!native_revoke_device(strtolower($device))) throw new RuntimeException('Este aparelho ainda não possui credencial segura ativa.');
            audit_log('epc_admin.device_revoke',['device_id'=>epcd_mask($device),'admin_id'=>(int)($admin['id']??0)]);
            $message = 'Acesso seguro do aparelho revogado. Para autorizar novamente, o usuário precisará entrar com a senha nesse aparelho.';
        }
    } catch (Throwable $e) {
        $error = $e->getMessage() ?: 'Não foi possível concluir a ação.';
    }
}

$rows = [];
try {
    $sql = "SELECT d.user_id,d.device_id,d.device_name,d.last_seen_at,
                   u.name user_name,u.username,u.email,u.status user_status,
                   c.created_at secure_since,c.last_used_at,c.revoked_at,c.access_expires_at,c.refresh_expires_at
            FROM client_device_state d
            JOIN users u ON u.id=d.user_id
            LEFT JOIN native_device_credentials c ON c.device_id=d.device_id AND c.user_id=d.user_id
            ORDER BY d.last_seen_at DESC LIMIT 250";
    $rows = db()->query($sql)->fetchAll() ?: [];
} catch (Throwable $e) { $error = $error ?: 'Não foi possível carregar os aparelhos.'; }

$secure = 0; $revoked = 0; $online = 0;
foreach ($rows as $r) {
    if (!empty($r['secure_since'])) $secure++;
    if (!empty($r['revoked_at'])) $revoked++;
    if (epcd_online($r['last_seen_at'] ?? null)) $online++;
}
$csrf = csrf_token();
?><!doctype html>
<html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="theme-color" content="#080507"><title>EPC · Segurança de aparelhos</title>
<style>
:root{--bg:#080507;--panel:#160b0e;--panel2:#211014;--line:#563039;--text:#f5eee2;--muted:#ae9792;--red:#be1226;--gold:#e2b94c;--green:#45d483;--blue:#5da9ff}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif}.shell{max-width:1240px;margin:auto;padding:28px}.top{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:24px}.brand{display:flex;align-items:center;gap:12px}.star{width:48px;height:48px;display:grid;place-items:center;background:#650a18;border:1px solid #a31c2b;border-radius:14px;color:var(--gold);font-size:25px}.kicker{color:var(--red);font-weight:800;letter-spacing:.13em;font-size:12px}.top h1{margin:2px 0 3px;font-size:28px}.muted{color:var(--muted)}a{color:var(--text);text-decoration:none}.actions{display:flex;gap:8px;flex-wrap:wrap}.btn,button{border:1px solid var(--line);border-radius:11px;background:var(--panel2);color:var(--text);padding:10px 14px;font-weight:750;cursor:pointer}.btn.red,button.red{background:#5a0b17;border-color:#a31c2b}.metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin:18px 0}.card{background:var(--panel);border:1px solid #3d2228;border-radius:17px;padding:17px}.metric{font-size:30px;font-weight:850}.label{font-size:11px;text-transform:uppercase;letter-spacing:.1em;color:var(--muted)}.notice{padding:13px 15px;border-radius:12px;margin:12px 0;border:1px solid}.notice.ok{background:#102e22;border-color:#286748}.notice.bad{background:#3a1118;border-color:#812432}.tablewrap{overflow:auto;background:var(--panel);border:1px solid #3d2228;border-radius:17px}table{width:100%;border-collapse:collapse;min-width:980px}th,td{text-align:left;padding:13px 12px;border-bottom:1px solid #321b20;vertical-align:top}th{font-size:10px;color:var(--muted);letter-spacing:.08em;text-transform:uppercase;background:#10080a;position:sticky;top:0}.pill{display:inline-block;border:1px solid #4b3035;border-radius:99px;padding:4px 8px;font-size:10px;font-weight:800}.pill.green{color:var(--green);border-color:#245f43;background:#102c21}.pill.red{color:#ff7d86;border-color:#76232e;background:#381017}.pill.gold{color:var(--gold);border-color:#6d5927;background:#2c2511}.pill.blue{color:var(--blue);border-color:#294c70;background:#102339}.device{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:12px}.foot{margin-top:18px;color:var(--muted);font-size:12px;line-height:1.5}@media(max-width:780px){.shell{padding:16px}.top{align-items:flex-start;flex-direction:column}.metrics{grid-template-columns:repeat(2,1fr)}}
</style></head><body><main class="shell">
<header class="top"><div class="brand"><div class="star">★</div><div><div class="kicker">EPC · SEGURANÇA 2.0.7</div><h1>Aparelhos autorizados</h1><div class="muted">Identidade pública separada da credencial de autenticação.</div></div></div><div class="actions"><a class="btn" href="admin_epc.php">Painel EPC</a><a class="btn" href="admin.php">Painel legado</a><a class="btn red" href="logout.php">Sair</a></div></header>
<?php if($message):?><div class="notice ok"><?=epcd_e($message)?></div><?php endif;?>
<?php if($error):?><div class="notice bad"><?=epcd_e($error)?></div><?php endif;?>
<section class="metrics"><div class="card"><div class="label">Aparelhos cadastrados</div><div class="metric"><?=count($rows)?></div></div><div class="card"><div class="label">Segurança 2.0.7 ativa</div><div class="metric"><?=$secure?></div></div><div class="card"><div class="label">Online · 15 min</div><div class="metric"><?=$online?></div></div><div class="card"><div class="label">Revogados</div><div class="metric"><?=$revoked?></div></div></section>
<div class="tablewrap"><table><thead><tr><th>Usuário</th><th>Aparelho</th><th>Estado</th><th>Último uso</th><th>Credencial</th><th>Ação</th></tr></thead><tbody>
<?php foreach($rows as $r): $has=!empty($r['secure_since']);$isRevoked=!empty($r['revoked_at']);$isOnline=epcd_online($r['last_seen_at']??null); ?>
<tr><td><strong><?=epcd_e($r['user_name']?:$r['username'])?></strong><br><span class="muted"><?=epcd_e($r['username'])?> · <?=epcd_e($r['email'])?></span></td>
<td><strong><?=epcd_e($r['device_name']?:'Android')?></strong><br><span class="device"><?=epcd_e(epcd_mask((string)$r['device_id']))?></span></td>
<td><?php if($isRevoked):?><span class="pill red">REVOGADO</span><?php elseif($has):?><span class="pill green">SEGURO</span><?php else:?><span class="pill gold">LEGADO</span><?php endif;?> <?php if($isOnline):?><span class="pill blue">ONLINE</span><?php endif;?></td>
<td><?=epcd_e($r['last_seen_at']?:'—')?><?php if(!empty($r['last_used_at'])):?><br><span class="muted">token: <?=epcd_e($r['last_used_at'])?></span><?php endif;?></td>
<td><?php if($has):?>Ativada <?=epcd_e($r['secure_since'])?><br><span class="muted">refresh até <?=epcd_e($r['refresh_expires_at']?:'—')?></span><?php else:?><span class="muted">Será criada no primeiro acesso pela versão 2.0.7.</span><?php endif;?></td>
<td><?php if($has && !$isRevoked):?><form method="post" onsubmit="return confirm('Revogar o acesso deste aparelho?');"><input type="hidden" name="csrf" value="<?=epcd_e($csrf)?>"><input type="hidden" name="action" value="revoke_device"><input type="hidden" name="device_id" value="<?=epcd_e($r['device_id'])?>"><button class="red" type="submit">REVOGAR</button></form><?php elseif($isRevoked):?><span class="muted">Nova senha no aparelho reautoriza.</span><?php else:?><span class="muted">Aguardando migração.</span><?php endif;?></td></tr>
<?php endforeach;?>
<?php if(!$rows):?><tr><td colspan="6" class="muted">Nenhum aparelho registrado.</td></tr><?php endif;?>
</tbody></table></div>
<p class="foot">A versão 2.0.7 não usa mais o identificador Android como senha. O ID continua existindo somente para reconhecer o aparelho. O segredo aleatório fica protegido no armazenamento privado do app/Android Keystore; o servidor guarda apenas hashes. Tokens de acesso expiram em 30 minutos e o refresh é rotacionado.</p>
</main></body></html>
