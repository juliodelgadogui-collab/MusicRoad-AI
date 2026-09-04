# Estrada Play Server 3.0 — Alpha 1

Esta pasta inicia o novo servidor em paralelo ao servidor atual. Nenhum arquivo da raiz antiga é substituído para testar esta etapa.

## Versão web do aplicativo

Acesse `/server-v3/`. O servidor direciona para o login web e, depois da autenticação, abre a versão do Estrada Play no navegador.

Alpha 1 já inclui:

- login pela mesma conta do Estrada Play;
- GPS do navegador em HTTPS;
- mapa interativo;
- busca de destino;
- cálculo de rota Mapbox com fallback OSRM;
- contexto da rota e clima;
- radares próximos respeitando o modo OFFICIAL;
- biblioteca de músicas e reprodução pelo navegador;
- PWA instalável, com cache apenas dos arquivos estáticos da interface.

## Segurança e compatibilidade

O Android continua nativo e não usa WebView. Esta versão web é um cliente separado.

Nesta primeira etapa, `server-v3/bootstrap.php` reutiliza `config/config.php`, sessão, banco e alguns módulos de leitura do servidor atual como ponte de compatibilidade. Isso permite testar o novo servidor sem migrar usuários ou quebrar o APK. Os módulos v3 serão desacoplados progressivamente para migrations, quarentena e API v8 próprias.

A aplicação web não grava trânsito, combustível ou alertas comunitários na base operacional. O endpoint de contexto usa a política de dados já configurada no servidor e a consulta v3 de radares aplica a filtragem oficial quando `road_data_mode=OFFICIAL`.

## Instalação paralela

Extraia `server-v3/` na raiz atual, mantendo o servidor antigo intacto. Depois abra:

`https://SEU-DOMINIO/server-v3/`

O navegador precisa de HTTPS para liberar geolocalização. O PWA não armazena páginas autenticadas nem respostas das APIs no cache.

## Próximos módulos do Server 3.0

- migrations versionadas próprias;
- cadastro dinâmico de fontes oficiais;
- quarentena separada da base operacional;
- validação de rodovia por coordenadas;
- Comboio na versão web;
- histórico/telemetria no navegador;
- painel de saúde e backup do Server 3.0;
- remoção gradual das dependências do servidor legado.
