# Estrada Play Comunista 1.5.3 — Universal

- Um único APK para portrait + landscape.
- Flavor: `universal`.
- Package: `com.estradaplay.comunista.universal`.
- Orientation: `fullSensor`.
- O cockpit `RoadMapActivity` escolhe `buildPortraitUi` ou `buildLandscapeUi` pela dimensão atual da tela.
- Ao girar, `onConfigurationChanged` reconstrói somente a interface responsiva; serviços de estrada e voz continuam preservados.
- A versão mantém as correções de estabilidade 1.5.2: serviços não sticky, encerramento com a tarefa, voz embarcada única por padrão, contagem de perigos em cache e I/O pesado fora da UI.
- Vertical e Horizontal antigos permanecem disponíveis como builds separados; a Universal usa package próprio para coexistir durante testes.
