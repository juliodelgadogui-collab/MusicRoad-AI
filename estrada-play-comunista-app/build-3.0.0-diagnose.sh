#!/usr/bin/env bash
set -uo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$APP_DIR"

# UNIVERSAL_BUILD_DIAGNOSTICS_V300
LOG="$APP_DIR/build-3.0.0.log"
rm -f "$LOG"

printf '\n==> Executando build UNIVERSAL TESTE 3.0.0 com log completo\n'
set +e
bash "$APP_DIR/build-3.0.0-codespace.sh" 2>&1 | tee "$LOG"
status=${PIPESTATUS[0]}
set -e

if [ "$status" -eq 0 ]; then
  printf '\n==> BUILD UNIVERSAL CONCLUÍDO\n'
  printf 'Log salvo em: %s\n' "$LOG"
  printf 'APK esperado em: %s\n' "$APP_DIR/dist/Estrada-Play-Comunista-Teste-Universal-3.0.0.apk"
  exit 0
fi

printf '\n============================================================\n'
printf 'CAUSA PROVÁVEL DO BUILD UNIVERSAL FAILED\n'
printf '============================================================\n'

# First show the Gradle failure block when present.
awk '
  /FAILURE: Build failed with an exception\./ {show=1}
  show {print}
  show && /BUILD FAILED/ {exit}
' "$LOG" | tail -n 140

printf '\n---------------- ERROS/TAREFAS RELEVANTES ------------------\n'
grep -nEi \
  'MissingPermission|ERRO 16 KB|EPC 3\.0\.0 Universal: pré-validação FALHOU|(^|[[:space:]])(error:|fatal:)|Execution failed for task|What went wrong|Lint found|lintVital|lint.*failed|FAILED$|Compilation failed|Could not resolve|Could not determine|Caused by:|Exception|AssertionError|FAILURES!!!|There (was|were) [0-9]+ failure' \
  "$LOG" | tail -n 160 || true

printf '\n---------------- ÚLTIMAS 120 LINHAS -------------------------\n'
tail -n 120 "$LOG"

printf '\n============================================================\n'
printf 'Build Universal falhou (código %s). Log completo: %s\n' "$status" "$LOG"
printf '============================================================\n'
exit "$status"
