# MusicRoad 1.2 — SecureDB + Android Offline Core

MusicRoad reúne navegação, alertas viários, mapas offline por estado e reprodução de música. O servidor usa MariaDB/MySQL; o aplicativo Android contém uma interface local e um serviço de navegação que continua ativo sem internet.

## Principais mudanças da 1.2

- APIs de radares, biblioteca e integrações protegidas por sessão, perfil e CSRF.
- Relatos comunitários entram em moderação e não viram alertas ativos automaticamente.
- Consulta de rota com corredores menores, direção de tráfego, expiração, deduplicação espacial e fontes independentes.
- Sincronização ANTT/DER transacional, com desativação controlada de registros removidos.
- O offline usa uma base estadual leve mais o detalhe opcional do município atual, evitando o antigo leque de centenas de downloads; os pacotes de radares invalidam o cache quando o banco muda.
- Offline Core retoma uma viagem ativa, mapa, radares, limites e manobras; o serviço Android mantém alertas, voz e vibração em segundo plano.
- APK `release` exige chave de produção. O atualizador valida origem e SHA-256 antes de abrir o instalador.
- PWA não guarda uma página autenticada globalmente como fallback offline.
- Erros inesperados das APIs recebem um identificador de incidente sem expor SQL, caminhos locais ou respostas integrais de provedores.
- Instalador assistido em três etapas solicita o banco MariaDB/MySQL, o Mapbox opcional e a conta administrativa, preservando somente campos não secretos quando há erro.
- Mapa-base Mapbox GL JS opcional, com token público cifrado no banco e fallback automático para OpenStreetMap e mapas locais/offline.
- Nova identidade premium em obsidiana, titânio, verde elétrico e violeta nas interfaces vertical, DriveOS, configuração e Offline Core Android.

## Instalação nova

1. Crie um banco MariaDB 10.5+ ou MySQL 8.0+ vazio e um usuário exclusivo para ele.
2. Envie o conteúdo deste projeto para uma pasta servida somente por HTTPS.
3. Dê permissão de escrita ao processo do PHP apenas em `config/`, `storage/` e `logs/`.
4. Acesse `https://SEU-DOMINIO/SUA-PASTA/install/`. Leia `storage/install.token` pelo gerenciador de arquivos/SSH e use-o para autorizar o navegador.
5. Informe banco, administrador e, se desejar, um token público Mapbox `pk.`. O token do instalador é apagado e `storage/installed.lock` bloqueia novas instalações.
6. Configure os planos e as integrações necessárias no painel administrativo.
7. Configure o cron `cron/update_radars.php` para execução diária.
8. Compile o APK assinado pelo workflow, publique-o como `downloads/MusicRoad-1.2.0.apk` e confira o SHA-256 retornado por `api/app_update.php`.

O repositório não distribui APK de debug. Consulte [android-app/SIGNING-STABILITY.md](android-app/SIGNING-STABILITY.md).

## Requisitos do servidor

- PHP 8.1+ com PDO MySQL, cURL, OpenSSL, mbstring, ZipArchive e Zlib/GZIP.
- MariaDB 10.5+ ou MySQL 8.0+ com tabelas InnoDB.
- HTTPS e tarefas cron.
- Apache com `mod_rewrite`/`mod_headers`, ou regras equivalentes no servidor web escolhido.

## Atualização de uma instalação anterior

1. Faça backup do banco, `config/config.php`, `storage/` e do APK de produção vigente.
2. Envie os arquivos da 1.2 mantendo o `config/config.php` existente.
3. Abra uma página autenticada. A migração idempotente atualiza o esquema e só grava a versão depois que todas as etapas terminam.
4. Execute `cron/update_radars.php` manualmente uma vez e verifique `logs/`.
5. Publique o APK 1.2 assinado com a mesma chave usada na versão instalada.

Instalações SQLite antigas não são apagadas. A migração opcional está em `tools/migrate_sqlite_to_mysql.php`; execute-a apenas sobre uma cópia e com o banco MySQL já preparado.

## Segurança operacional

- Use uma chave aleatória e exclusiva em `config/config.php`; tokens de integração são criptografados e a aplicação falha de forma fechada se a criptografia não estiver disponível.
- Não exponha `config/`, `database/`, `storage/`, `logs/`, `cron/` ou arquivos de backup pela web.
- Cadastre o segredo do webhook do Mercado Pago e valide o endpoint antes de ativar cobrança real.
- Conserve a chave Android fora do servidor e do repositório, com cópias criptografadas.
- Revise periodicamente os relatos pendentes no painel e os resultados do cron.
- Para Mapbox, crie um token público exclusivo, restrinja-o às URLs de produção e nunca use um token secreto `sk.` no app.

## Mapbox e franquias

A interface atual usa **Mapbox GL JS**, inclusive dentro da WebView Android. Portanto, aplica-se a franquia publicada de **50 mil carregamentos de mapa por mês**. A referência de **25 mil usuários ativos/mês** pertence aos SDKs móveis nativos para Android/iOS. Directions, Search e Navigation têm medidores próprios. Valores podem mudar; confirme a página oficial antes da produção. Consulte [docs/MAPBOX.md](docs/MAPBOX.md).

## Versões

- Servidor e interface: **1.2.0**
- Esquema: **1.2.0**
- APK Android: **1.2.0 / versionCode 26**
- DriveOS: **1.2.0**

Relatório da revisão: [ANALISE-MELHORIAS-1.2.md](ANALISE-MELHORIAS-1.2.md).

Mais detalhes: `docs/BANCO-SEGURO.md`, `docs/MAPBOX.md`, `docs/OFFLINE-DISPOSITIVO.md` e `docs/RADARES-BRASIL.md`.
