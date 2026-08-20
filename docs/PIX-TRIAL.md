# MusicRoad 1.2.0 — Mercado Pago PIX + teste gratuito

## Mercado Pago PIX
1. No painel administrativo abra **Financeiro**.
2. Informe o **Access Token de produção** e o **Webhook Secret** da aplicação Mercado Pago.
3. Copie a URL de Webhook exibida pelo MusicRoad e, no Mercado Pago, habilite o evento **Pagamentos**.
4. Ative **Mercado Pago PIX** e **Venda de planos por PIX**.
5. Em **Planos**, informe os preços.

O MusicRoad cria pagamentos em `/v1/payments` com `payment_method_id=pix` e `X-Idempotency-Key`. O CPF é usado apenas para gerar o PIX e não é salvo no banco do MusicRoad. A confirmação é validada por webhook e também pode ser consultada pelo cliente enquanto a tela do QR Code está aberta.

## Teste grátis
- Cadastro disponível na tela de entrada.
- 24 horas por padrão (configurável no painel).
- 1 teste por identificador de dispositivo/instalação.
- O APK 1.2 envia somente um identificador derivado do Android ID.
- Proteção secundária por rede: 2 testes em 7 dias por padrão (configurável).
- O administrador continua podendo liberar acesso manualmente.

Nenhum controle local impede fraude em 100% dos casos: outro aparelho ou reset de fábrica podem gerar nova identidade. Para uma etapa futura, validação de telefone/CPF pode elevar a proteção, mas exige tratamento adicional de dados pessoais e/ou serviço externo.
