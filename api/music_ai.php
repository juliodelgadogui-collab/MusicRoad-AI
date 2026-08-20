<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
require_login();
if ($_SERVER['REQUEST_METHOD'] === 'POST') require_csrf();
json_response([
  'ok'=>false,
  'error'=>'Smart Mix legado foi removido porque a implementação anterior não era confiável. A biblioteca, favoritos e reprodução continuam funcionando normalmente.'
],410);
