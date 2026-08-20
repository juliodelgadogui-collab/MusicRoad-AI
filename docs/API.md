# API MusicRoad 1.2

As rotas privadas exigem uma sessão válida. Gravações exigem o token enviado em `X-CSRF-Token`; operações administrativas também exigem o perfil `admin`. Erros de API são JSON com o status HTTP correspondente.

## Biblioteca

- `GET api/library.php?action=list`: faixas públicas e faixas do usuário, com favoritos e contadores individualizados.
- `POST api/library.php?action=add`: adiciona metadados à biblioteca do usuário.
- `POST api/library.php?action=played`: registra reprodução para o usuário atual.
- `POST api/library.php?action=favorite`: alterna o favorito do usuário atual.

## Navegação e alertas

- `GET api/route.php?origin=LAT,LON&destination=DESTINO`: calcula a rota.
- `POST api/route_radars.php`: recebe `{"coords":[[LON,LAT],...]}` e o modo `local`, `antt`, `regional` ou `osm`.
- `GET api/radars.php?action=near&lat=LAT&lon=LON&radius=5000`: pontos ativos e não expirados próximos.
- `POST api/radars.php?action=save`: cria um relato `PENDENTE`; não cria um radar ativo.
- `GET api/radars.php?action=admin_summary`: resumo exclusivo do administrador.

## Pacotes offline

- `GET api/offline_state.php?kind=map|radars&state_id=ID&uf=UF&state=NOME`: baixa uma base estadual leve de rodovias principais ou o pacote estadual de alertas.
- `GET api/offline_city_roads.php?city_id=IBGE&uf=UF&city=NOME`: detalhe opcional apenas do município selecionado.
- `GET api/offline_state_manifest.php?state_id=ID&uf=UF&state=NOME`: compatibilidade administrativa; a interface 1.2 não usa esse manifesto para baixar todos os municípios.

Os downloads pesados têm limite por sessão, liberam o bloqueio da sessão durante a geração e usam cache técnico temporário no servidor. O pacote de radares possui `data_version`; o cache é invalidado quando a contagem, a data máxima ou o conjunto ativo muda.

## Endereço e mapa local

- `GET api/address_local.php?action=status|search`: consulta o índice CNEFE do município escolhido.
- `POST api/address_local.php?action=prepare`: prepara a base CNEFE; exige CSRF.
- `GET api/map_light.php?action=status|data`: estado ou GeoJSON compacto do mapa municipal.
- `POST api/map_light.php?action=prepare`: prepara o mapa municipal; exige CSRF.

As respostas públicas não incluem caminho de arquivo, URL interna de origem, SQL ou mensagem bruta de exceção. Falhas inesperadas retornam um identificador de incidente para correlação com os logs.

## Importação e sincronização

- `POST api/import_radars.php`: administrador, multipart, até 30 MB e 100 mil registros; aceita CSV/TXT, JSON/GeoJSON e KML.
- `POST api/radar_sync_brazil.php`: administrador; inicia a sincronização oficial.
- `cron/update_radars.php`: execução CLI recomendada para sincronização e expiração.

## Google Drive

- `GET api/google_drive.php?action=status`: estado da integração do usuário.
- `POST api/google_drive.php?action=auth_url`: gera URL OAuth com `state` temporário.
- `GET api/google_drive.php?action=callback&code=...&state=...`: troca o código e guarda tokens criptografados.
- `POST api/google_drive.php?action=disconnect`: remove os tokens do usuário.
- `POST api/google_drive_public.php`: importação administrativa de links públicos.
- `GET api/drive_stream.php?id=FILE_ID`: streaming autenticado.

## Dispositivo e atualização

- `POST api/device_auth.php`: ações `ping`, `login`, `bind`, `status` e `remove`; `bind` e `remove` exigem CSRF.
- `GET api/app_update.php`: versão, `version_code`, tamanho e SHA-256 do APK publicado; `available=false` enquanto o arquivo assinado não existir.

## Pagamento

- `POST api/payment_checkout.php`: cria uma preferência para um plano habilitado.
- `POST api/mercadopago_webhook.php`: valida o webhook, consulta o pagamento e aplica licença de modo idempotente.
