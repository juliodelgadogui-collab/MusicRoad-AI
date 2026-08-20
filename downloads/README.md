# APK de produção

Este diretório não inclui APK de debug.

Depois que o workflow **Signed Android production APK** terminar, baixe o artefato `MusicRoad-1.2.0-signed-release` e publique o arquivo assinado aqui com o nome:

`MusicRoad-1.2.0.apk`

O endpoint `api/app_update.php` só anuncia a atualização quando esse arquivo existe e informa tamanho e SHA-256. O APK 1.2 verifica o SHA-256 antes de abrir o instalador do Android.

Não renomeie nem distribua `app-debug.apk` como versão pública.
