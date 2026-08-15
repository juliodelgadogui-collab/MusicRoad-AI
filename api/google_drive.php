<?php
require __DIR__ . '/bootstrap.php';
require_admin();
json_response([
  'ok'=>true,
  'connected'=>false,
  'configured'=>true,
  'csrf'=>csrf_token(),
  'message'=>'O MusicRoad AI 1.0.0 usa pastas públicas do Google Drive pelo Painel ADM e não exige OAuth.'
]);
