# Sistema Médico — Atestados e Laudos

Sistema independente dentro do repositório MusicRoad-AI. Não utiliza o banco, autenticação, APIs ou assets do MusicRoad-AI.

## Instalação
1. PHP 8.1+ com PDO SQLite.
2. Aponte o servidor para o repositório normalmente.
3. Acesse /medical/.
4. Na primeira execução, o banco SQLite é criado em medical/database/medical.sqlite.
5. O primeiro acesso utiliza o cadastro inicial exibido pelo instalador.

## Segurança
Configure MEDICAL_APP_SECRET em variável de ambiente em produção. Os documentos e uploads devem ser mantidos fora de área pública quando possível.
