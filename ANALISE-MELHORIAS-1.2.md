# Análise técnica e melhorias — MusicRoad 1.2.0

## Resultado executivo

O projeto foi revisado como uma solução integrada: servidor PHP/MariaDB, PWA, DriveOS horizontal, aplicativo Android, mapas offline, índice local de endereços, radares, pagamentos, instalação e entrega. A versão resultante é a **1.2.0**, com esquema **1.2.0** e Android **versionCode 26**.

Os problemas de maior impacto foram corrigidos antes da entrega. A aplicação agora bloqueia instalação não autorizada, reduz exposição de dados internos, modera relatos comunitários, limita operações caras, evita downloads municipais em massa, preserva o índice CNEFE após limpeza de arquivos brutos e exige um APK de produção assinado e verificável.

Uma segunda rodada ampliou a entrega com instalador assistido, integração Mapbox opcional e uma identidade visual premium consistente na PWA, no DriveOS e nas superfícies nativas do APK. A arquitetura de mapa existente foi preservada para não perder a operação offline.

## Escopo revisado

- autenticação, sessão, CSRF, perfis e dispositivos registrados;
- instalador, configuração, migração de esquema e MariaDB/MySQL;
- pagamentos PIX/Mercado Pago e webhook;
- importação, moderação, deduplicação e ciclo de vida de radares;
- cálculo de rota, correlação espacial e avisos de direção;
- PWA, cache, interface vertical e DriveOS horizontal;
- mapas estaduais, detalhe municipal, CNEFE e Offline Core Android;
- assinatura, atualização, endurecimento do WebView e CI do APK;
- documentação, arquivos distribuídos e controles de regressão.

## Principais achados e tratamento

| Prioridade | Achado | Tratamento aplicado |
|---|---|---|
| Crítica | Qualquer visitante poderia tentar assumir uma instalação ainda não concluída | Instalador exige HTTPS fora de localhost, token privado de 64 caracteres, autorização com validade de 15 minutos, CSRF e gravações atômicas; o token é removido após o bloqueio final |
| Crítica | APK antigo de debug e atualização sem confiança suficiente | APK legado removido da distribuição; release exige chave de produção, origem HTTPS confiável, SHA-256 válido e verificação da assinatura no workflow |
| Alta | APIs sensíveis com proteção ou limites inconsistentes | Sessão, perfil, CSRF, limites por sessão/rede e liberação antecipada do lock de sessão foram aplicados conforme o tipo de operação |
| Alta | Relato comunitário podia se aproximar de um alerta ativo | Novos relatos entram como pendentes, sem aceitar estado ativo ou identificador externo do cliente, e exigem moderação administrativa |
| Alta | Exceções poderiam revelar SQL, caminhos e respostas de provedores | Respostas usam mensagens controladas e identificador de incidente; caminhos, origem do pacote e último erro foram removidos dos status públicos |
| Alta | Webhook podia aceitar corpo excessivo e persistir dados pessoais brutos | Corpo limitado a 256 KiB, identificador validado e payload sanitizado antes da persistência |
| Alta | Confirmação de pagamento sujeita a corrida | Ordem é bloqueada na transação e valores/estado são revalidados antes de conceder acesso |
| Alta | Offline estadual disparava grande quantidade de downloads municipais | Arquitetura alterada para base estadual leve mais detalhe opcional somente do município selecionado |
| Alta | Caches e downloads podiam crescer sem limite ou ficar parcialmente gravados | Limites de resposta, download atômico, arquivos temporários e limpeza por idade foram adicionados |
| Média | Migração adquiria lock do banco em toda requisição | Leitura rápida da versão evita `GET_LOCK` quando o esquema já está atualizado; migrações continuam serializadas |
| Média | Arquivo CNEFE bruto permanecia indefinidamente | ZIP sem uso é removido após 30 dias, mantendo o índice de ruas; ele só é baixado novamente quando uma busca por número exige os pontos |
| Média | Busca horizontal omitindo município não retornava sugestões | DriveOS usa o município preparado quando disponível e, caso contrário, orienta a digitação completa sem fazer uma chamada inútil |
| Média | Clique de velocidade no DriveOS tinha dois listeners idênticos | Registro duplicado removido e protegido por teste de regressão |
| Média | Observadores de GPS e anúncio de chegada podiam repetir | Um único watcher é mantido e o anúncio de chegada é controlado por estado da viagem |
| Alta | Limite de 25 mil do Mapbox podia ser interpretado como aplicável à WebView | Documentação separa os 25 mil MAU dos SDKs móveis nativos dos 50 mil carregamentos do GL JS; Directions, Search e Navigation permanecem medidores separados |
| Alta | Token Mapbox poderia ser inserido de forma insegura | Instalador e painel aceitam somente token público `pk.`, rejeitam `sk.`, cifram o valor no banco e orientam restrição por URL |
| Média | Visual continuava próximo da edição anterior | Camada final premium redesenha hierarquia, superfícies, tipografia, cores, mapa, player, DriveOS, instalação e telas Android sem trocar IDs ou fluxos funcionais |

