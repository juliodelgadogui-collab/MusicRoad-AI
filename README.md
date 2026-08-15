# MusicRoad AI 1.0.0

Sistema web/PWA com dois perfis:

- **ADM**: gerencia clientes, ativa/desativa contas, redefine senhas, configura pastas públicas do Google Drive, biblioteca do servidor e radares.
- **Cliente**: usa player, Smart Mix, GPS/rotas e autoriza uma ou mais pastas de música do próprio dispositivo.

## Credenciais iniciais

- ADM: `adm` / `1`
- Cliente teste: `cliente` / `1`

Troque as senhas após instalar.

## Instalação

1. Extraia o ZIP na pasta do domínio/subdomínio.
2. Garanta PHP 8.2+ com PDO SQLite, cURL, OpenSSL e mbstring.
3. Acesse `install/` e clique em **Preparar banco SQLite**.
4. Acesse `login.php`.
5. Use HTTPS, obrigatório para geolocalização e recomendado para PWA.

## Acesso às músicas do dispositivo

O Cliente não adiciona faixa por faixa. Ele toca em **Autorizar pasta de músicas** e escolhe uma pasta. O sistema procura automaticamente arquivos de áudio dentro dela e nas subpastas.

- Em navegadores com File System Access API, o app guarda o vínculo autorizado à pasta e relê os arquivos diretamente.
- Em navegadores sem essa API, o sistema usa o seletor de pasta do navegador e mantém uma cópia local no armazenamento do PWA/IndexedDB para reprodução. Os arquivos não são enviados ao servidor.

## Localização

No primeiro acesso do Cliente, o sistema solicita permissão de localização. O navegador e o sistema operacional continuam sendo os responsáveis por conceder ou negar a permissão.
