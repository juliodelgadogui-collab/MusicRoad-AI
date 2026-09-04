<?php
declare(strict_types=1);
require_once __DIR__ . '/bootstrap.php';
header('Location: ' . (current_user() ? 'public/app/' : 'public/login.php'));
exit;
