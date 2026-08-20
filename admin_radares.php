<?php
declare(strict_types=1);
require __DIR__ . '/api/bootstrap.php';
require_admin();
header('Location: admin.php?section=radars', true, 302);
exit;
