# Estrada Play Comunista 1.0.0

Aplicativo Android nativo separado do EstradaPlay normal.

- applicationId: `com.estradaplay.comunista`
- destino opcional: proteção passiva continua sem rota
- busca de endereço para testes via Nominatim / OpenStreetMap
- rota de teste via servidor público OSRM
- MapLibre + linha vermelha de rota
- copiloto local contextual: segurança é determinística e a personalidade só escolhe a frase
- mesma API MusicRoad/EstradaPlay para conta, música e base rodoviária

A busca Nominatim e o roteador público OSRM são adequados para validação inicial, não para escala comercial. Antes de produção, mover geocodificação/roteamento para infraestrutura própria ou provedor contratado.
