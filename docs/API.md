# API MusicRoad AI 1.0.0

As rotas abaixo usam a sessão do sistema. Operações POST exigem `X-CSRF-Token`.

## Biblioteca do Cliente

- `GET api/library.php?action=list` — músicas do servidor/Drive com favoritos e histórico do cliente atual.
- `POST api/library.php?action=favorite` — alterna favorito por cliente.
- `POST api/library.php?action=played` — registra reprodução por cliente.

## Estado de permissões do dispositivo

- `GET api/client_state.php?device_id=...`
- `POST api/client_state.php`

O endpoint guarda apenas o estado informado pelo navegador, quantidade de pastas e faixas. A permissão real é controlada pelo navegador/sistema operacional.

## Google Drive público — ADM

- `POST api/google_drive_public.php` — processa uma pasta, todas as pastas ativas ou links públicos.
- `GET api/drive_stream.php?id=FILE_ID` — streaming autenticado da faixa cadastrada.

## Smart Mix

- `POST api/music_ai.php` — mix de músicas do servidor usando o histórico do cliente. O app também possui Smart Mix local para músicas do dispositivo.

## Viagem

- `GET api/route.php?origin=...&destination=...` — rota OSRM e radares próximos.
- `GET api/radars.php?action=near&lat=...&lon=...&radius=...` — radares próximos.
- `GET api/radars.php?action=admin_summary` — somente ADM.
- `POST api/import_radars.php` — somente ADM.
