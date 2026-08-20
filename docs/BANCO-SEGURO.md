# MusicRoad 1.2.0 SecureDB

O banco principal usa **MariaDB/MySQL com InnoDB**. SQLite não é usado para contas, pagamentos, licenças, tokens, auditoria, radares ou índice de endereços.

## Recomendação de produção

1. Crie um banco exclusivo, por exemplo `musicroad`.
2. Crie um usuário exclusivo, por exemplo `musicroad_app`.
3. Autorize esse usuário somente no banco MusicRoad. Nunca use `root` na aplicação.
4. Prefira o banco em `localhost`/rede privada. Se for remoto, ative TLS e informe a CA no instalador.
5. Use uma senha aleatória forte para o banco e pelo menos 10 caracteres para o administrador.
6. Mantenha `config/config.php` fora de acesso HTTP e com permissão 0640. O pacote inclui regras Apache de bloqueio.
7. Faça backup diário do MariaDB/MySQL e teste restauração periodicamente.

## Segurança implementada

- PDO MySQL com prepared statements nativos (`ATTR_EMULATE_PREPARES=false`).
- Charset `utf8mb4` e tabelas InnoDB com chaves estrangeiras.
- Conta do banco separada do usuário do sistema/web.
- Cookies `HttpOnly`, `SameSite=Lax`, `Secure` quando HTTPS está ativo e modo de sessão estrito.
- CSRF nas alterações autenticadas.
- Senhas armazenadas por `password_hash()`; nunca em texto puro.
- Configuração, schema, storage e logs bloqueados por `.htaccess` em Apache.
- Credenciais do Mercado Pago e Google ficam no banco/config do servidor, nunca no APK.

> Observação: trocar SQLite por MariaDB melhora isolamento, controle de acesso, concorrência, backups e operação. Nenhum banco sozinho protege uma aplicação se o servidor PHP for comprometido; mantenha PHP, sistema operacional e hospedagem atualizados.
