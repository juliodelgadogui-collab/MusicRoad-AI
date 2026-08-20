# MusicRoad 1.2 — Offline Core e dispositivo

- O primeiro acesso é guiado por uma página dentro do APK.
- O vínculo usa um identificador derivado do Android; o `ANDROID_ID` bruto não é enviado.
- Uma base estadual leve de rodovias principais, o detalhe opcional do município selecionado, radares e última rota são compactados no armazenamento privado do app. No APK, esse armazenamento é canônico; o Cache Storage fica reservado ao PWA.
- Preparar um estado exige um pacote de mapa e um de radares, em vez de centenas de requisições municipais. O download interrompido reaproveita cada parte válida e tenta novamente somente o que faltou.
- O pacote estadual de radares é invalidado quando o banco ativo muda e inclui pontos sem UF cuja coordenada está dentro do limite do estado.
- A fotografia da viagem contém geometria, destino, radares, limites, manobras e o estado ativo/encerrado.
- Se a viagem estava ativa, o Offline Core reinicia o serviço Android. Alertas, voz, vibração e notificação continuam em segundo plano.
- O canvas local mostra vias compactadas, rota, posição e alertas sem baixar bibliotecas ou mapas externos.
- Ao sair do limite do pacote atual, o Offline Core procura automaticamente uma base estadual compatível já salva.
- Tocar sete vezes no cartão Versão revela a remoção do vínculo do aparelho.

O servidor mantém somente um cache técnico temporário, protegido por lock e poda no cron. Os pacotes de uso offline permanecem no dispositivo; o cache do servidor não representa a biblioteca pessoal do usuário.

Os arquivos CNEFE usados para localizar números também são temporários: após 30 dias sem uso, o cron remove o ZIP e os pontos auxiliares, preservando o índice de ruas. Uma busca numérica futura reconstrói esse município sob demanda.

## Limite conhecido

Calcular uma rota para um destino totalmente novo ainda requer o servidor online. A rota já salva e seus alertas funcionam sem internet.
