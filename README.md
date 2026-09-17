# Estrada Play

Aplicativo Android nativo para estrada, navegação, música, segurança rodoviária e operação de frotas.

## Fonte oficial

A única base Android ativa do projeto é:

`estrada-play-comunista-app/`

As bases Android antigas foram retiradas da árvore atual e permanecem apenas no histórico do Git.

## Versão atual de desenvolvimento

- Version name: `5.2.0`
- Version code: `520`
- APK: Universal
- Application ID instalado: `com.estradaplay.universal`
- Namespace interno temporariamente mantido: `com.estradaplay.comunista`
- Branch de desenvolvimento: `agent/estrada-play-comunista`

O namespace legado é uma dívida técnica conhecida e não representa a marca exibida ao usuário.

## Regras de arquitetura

- Android nativo; não usar WebView como base do aplicativo.
- Não restaurar arquiteturas antigas baseadas em scripts `apply_v*` ou múltiplas pastas Android.
- Não fazer merge para `main` sem autorização explícita.
- O workflow oficial compila somente `estrada-play-comunista-app/`.
- Builds normais geram apenas o APK Universal.

## Principais módulos

- Central pessoal Estrada Play
- Estrada / mapa / navegação
- Música local e player
- Proteção e alertas rodoviários
- Copiloto
- Comboio
- Dashcam local
- Manutenção e custos pessoais
- Módulo Empresa / Estrada Play Frotas

O módulo Empresa inclui frota, motoristas, vínculo motorista-veículo, jornadas, km diário, mapa da frota, Comboio Empresa, abastecimentos, manutenção, ocorrências e relatórios. Consulte `docs/MODULO-EMPRESA.md`.

## Backend

O backend PHP e as APIs ficam principalmente em `api/` e compartilham o mesmo sistema de autenticação nativa. O aplicativo usa por padrão:

`https://musicroad1.gestao2.store/`

Endpoints presentes no Git não significam automaticamente que já estejam publicados na hospedagem. Alterações de banco e APIs devem ser implantadas e validadas no servidor antes de uma liberação de produção.

## Build

O workflow oficial é:

`.github/workflows/build-estrada-play-comunista.yml`

Ele é `workflow_dispatch` manual e executa:

1. verificação da fonte Android canônica;
2. testes unitários;
3. Android lint;
4. compilação do Universal candidate e APK de instrumentação;
5. smoke tests em emulador quando habilitados;
6. upload do APK Universal.

A assinatura `candidate` usa a chave estável de teste. Uma release de produção continua bloqueada sem a chave permanente configurada.

## Estado de produção

A versão 5.2.0 contém uma unificação estrutural importante e o módulo Empresa. Antes de tratar essa versão como release, ela deve passar pelo workflow completo e por teste real em aparelho, incluindo login, permissão de localização, Estrada, mapa, rotação, música e os fluxos Empresa.
