<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';

header('Content-Type: application/json; charset=utf-8');
$data=input_json();
$user=native_require_json_user($data);
$userId=(int)($user['id']??0);
$action=strtolower(trim((string)($_GET['action']??'list')));
$pdo=db();
$mysql=(string)$pdo->getAttribute(PDO::ATTR_DRIVER_NAME)==='mysql';

if($mysql){
    $pdo->exec("CREATE TABLE IF NOT EXISTS company_driver_profiles (
        company_id BIGINT NOT NULL,
        user_id BIGINT NOT NULL,
        phone VARCHAR(40) NOT NULL DEFAULT '',
        cnh_number VARCHAR(40) NOT NULL DEFAULT '',
        cnh_category VARCHAR(12) NOT NULL DEFAULT '',
        cnh_expiry DATE NULL,
        notes VARCHAR(500) NOT NULL DEFAULT '',
        updated_at DATETIME NOT NULL,
        PRIMARY KEY(company_id,user_id),
        KEY idx_company_driver_cnh(company_id,cnh_expiry)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
}else{
    $pdo->exec("CREATE TABLE IF NOT EXISTS company_driver_profiles (
        company_id INTEGER NOT NULL,user_id INTEGER NOT NULL,phone TEXT NOT NULL DEFAULT '',
        cnh_number TEXT NOT NULL DEFAULT '',cnh_category TEXT NOT NULL DEFAULT '',cnh_expiry TEXT,
        notes TEXT NOT NULL DEFAULT '',updated_at TEXT NOT NULL,PRIMARY KEY(company_id,user_id)
    )");
}

$m=$pdo->prepare("SELECT c.id,c.name,m.member_role FROM company_members m JOIN companies c ON c.id=m.company_id WHERE m.user_id=? AND m.status='active' AND c.status='active' ORDER BY m.id ASC LIMIT 1");
$m->execute([$userId]);$company=$m->fetch();
if(!$company)json_response(['ok'=>false,'error'=>'Esta conta não está vinculada a uma empresa.'],403);
$role=strtolower((string)($company['member_role']??''));
if(!in_array($role,['owner','admin','manager'],true))json_response(['ok'=>false,'error'=>'Sua conta não pode administrar dados profissionais.'],403);
$companyId=(int)$company['id'];

function driver_profile_short(string $value,int $max): string {
    $value=trim($value);return function_exists('mb_substr')?mb_substr($value,0,$max):substr($value,0,$max);
}
function driver_profile_date(string $raw): ?string {
    $raw=trim($raw);if($raw==='')return null;
    $d=DateTime::createFromFormat('!Y-m-d',$raw);
    return $d && $d->format('Y-m-d')===$raw ? $raw : null;
}
function driver_profile_status(?string $expiry): array {
    if($expiry===null||$expiry==='')return ['status'=>'SEM DATA','days_remaining'=>null];
    $today=strtotime(date('Y-m-d'))?:time();$due=strtotime($expiry);
    if($due===false)return ['status'=>'SEM DATA','days_remaining'=>null];
    $days=(int)floor(($due-$today)/86400);
    return ['status'=>$days<0?'VENCIDA':($days<=30?'PRÓXIMA':'EM DIA'),'days_remaining'=>$days];
}

if($action==='save'){
    $driverId=(int)($data['user_id']??0);if($driverId<=0)json_response(['ok'=>false,'error'=>'Motorista inválido.'],422);
    $check=$pdo->prepare("SELECT 1 FROM company_members WHERE company_id=? AND user_id=? AND member_role='driver' AND status='active' LIMIT 1");
    $check->execute([$companyId,$driverId]);if(!$check->fetchColumn())json_response(['ok'=>false,'error'=>'Motorista não pertence à equipe ativa.'],404);
    $phone=driver_profile_short((string)($data['phone']??''),40);
    $cnh=preg_replace('/[^A-Za-z0-9.-]/','',(string)($data['cnh_number']??''))??'';$cnh=substr($cnh,0,40);
    $category=strtoupper(preg_replace('/[^A-Z0-9]/i','',(string)($data['cnh_category']??''))??'');$category=substr($category,0,12);
    $expiryRaw=trim((string)($data['cnh_expiry']??''));$expiry=driver_profile_date($expiryRaw);
    if($expiryRaw!==''&&$expiry===null)json_response(['ok'=>false,'error'=>'Use a validade da CNH no formato AAAA-MM-DD.'],422);
    $notes=driver_profile_short((string)($data['notes']??''),500);$now=date('Y-m-d H:i:s');
    if($mysql){
        $q=$pdo->prepare("INSERT INTO company_driver_profiles (company_id,user_id,phone,cnh_number,cnh_category,cnh_expiry,notes,updated_at) VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE phone=VALUES(phone),cnh_number=VALUES(cnh_number),cnh_category=VALUES(cnh_category),cnh_expiry=VALUES(cnh_expiry),notes=VALUES(notes),updated_at=VALUES(updated_at)");
    }else{
        $q=$pdo->prepare("INSERT INTO company_driver_profiles (company_id,user_id,phone,cnh_number,cnh_category,cnh_expiry,notes,updated_at) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(company_id,user_id) DO UPDATE SET phone=excluded.phone,cnh_number=excluded.cnh_number,cnh_category=excluded.cnh_category,cnh_expiry=excluded.cnh_expiry,notes=excluded.notes,updated_at=excluded.updated_at");
    }
    $q->execute([$companyId,$driverId,$phone,$cnh,$category,$expiry,$notes,$now]);
    audit_log('company.driver.profile.save',['company_id'=>$companyId,'driver_id'=>$driverId,'user_id'=>$userId]);
    json_response(['ok'=>true,'user_id'=>$driverId]);
}

if($action==='list'){
    $q=$pdo->prepare("SELECT m.user_id,u.name,u.username,p.phone,p.cnh_number,p.cnh_category,p.cnh_expiry,p.notes,p.updated_at
        FROM company_members m JOIN users u ON u.id=m.user_id
        LEFT JOIN company_driver_profiles p ON p.company_id=m.company_id AND p.user_id=m.user_id
        WHERE m.company_id=? AND m.member_role='driver' AND m.status='active'
        ORDER BY u.name,u.username");
    $q->execute([$companyId]);$rows=$q->fetchAll()?:[];$out=[];$summary=['expired'=>0,'soon'=>0,'missing'=>0];
    foreach($rows as $r){
        $expiry=$r['cnh_expiry']===null?null:(string)$r['cnh_expiry'];$state=driver_profile_status($expiry);
        if($state['status']==='VENCIDA')$summary['expired']++;elseif($state['status']==='PRÓXIMA')$summary['soon']++;elseif($state['status']==='SEM DATA')$summary['missing']++;
        $out[]=['user_id'=>(int)$r['user_id'],'name'=>(string)($r['name']??'Motorista'),'username'=>(string)($r['username']??''),
            'phone'=>(string)($r['phone']??''),'cnh_number'=>(string)($r['cnh_number']??''),'cnh_category'=>(string)($r['cnh_category']??''),
            'cnh_expiry'=>$expiry,'cnh_status'=>$state['status'],'cnh_days_remaining'=>$state['days_remaining'],'notes'=>(string)($r['notes']??''),'updated_at'=>(string)($r['updated_at']??'')];
    }
    json_response(['ok'=>true,'company'=>['id'=>$companyId,'name'=>(string)$company['name']],'summary'=>$summary,'profiles'=>$out]);
}

json_response(['ok'=>false,'error'=>'Ação inválida.'],400);
