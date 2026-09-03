# Estrada Play Comunista Universal 2.1.0 — Base Consolidada

A 2.1.0 encerra a sequência técnica 2.0.8/2.0.9 com uma base direta e testável antes de novas funções de comboio, PTT e telemetria.

## Fonte definitiva

A fonte Android é `estrada-play-comunista-app/` na branch `agent/estrada-play-comunista`.

A partir da 2.1.0 o workflow corrente não deve gerar `RoadSafetyService.java`, `RoadMapActivity.java` ou `build.gradle` através de patches. O APK deve ser compilado diretamente do conteúdo versionado em `estrada-play-comunista-app/`.

## RoadSafetyService

`RoadSafetyService` continua sendo o Android `Service` responsável pelo ciclo de vida, localização, broadcasts, voz, integração com stores e coordenação de proteção. As políticas independentes de Android foram separadas:

- `RoadHazardMatcher`: geometria, faixa lateral, sentido e alcance de cada tipo de perigo.
- `RoadHazardSelector`: escolha do perigo atual e do próximo perigo.
- `RoadAlertCooldown`: deduplicação e janela de repetição dos alertas.
- `RoadLimitPolicy`: estado de limite da via e decisões de aviso de excesso.
- `RoadSafetyFormat`: formatação de distância para tela e voz.

O objetivo é impedir que regras de domínio voltem a ficar misturadas com ciclo de vida Android e efeitos colaterais.

## Testes obrigatórios

O módulo usa JUnit 4.13.2 e o CI executa `testUniversalDebugUnitTest` antes da montagem do APK. A release é bloqueada se os testes falharem.

Cobertura inicial automatizada:

- perigo alinhado à frente;
- perigo atrás do veículo;
- distância lateral excessiva;
- aumento da janela de segurança com chuva;
- incompatibilidade de sentido;
- prioridade de tipos de perigo;
- seleção do alerta atual e do próximo;
- cooldown e reativação após TTL;
- validação e anúncio de limite da via;
- aviso de excesso uma única vez até rearmar;
- formatação de distâncias.

## Contratos preservados

A consolidação não remove as funções já entregues:

- segurança de dispositivo da 2.0.7;
- Navegação Real da 2.0.8;
- Contexto Inteligente/Server 7.0 da 2.0.9;
- trânsito colaborativo continua opt-in e desligado por padrão;
- proteção local/offline continua funcionando quando o servidor está indisponível;
- aplicativo nativo, sem WebView.

## Política de patches legados

Os geradores ativos usados para produzir 2.0.8 e 2.0.9 deixam de fazer parte do caminho de build corrente. O workflow 2.1.0 final compila o fonte direto. Scripts históricos de versões antigas, quando mantidos apenas para auditoria/reprodutibilidade, não podem ser chamados pelo workflow atual nem modificar a fonte durante a build.
