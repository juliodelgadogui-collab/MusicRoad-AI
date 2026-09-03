# Estrada Play Server 7.0.1 — Autenticação Persistente

Hotfix do servidor para o caso em que o APK autentica normalmente, mas volta a pedir usuário e senha após o processo ser fechado.

## Arquivos

- `api/native_auth.php`
- `api/native_auth_diagnostic.php`

Não altera tabelas de música, Comboio, Rádio ou Contexto 7.0.

## O que foi corrigido

`native_auth_salt_v207` passa a ser criado com semântica create-once. Em MySQL/MariaDB é usado INSERT com tratamento de chave existente; em SQLite é usado INSERT OR IGNORE. Depois da tentativa, o valor canônico é relido do banco.

Também existe um fallback em `config/native-auth-salt-v207.php`. O arquivo contém somente o salt aleatório da autenticação e é um arquivo PHP que apenas retorna o valor, evitando exposição do conteúdo quando servido por um servidor PHP normal. O banco continua sendo a fonte principal. Se o banco perder temporariamente o setting, o mesmo salt é restaurado a partir do arquivo.

Isso evita a situação em que duas requisições simultâneas de login/rádio/biblioteca geram salts diferentes e tornam a credencial do aparelho inválida na requisição seguinte.

## Diagnóstico

`api/native_auth_diagnostic.php` retorna somente informações sanitizadas:

- driver do banco;
- fingerprint do destino do banco;
- quantidade de credenciais;
- presença e fingerprint do salt;
- fingerprint da instância do servidor;
- existência/revogação da credencial do aparelho;
- se o segredo fornecido confere;
- validade de access/refresh;
- motivo resumido (`ok`, `credential_missing`, `credential_revoked`, `device_secret_mismatch`, etc.).

O endpoint nunca devolve senha, segredo bruto, bearer token, refresh token ou hashes armazenados.

## Procedimento de teste

1. Substitua `api/native_auth.php` e envie `api/native_auth_diagnostic.php`.
2. Abra o APK 2.3.4.
3. Faça login uma vez.
4. Feche completamente o aplicativo.
5. Abra novamente.

A sessão deve ser reconhecida sem pedir usuário e senha.

Se ainda pedir senha, o diagnóstico permite determinar se a tabela `native_device_credentials`, o salt ou o próprio destino de banco estão mudando entre requisições.