## Melhorias de precisão e operação

- Radares de rota usam corredor espacial mais estreito, posição ao longo do trajeto, direção de tráfego, validade e deduplicação por proximidade.
- Importações oficiais são transacionais e o cron desativa registros expirados ou que precisam de revalidação.
- Coordenadas de relatos são validadas dentro do território brasileiro.
- XML/KML/XLSX é lido sem acesso externo de rede (`LIBXML_NONET`).
- O índice CNEFE trabalha com limite de 1 GiB por arquivo, diretório de origem limitado e escrita por arquivo parcial seguido de renomeação.
- Downloads e caches JSON usam limites explícitos, escrita atômica e limpeza de resíduos.
- PWA não reutiliza página autenticada como fallback global e usa Leaflet local para o shell offline.
- Offline Core restaura rota ativa, mapa, radares, limites e manobras salvas; nova rota ainda depende de internet.
- WebView libera depuração somente no build de debug e restringe downloads à origem configurada.
- Login executa verificação com hash fictício quando o usuário não existe, reduzindo enumeração por diferença de tempo.
- Mapbox GL JS funciona como mapa-base opcional; uma falha de SDK, token, rede ou estilo devolve o controle ao OpenStreetMap e aos pacotes locais já existentes.

## Validação executada

- suíte estática `node tests/validate.mjs`, incluindo sintaxe JavaScript e regressões de segurança, offline, pagamentos, Android e DriveOS;
- validação de YAML do workflow e de todos os XML Android;
- lint de sintaxe de todos os arquivos PHP no GitHub Actions;
- `lintDebug` e `assembleDebug` das fontes Android com JDK 17, Gradle 8.9 e SDK Android 35 no GitHub Actions;
- geração do APK de debug apenas como artefato temporário de teste;
- inspeção de arquivos de distribuição, regras de acesso e ausência do APK de debug legado;
- comparação por hash dos 134 arquivos publicados e conferência do ZIP final contra a árvore validada.

O workflow do pull request concluiu com sucesso as validações web, o lint Android e a montagem do APK de teste. O job de produção permanece intencionalmente restrito a tag ou execução manual: ele exige a chave privada configurada nos segredos, monta o APK release, verifica a assinatura e publica o SHA-256.

## Limites conhecidos

- Uma rota totalmente nova e o recálculo fora do trajeto ainda precisam de internet e dos provedores configurados. A última viagem preparada pode continuar no modo offline.
- Cobertura e atualidade dos radares dependem das fontes oficiais/abertas e da execução diária do cron; relatos da comunidade só entram após moderação.
- O APK final não acompanha o código-fonte: deve ser produzido com a chave privada de produção, mantida fora do repositório, e publicado junto do checksum.
- Testes de carga, ensaio com banco real e teste de condução em aparelho físico continuam recomendados antes de produção pública.

## Checklist de publicação

1. Fazer backup de banco, configuração, armazenamento e chave de assinatura.
2. Publicar o servidor apenas por HTTPS e proteger diretórios internos no servidor web.
3. Executar o instalador com o token privado ou atualizar preservando `config/config.php`.
4. Rodar as validações e o cron manualmente uma vez em homologação.
5. Configurar segredos de assinatura e URL HTTPS no GitHub Actions.
6. Gerar, verificar e testar o APK release em um aparelho real.
7. Validar webhook, planos, moderação, mapas offline e recuperação da última viagem.
8. Liberar produção e acompanhar logs por identificador de incidente.

## Estado do GitHub

As alterações estão publicadas na branch `codex/musicroad-1.2.0-security-offline` e na [PR rascunho #3](https://github.com/juliobji6-ship-it/MusicRoad-AI/pull/3). A branch `main` permanece intacta; merge e publicação de produção dependem de revisão, configuração dos segredos e testes de homologação/aparelho físico.
