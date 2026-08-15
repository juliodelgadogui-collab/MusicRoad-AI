<?php
require __DIR__ . '/bootstrap.php';
ensure_schema();
ensure_default_users();
set_app_setting('music_storage_policy','device_local_and_drive_links');
set_app_setting('system_version','1.0.0');
json_response([
  'ok'=>true,
  'message'=>'MusicRoad AI 1.0.0 preparado com sucesso.',
  'admin'=>['usuario'=>'adm','senha'=>'1'],
  'cliente_teste'=>['usuario'=>'cliente','senha'=>'1'],
  'login'=>'../login.php'
]);
