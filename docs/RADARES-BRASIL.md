# Fontes e ciclo de vida dos alertas viários

## Fontes

- Banco local e importações administrativas.
- ANTT e fontes regionais disponíveis nos importadores.
- DER-ES, com filtro de registros desativados, retirados, remanejados ou substituídos.
- OpenStreetMap para fiscalização, semáforos, videomonitoramento, quebra-molas e limites conhecidos.
- Relatos da comunidade, sempre pendentes até revisão.

## Precisão da rota

A geometria é dividida em corredores menores. Os candidatos são projetados nos segmentos da rota e recebem distância transversal e posição longitudinal. Limiares variam por tipo; direção textual confiável é comparada com o rumo da rota. OSM e as fontes oficiais são consultados de forma independente, para uma falha não apagar as demais.

## Deduplicação

O identificador externo é a primeira chave. Quando ele não existe, a deduplicação usa grade espacial, tipo compatível, rodovia/km e distância real. Sincronizar novamente a mesma fonte não aumenta artificialmente a confiança.

## Ciclo de vida

- Uma importação ANTT válida é transacional: o conjunto anterior só é substituído depois que o novo passa pelas validações.
- Uma atualização DER-ES completa desativa itens que desapareceram da fonte; respostas parciais não provocam desativação em massa.
- Alertas temporários aprovados recebem expiração por tipo.
- O cron expira relatos antigos, alertas temporários, pontos OSM muito antigos e tentativas de autenticação obsoletas.

Agende diariamente:

```bash
php cron/update_radars.php
```

Revise o resumo produzido e os registros de `logs/` antes de investigar quedas abruptas de cobertura.
