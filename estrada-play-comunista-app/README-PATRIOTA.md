# Estrada Play Patriota

Variante de identidade do Estrada Play criada a partir da mesma base Android nativa da edição Comunista.

## Branch

`agent/estrada-play-patriota`

## Identidade

- Nome: Estrada Play Patriota
- Universal: `com.estradaplay.patriota.universal`
- Vertical: `com.estradaplay.patriota.vertical`
- Horizontal: `com.estradaplay.patriota.horizontal`
- Paleta principal: verde `#009C3B`, amarelo `#FFDF00`, azul `#002776`
- Marca: estrada branca, faixa azul e destaque amarelo sobre fundo verde

O namespace Java permanece `com.estradaplay.comunista` por compatibilidade com a base existente. O `applicationId` é diferente, portanto as duas edições podem ser instaladas lado a lado.

## Regra de compatibilidade

A camada `PatriotaBranding` é apenas visual: troca rótulos legados e cores de marca conhecidas. Ela é instalada somente no processo principal do aplicativo.

Os arquivos do rádio/PTT não foram alterados:

- `RoadRadioActivity.java`
- `RoadRadioRtcPolicy.java`
- `RoadRadioService.java`

Comboio, navegação, servidor, biblioteca offline e demais funções continuam usando a mesma implementação da base.

## Build

A variante mantém `versionCode 240` e `versionName 2.3.9`, acompanhando a base funcional usada na criação desta edição.
