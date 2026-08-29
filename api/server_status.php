<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/native_auth.php';
require_once __DIR__ . '/server_intelligent.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

$data = input_json();
$user = native_require_json_user($data);
$status = intelligent_server_status_snapshot();
$status['ok'] = true;
$status['account'] = native_account_payload($user);
json_response($status);
