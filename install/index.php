<?php
$storage=__DIR__.'/../storage';
if(!is_dir($storage)) @mkdir($storage,0775,true);
$checks=[
 'PHP 8.2+'=>version_compare(PHP_VERSION,'8.2.0','>='),
 'PDO'=>extension_loaded('pdo'),
 'PDO SQLite'=>extension_loaded('pdo_sqlite'),
 'cURL'=>extension_loaded('curl'),
 'OpenSSL'=>extension_loaded('openssl'),
 'mbstring'=>extension_loaded('mbstring'),
 'Storage gravável'=>is_writable($storage),
 'HTTPS'=>(!empty($_SERVER['HTTPS'])&&$_SERVER['HTTPS']!=='off')||in_array($_SERVER['HTTP_HOST']??'',['localhost','127.0.0.1'],true),
];
?>
<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Instalar MusicRoad AI</title><link rel="stylesheet" href="../assets/css/app.css?v=1.0.0"></head>
<body><main class="admin-main"><div class="toolbar"><div><p class="eyebrow">MusicRoad AI v1.0.0</p><h1>Instalador</h1></div><a class="button secondary" href="../login.php">Login</a></div>
<div class="grid admin-summary"><?php foreach($checks as $name=>$ok):?><article class="panel"><h2><?=htmlspecialchars($name)?></h2><p class="<?= $ok?'success-text':'error-text' ?>"><?=$ok?'OK':'Ajustar'?></p></article><?php endforeach;?></div>
<section class="panel"><h2>Perfis incluídos</h2><p><strong>ADM:</strong> usuário <code>adm</code> / senha <code>1</code></p><p><strong>Cliente teste:</strong> usuário <code>cliente</code> / senha <code>1</code></p><p class="muted">No primeiro acesso do Cliente, o sistema abre a Central de Acessos e solicita autorização para uma pasta de músicas e para a localização.</p><a class="button" href="../api/install_schema.php">Preparar banco SQLite</a></section>
<section class="panel"><h2>Importante para o celular</h2><p>Use HTTPS. Por segurança, Android/iPhone/navegadores não permitem que um site leia todas as músicas ou a localização sem o usuário tocar em “Permitir”. O MusicRoad solicita esses acessos no primeiro login do Cliente.</p></section>
</main></body></html>
