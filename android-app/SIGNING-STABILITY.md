# Assinatura estável do APK MusicRoad

O APK distribuído aos usuários deve ser sempre uma compilação `release` assinada pela mesma chave de produção. A chave nunca deve entrar no repositório.

## Segredos do ambiente `production` no GitHub

- `MUSICROAD_KEYSTORE_BASE64`: conteúdo do keystore codificado em Base64, em uma única linha.
- `MUSICROAD_STORE_PASSWORD`: senha do keystore.
- `MUSICROAD_KEY_ALIAS`: alias da chave.
- `MUSICROAD_KEY_PASSWORD`: senha da chave.

Opcionalmente, configure a variável `MUSICROAD_URL` com a URL HTTPS do servidor.

O workflow valida os quatro segredos, verifica se o alias existe, compila `assembleRelease` e executa `apksigner verify`. Se algum segredo estiver ausente, a produção falha de forma explícita; não existe fallback para chave de debug.

## Geração e guarda

Gere a chave uma única vez em um equipamento controlado e mantenha ao menos duas cópias criptografadas, em locais separados. Registre o SHA-256 do certificado. Perder a chave impede atualizar instalações existentes; trocar a chave exige reinstalação do aplicativo.

Para gerar o valor Base64 sem quebras de linha:

```bash
base64 -w 0 musicroad-release.jks
```

No macOS, use `base64 < musicroad-release.jks | tr -d '\n'`.

## Publicação

Execute manualmente o workflow com `release=true` ou crie uma tag `v1.2.0`. Baixe o artefato `MusicRoad-1.2.0-signed-release`, publique o arquivo como `downloads/MusicRoad-1.2.0.apk` no servidor e confirme o SHA-256 antes de disponibilizar a atualização.
