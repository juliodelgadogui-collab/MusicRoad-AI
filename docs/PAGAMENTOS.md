# Pagamentos — Mercado Pago PIX

O MusicRoad v1.2.0 usa PIX direto no servidor.

## Configuração

1. Acesse **Administração → Financeiro**.
2. Informe o **Access Token de produção** da aplicação Mercado Pago.
3. Informe o **Webhook Secret** da mesma aplicação.
4. No painel Mercado Pago, em Webhooks, cadastre a URL exibida pelo MusicRoad e habilite o evento **Pagamentos**.
5. Ative **Mercado Pago PIX** e **Venda de planos por PIX**.
6. Em **Planos**, configure preços maiores que zero.

## Fluxo

- O cliente escolhe o plano.
- Informa o CPF do pagador apenas para emissão do PIX.
- O servidor cria o pagamento em `/v1/payments` com `payment_method_id=pix` e `X-Idempotency-Key`.
- O app mostra QR Code e PIX Copia e Cola.
- O webhook recebe a atualização, valida `X-Signature` quando o Webhook Secret está configurado e consulta o pagamento na API antes de liberar a licença.
- A tela também consulta o status do pagamento enquanto fica aberta.
- A licença é liberada uma única vez, inclusive se o webhook for repetido.

## Privacidade

O CPF não é salvo em `payment_orders`. Respostas do Mercado Pago são sanitizadas antes da persistência para remover identificação, telefone e endereço do pagador.
