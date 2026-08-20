# Mapbox no MusicRoad 1.2

O Mapbox é opcional. Sem token, com token inválido, sem internet ou se o carregamento falhar, o MusicRoad mantém o OpenStreetMap e os mapas locais/offline já existentes.

## Qual limite se aplica

- **Maps SDKs para Android/iOS:** franquia de 25.000 usuários ativos mensais (MAU). Essa regra se aplica a uma tela de mapa implementada com o SDK móvel nativo.
- **Mapbox GL JS:** franquia de 50.000 carregamentos de mapa por mês. É a regra aplicável à integração atual, pois a interface do MusicRoad é web e também roda dentro de uma WebView no Android.
- **Directions, Search e Navigation:** têm medidores e preços próprios. O Mapbox do MusicRoad é usado somente como mapa-base visual; a rota resiliente existente não é convertida automaticamente em Mapbox Navigation.

Um carregamento é contabilizado quando uma nova instância do mapa GL é inicializada. Consulte sempre a página oficial de preços antes de publicar em grande escala, pois as franquias e tarifas podem mudar.

## Configuração segura

1. Crie um token público dedicado no painel do Mapbox.
2. Use apenas um token iniciado por `pk.`. O instalador e o painel rejeitam tokens secretos `sk.`.
3. Dê ao token somente os escopos públicos necessários para estilos e fontes.
4. Restrinja o token às URLs de produção do MusicRoad.
5. No painel administrativo, abra **Sistema e armazenamento**, ative o Mapbox, cole o token e escolha o estilo.
6. Monitore o uso e configure alertas no painel do Mapbox.

O token público precisa chegar ao navegador para renderizar o mapa. Mesmo assim, o MusicRoad o cifra em repouso no banco e nunca inclui um token secreto no pacote.

Referências oficiais:

- https://www.mapbox.com/pricing
- https://docs.mapbox.com/help/dive-deeper/access-tokens/
- https://docs.mapbox.com/help/dive-deeper/how-to-use-mapbox-securely/
- https://docs.mapbox.com/mapbox-gl-js/guides/
