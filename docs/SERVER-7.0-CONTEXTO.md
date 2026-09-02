# EstradaPlay Server 7.0 — Contexto Inteligente

O Server 7.0 transforma o backend em uma central de contexto da viagem, mantendo a proposta de armazenamento leve (metadata-only).

## APIs novas

- `POST /api/route_context.php`: recebe posição atual e, opcionalmente, pontos da rota. Devolve radares/perigos, alertas comunitários, trânsito, clima ao longo do caminho e postos ranqueados por custo real de abastecimento + desvio.
- `POST /api/traffic_live.php`: `action=sample` envia uma amostra apenas com `traffic_opt_in=true`; `action=nearby` consulta o trânsito agregado.
- `POST /api/weather_route.php`: previsão amostrada ao longo da rota, com cache de 15 minutos para reduzir chamadas externas.

## Alertas comunitários

`/api/road_live.php` continua compatível com `report`, `confirm` e `nearby` e passa a aceitar `dismiss`. Cada alerta recebe `confidence.score`, `confidence.label`, confirmações, descartes e decaimento por idade.

## Privacidade do trânsito

O recurso é opt-in. O token bruto do aparelho não é salvo na tabela de trânsito: o servidor grava somente SHA-256 com salt privado local. Cada aparelho mantém no máximo uma amostra ativa por célula/direção e as amostras expiram em 2 horas.

## Clima

O servidor usa Open-Meteo como fonte de previsão, guarda somente a resposta resumida por 15 minutos e classifica chuva forte, tempestade, vento e baixa visibilidade para a rota.

## Combustível

O ranking considera preço informado, idade do relato, distância aproximada de desvio, consumo do veículo e quantidade estimada a abastecer. O app pode enviar `consumption_km_l` e `liters_to_buy`.

## Compatibilidade e armazenamento

- MariaDB/MySQL e SQLite para as tabelas novas.
- Sem armazenamento de áudio ou vídeo.
- Housekeeping automático de clima, trânsito e eventos expirados.
- Estados de pacote offline declarados: SP, RJ, MG e ES.
- Atualização do servidor preserva `config/config.php` e o banco porque eles não são incluídos no ZIP de release.
