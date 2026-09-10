<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';

header('Content-Type: application/json; charset=utf-8');
$data = input_json();
$user = native_require_json_user($data);
$userId = (int)($user['id'] ?? 0);
$device = native_device_token_from_request($data);
if ($device === '') json_response(['ok'=>false,'error'=>'Aparelho não identificado.'],422);

$m = db()->prepare("SELECT c.id,c.name,m.member_role
    FROM company_members m JOIN companies c ON c.id=m.company_id
    WHERE m.user_id=? AND m.status='active' AND c.status='active'
    ORDER BY m.id ASC LIMIT 1");
$m->execute([$userId]);
$company = $m->fetch();
if (!$company) json_response(['ok'=>false,'error'=>'Esta conta não está vinculada a uma empresa.'],403);
$companyId = (int)$company['id'];

$link = db()->prepare('SELECT convoy_code FROM company_convoy_links WHERE company_id=? AND active=1 LIMIT 1');
$link->execute([$companyId]);
$code = strtoupper((string)($link->fetchColumn() ?: ''));
if ($code === '') json_response(['ok'=>false,'error'=>'A empresa não possui um comboio ativo.'],404);

$leader = db()->prepare('SELECT 1 FROM company_convoy_roster WHERE company_id=? AND convoy_code=? AND user_id=? AND active=1 AND is_leader=1 LIMIT 1');
$leader->execute([$companyId,$code,$userId]);
if (!$leader->fetchColumn()) json_response(['ok'=>false,'error'=>'Este motorista não é o líder definido para a operação.'],403);

$convoy = db()->prepare('SELECT id FROM estrada_convoys WHERE code=? AND expires_at>NOW() LIMIT 1');
$convoy->execute([$code]);
$convoyId = (int)($convoy->fetchColumn() ?: 0);
if ($convoyId <= 0) json_response(['ok'=>false,'error'=>'O comboio da empresa expirou ou não existe mais.'],404);

$u = db()->prepare('UPDATE estrada_convoys SET leader_device_token=?,owner_user_id=?,expires_at=DATE_ADD(NOW(),INTERVAL 24 HOUR) WHERE id=?');
$u->execute([$device,$userId,$convoyId]);
audit_log('company.convoy.claim_leader',['company_id'=>$companyId,'code'=>$code,'user_id'=>$userId]);
json_response(['ok'=>true,'code'=>$code,'leader_user_id'=>$userId]);
