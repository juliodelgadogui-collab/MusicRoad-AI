# Estado e próximos passos após a 1.2

## Entregue

- Autorização e moderação das APIs críticas.
- Migração de esquema idempotente com marcador posterior ao sucesso.
- Sincronização e ciclo de vida de radares.
- Melhor correspondência entre ponto, segmento e direção da rota.
- Base estadual leve retomável, detalhe municipal sob demanda e Offline Core com navegação em segundo plano.
- Build Android direto do código-fonte, debug para teste e release obrigatoriamente assinado.
- Verificações estáticas e lint no CI.

## Evoluções possíveis

- Motor de roteamento totalmente local para criar destinos novos sem internet.
- Cobertura oficial adicional para estados que publiquem dados abertos estruturados.
- Testes de integração com MariaDB e um dispositivo/emulador Android no CI.
- Publicação automatizada do APK assinado no servidor, condicionada a ambiente protegido e aprovação humana.
- Política de retenção e exportação de auditoria definida para a operação real.
