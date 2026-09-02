# Painel Administrativo EPC 1.0

O painel `admin_epc.php` é a central administrativa do Estrada Play Comunista.

## Banco de dados

O painel NÃO cria um segundo banco. Ele usa o mesmo `db()` de `api/bootstrap.php` e, portanto, a mesma instalação MariaDB/MySQL ou SQLite já configurada no servidor.

Dados exibidos e administrados incluem:
- usuários e aparelhos (`users`, `client_device_state`)
- relatos e moderação (`road_reports`)
- Estrada Viva e votos (`road_live_events`, `road_live_votes`)
- trânsito colaborativo (`road_traffic_samples`)
- cache meteorológico (`road_weather_cache`)
- combustível comunitário (`fuel_price_reports`)
- base rodoviária (`road_hazards`, `road_hazard_sync`)
- comboios (`estrada_convoys`, `estrada_convoy_members`)
- auditoria (`audit_logs`)

## Segurança

- Acesso exige sessão com `role=admin`.
- Toda ação de escrita usa CSRF.
- Ações administrativas relevantes são registradas em `audit_logs`.
- Identificadores de aparelho aparecem mascarados na interface.
- O painel não armazena áudio, vídeo ou credenciais adicionais.

## Entrada

Administradores autenticados em `login.php` passam a ser enviados para `admin_epc.php`. O painel legado `admin.php` continua disponível como contingência.

## Atualização em servidor existente

O pacote de release não inclui `config/config.php`, banco, cache, mídia ou APK. Pode ser extraído por cima da instalação atual após backup preventivo. O mesmo banco e as mesmas credenciais permanecem em uso.
