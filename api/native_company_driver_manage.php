<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';

header('Content-Type: application/json; charset=utf-8');
$data=input_json();
$user=native_require_json_user($data);
$userId=(int)($user['id']??0);
$action=strtolower(trim((string)($_GET['action']??'')));

$m=db()->prepare("SELECT c.id,c.name,m.member_role FROM company_members m JOIN companies c ON c.id=m.company_id WHERE m.user_id=? AND m.status='active' AND c.status='active' ORDER BY m.id ASC LIMIT 1");
$m->execute([$userId]);$company=$m->fetch();
if(!$company)json_response(['ok'=>false,'error'=>'Esta conta não está vinculada a uma empresa.'],403);
$role=strtolower((string)($company['member_role']??''));
if(!in_array($role,['owner','admin','manager'],true))json_response(['ok'=>false,'error'=>'Sua conta não pode administrar motoristas.'],403);
$companyId=(int)$company['id'];
$driverId=(int)($data['user_id']??0);
if($driverId<=0)json_response(['ok'=>false,'error'=>'Motorista inválido.'],422);

$driver=db()->prepare("SELECT m.id,m.member_role,u.name,u.username FROM company_members m JOIN users u ON u.id=m.user_id WHERE m.company_id=? AND m.user_id=? AND m.status='active' LIMIT 1");
$driver->execute([$companyId,$driverId]);$driverRow=$driver->fetch();
if(!$driverRow)json_response(['ok'=>false,'error'=>'Motorista não pertence a esta empresa.'],404);
if(in_array(strtolower((string)$driverRow['member_role']),['owner','admin','manager'],true))json_response(['ok'=>false,'error'=>'Uma conta de gestão não pode ser removida por esta tela.'],403);

if($action==='assign'){
    $vehicleId=(int)($data['vehicle_id']??0);
    if($vehicleId<=0)json_response(['ok'=>false,'error'=>'Veículo inválido.'],422);
    $v=db()->prepare("SELECT id,plate,nickname FROM company_vehicles WHERE company_id=? AND id=? AND status='active' LIMIT 1");$v->execute([$companyId,$vehicleId]);if(!$v->fetch())json_response(['ok'=>false,'error'=>'Veículo não pertence a esta empresa.'],404);
    $now=date('Y-m-d H:i:s');db()->beginTransaction();
    try{
        // A driver can have only one active vehicle, and a vehicle can have only one active responsible driver.
        $offDriver=db()->prepare('UPDATE company_driver_vehicle SET active=0,ended_at=? WHERE company_id=? AND user_id=? AND active=1');$offDriver->execute([$now,$companyId,$driverId]);
        $offVehicle=db()->prepare('UPDATE company_driver_vehicle SET active=0,ended_at=? WHERE company_id=? AND vehicle_id=? AND active=1');$offVehicle->execute([$now,$companyId,$vehicleId]);
        $i=db()->prepare('INSERT INTO company_driver_vehicle (company_id,user_id,vehicle_id,active,assigned_at,ended_at) VALUES (?,?,?,1,?,NULL)');$i->execute([$companyId,$driverId,$vehicleId,$now]);
        // Old live presence is no longer authoritative after responsibility changes.
        $p=db()->prepare('DELETE FROM company_presence WHERE company_id=? AND (user_id=? OR vehicle_id=?)');$p->execute([$companyId,$driverId,$vehicleId]);
        db()->commit();
    }catch(Throwable $e){if(db()->inTransaction())db()->rollBack();throw $e;}
    audit_log('company.driver.assign.unique',['company_id'=>$companyId,'driver_id'=>$driverId,'vehicle_id'=>$vehicleId,'user_id'=>$userId]);
    json_response(['ok'=>true,'user_id'=>$driverId,'vehicle_id'=>$vehicleId]);
}

if($action==='unassign'){
    $now=date('Y-m-d H:i:s');
    $q=db()->prepare('SELECT vehicle_id FROM company_driver_vehicle WHERE company_id=? AND user_id=? AND active=1 ORDER BY id DESC LIMIT 1');$q->execute([$companyId,$driverId]);$vehicleId=(int)($q->fetchColumn()?:0);
    $u=db()->prepare('UPDATE company_driver_vehicle SET active=0,ended_at=? WHERE company_id=? AND user_id=? AND active=1');$u->execute([$now,$companyId,$driverId]);
    $j=db()->prepare("UPDATE company_journeys SET ended_at=?,status='closed' WHERE company_id=? AND user_id=? AND status='active'");$j->execute([$now,$companyId,$driverId]);
    $p=db()->prepare('DELETE FROM company_presence WHERE company_id=? AND user_id=?');$p->execute([$companyId,$driverId]);
    audit_log('company.driver.unassign',['company_id'=>$companyId,'driver_id'=>$driverId,'vehicle_id'=>$vehicleId,'user_id'=>$userId]);
    json_response(['ok'=>true]);
}

if($action==='remove'){
    $now=date('Y-m-d H:i:s');db()->beginTransaction();
    try{
        $a=db()->prepare('UPDATE company_driver_vehicle SET active=0,ended_at=? WHERE company_id=? AND user_id=? AND active=1');$a->execute([$now,$companyId,$driverId]);
        $j=db()->prepare("UPDATE company_journeys SET ended_at=?,status='closed' WHERE company_id=? AND user_id=? AND status='active'");$j->execute([$now,$companyId,$driverId]);
        $p=db()->prepare('DELETE FROM company_presence WHERE company_id=? AND user_id=?');$p->execute([$companyId,$driverId]);
        try{$r=db()->prepare('UPDATE company_convoy_roster SET active=0,is_leader=0,updated_at=? WHERE company_id=? AND user_id=? AND active=1');$r->execute([$now,$companyId,$driverId]);}catch(Throwable $ignored){}
        $mm=db()->prepare("UPDATE company_members SET status='inactive',updated_at=? WHERE company_id=? AND user_id=? AND member_role='driver'");$mm->execute([$now,$companyId,$driverId]);
        db()->commit();
    }catch(Throwable $e){if(db()->inTransaction())db()->rollBack();throw $e;}
    audit_log('company.driver.remove',['company_id'=>$companyId,'driver_id'=>$driverId,'user_id'=>$userId]);
    json_response(['ok'=>true]);
}

json_response(['ok'=>false,'error'=>'Ação inválida.'],400);
