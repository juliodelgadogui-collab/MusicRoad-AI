# Estrada Play Comunista 4.0 — Produção

A 4.0 separa serviços críticos do motorista de fallbacks públicos. Em produção, configure primeiro o VPS e só depois gere o APK `release` definitivo.

## 1. Banco

No servidor instalado, execute pela CLI:

```bash
php server/migrate_production.php
```

O aplicativo continua compatível com instalações antigas, mas o deploy de produção deve executar a migração explicitamente.

## 2. Roteamento próprio

Execute `server/install_osrm_brazil.sh` em um VPS com espaço/RAM suficientes. Depois grave em `app_settings`:

- `routing_osrm_url = http://127.0.0.1:5000`
- `routing_public_fallback = 0`

Mapbox pode continuar configurado como fallback contratado. O APK de produção não usa o OSRM público diretamente.

## 3. PTT / TURN

Execute `server/install_coturn.sh` com `TURN_REALM`, `TURN_USER` e `TURN_PASSWORD`. Depois configure:

- `radio_turn_url`
- `radio_turn_username`
- `radio_turn_password`

O rádio aceita BR e todas as UFs. Sem TURN, o app informa que o relay não está configurado; STUN sozinho não é considerado garantia de conectividade em rede móvel/CGNAT.

## 4. Mapa próprio

O app usa mapa Dark como fallback e aceita um estilo próprio pelo servidor. Para hospedar tiles, coloque um tileset licenciado/próprio no VPS e execute `server/install_tileserver.sh`. Publique o TileServer atrás de HTTPS e configure:

- `map_style_url = https://seu-dominio/.../style.json`

O APK busca essa configuração e guarda em cache. Se não estiver configurada, usa OpenFreeMap Dark.

## 5. Clima

O telefone consulta primeiro `api/weather_batch.php`; o servidor centraliza/cacheia Open-Meteo por 10 minutos. Consulta direta do Android permanece apenas como fallback de resiliência.

## 6. Telemetria

Crashes e ANRs são armazenados primeiro no aparelho e enviados autenticados para `api/client_telemetry.php`. Não são enviados GPS, áudio ou conteúdo do usuário nesse relatório.

## 7. Assinatura

Nunca publique o build `candidate`. Gere uma keystore permanente fora do repositório e configure os segredos/variáveis:

- `ESTRADAPLAY_RELEASE_KEYSTORE`
- `ESTRADAPLAY_RELEASE_STORE_PASSWORD`
- `ESTRADAPLAY_RELEASE_KEY_ALIAS`
- `ESTRADAPLAY_RELEASE_KEY_PASSWORD`

O Gradle bloqueia `Release` se a chave permanente não estiver disponível. Faça backup seguro da keystore: todas as atualizações futuras dependem dela.

## 8. Preflight

Após instalar os serviços, entre como administrador e consulte `api/production_health.php`. O endpoint separa banco, roteamento privado, TURN, base nacional, telemetria, clima e HTTPS e retorna um `production_score` de 0 a 10.
