# Estrada Play — Módulo Empresa

## Escopo do MVP

O módulo Empresa convive no mesmo APK da conta pessoal.

- Conta pessoal: Central normal do Estrada Play.
- Proprietário/admin/gestor: Central Empresa / Estrada Play Frotas.
- Motorista vinculado: mantém a Central pessoal e ganha a Jornada da Empresa.

Não fazem parte deste módulo: licenciamento, seguro ou burocracia documental do veículo.

## Funções

- Cadastro de veículos e motorista responsável.
- Regra de um motorista ativo por veículo e um veículo ativo por motorista.
- Jornada empresarial usando o mesmo GPS da Estrada, sem segundo listener de localização.
- Km diário por motorista e veículo.
- Mapa da frota com posições recentes somente enquanto o modo Estrada está em uso.
- Comboio Empresa com equipe e líder definidos pelo gestor.
- Viagens e duração da jornada.
- Relatórios de 1, 7 e 30 dias.
- Manutenção por km e/ou data.
- Abastecimentos, gasto e custo aproximado por km.
- Ocorrências operacionais e resolução pelo gestor.
- Dados profissionais/CNH do motorista e alertas de validade.
- Detalhe operacional por veículo.
- Comparação objetiva de veículos e motoristas, sem score de funcionário.

## Privacidade e ciclo de vida

O módulo Empresa não inicia um rastreador GPS separado. `CompanyJourneyTracker` consome o broadcast interno já produzido pela proteção/tela Estrada. Ao app sair de uso a jornada é encerrada. O servidor também fecha jornadas que ficarem sem presença correspondente por mais de 10 minutos, cobrindo encerramento abrupto do processo Android.

O mapa da frota deve tratar apenas posição recente como ao vivo. Posição antiga nunca deve ser apresentada como rastreamento atual.

## Endpoints do módulo

- `api/native_company.php`
- `api/native_company_create.php`
- `api/native_company_drivers.php`
- `api/native_company_driver_status.php`
- `api/native_company_driver_manage.php`
- `api/native_company_driver_profiles.php`
- `api/native_company_journeys.php`
- `api/native_company_map.php`
- `api/native_company_report.php`
- `api/native_company_maintenance.php`
- `api/native_company_fuel.php`
- `api/native_company_incidents.php`
- `api/native_company_vehicle_detail.php`
- `api/native_company_compare.php`
- `api/native_company_convoy.php`
- `api/native_company_convoy_roster.php`
- `api/native_company_convoy_claim_leader.php`
- `api/convoy.php` contém a proteção que restringe Comboio Empresa à equipe autorizada.

## Antes de liberar em produção

1. Publicar os PHP acima no mesmo servidor/API usado pelo app.
2. Confirmar que o usuário do banco possui as permissões necessárias para a criação/migração automática das tabelas do módulo.
3. Fazer backup do banco antes do primeiro teste real.
4. Testar com pelo menos três contas: gestor, motorista vinculado e conta pessoal sem empresa.
5. Validar o fluxo: criar empresa → veículo → motorista → atribuir veículo → abrir Estrada → gerar km → mapa da frota → comboio → abastecimento → manutenção → ocorrência → relatórios.
6. Só depois gerar a versão Universal destinada a teste real.

## Estado de validação

O módulo foi implementado e revisado estaticamente na branch de desenvolvimento. Enquanto não houver uma compilação autorizada, ele não deve ser considerado validado pelo compilador Android ou por testes instrumentados. Os endpoints no repositório também não significam que estejam publicados na hospedagem.
