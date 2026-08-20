<?php
http_response_code(410);
header('Content-Type: application/json; charset=utf-8');
echo json_encode(['ok'=>false,'error'=>'Instalador antigo desativado. Use /install/ para preparar o MusicRoad 1.2.'],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);
