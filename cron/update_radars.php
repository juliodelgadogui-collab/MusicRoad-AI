<?php
require __DIR__ . '/../api/bootstrap.php';
require __DIR__ . '/../importers/RadarImporter.php';

// Cron preparado para cPanel:
// /usr/local/bin/php /home/usuario/public_html/musicroad-ai/cron/update_radars.php
// Integrações oficiais devem preencher $rows com dados DNIT/DER/OSM normalizados.
$rows = [];
$stats = (new RadarImporter(db()))->importRows('CRON_PLACEHOLDER', $rows);
echo json_encode(['ok' => true, 'message' => 'Cron executado. Configure URLs oficiais nas próximas integrações.', 'stats' => $stats], JSON_UNESCAPED_UNICODE) . PHP_EOL;
