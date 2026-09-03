# Estrada Play Comunista — diagnóstico de autenticação do aparelho

Este pacote existe para diagnosticar o caso em que o APK funciona após usuário/senha, mas volta a pedir senha depois que o processo do aplicativo é fechado.

Arquivos do pacote incremental:

- `api/native_app.php` — fluxo atual de login/device_login/refresh.
- `api/native_auth.php` — credenciais seguras, tokens e schema `native_device_credentials`.
- `admin_auth_diagnostics.php` — painel administrativo seguro de diagnóstico.

O pacote usa o mesmo `config/config.php` e o mesmo banco do servidor já instalado. Não contém banco, credenciais, senhas, tokens, APK, mídia ou arquivos de cache.

## Instalação

1. Faça backup do servidor e do banco por precaução.
2. Extraia o ZIP sobre a raiz atual do Estrada Play.
3. Não substitua `config/config.php` por outro arquivo; ele não vem no pacote.
4. Entre no painel como administrador e abra `/admin_auth_diagnostics.php`.
5. Atualize a página uma vez para criar o baseline.
6. No celular, faça login uma vez, feche totalmente o app e abra novamente.
7. Atualize o diagnóstico após cada etapa.

## O que o diagnóstico verifica

- driver do banco;
- fingerprint não reversível da identidade do banco;
- presença e estabilidade de `native_auth_salt_v207`;
- schema e quantidade de linhas de `native_device_credentials`;
- escrita/leitura de `app_settings`;
- estabilidade entre requisições usando baseline em `logs/native-auth-diagnostic-state.json`;
- credencial por aparelho, revogação, último uso e validade do refresh;
- eventos `native.*` recentes do `audit_logs`.

A página nunca exibe senha, segredo do aparelho, access token, refresh token, hashes completos, DSN ou senha do banco. IDs de aparelhos aparecem mascarados.

## Leitura rápida

- `DB fp mudou`: a aplicação está chegando a outro banco/configuração entre requisições.
- `salt fp mudou`: o salt de autenticação não está persistindo; tokens e segredos deixam de validar.
- `SEM CREDENCIAL`: o login não persistiu `native_device_credentials`.
- `REVOGADA`: o aparelho precisa ser reautorizado.
- `REFRESH EXPIRADO`: o refresh salvo no servidor não é mais válido.
- `NUNCA REVALIDOU`: existe credencial, mas o `device_login` seguro ainda não foi aceito depois da criação.

Nenhum merge é necessário para instalar o pacote incremental gerado pela release da branch `agent/estrada-play-comunista`.
