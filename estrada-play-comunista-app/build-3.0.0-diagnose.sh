#!/usr/bin/env bash
set -uo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$APP_DIR"

LOG="$APP_DIR/build-3.0.0.log"
rm -f "$LOG"

printf '\n==> Executando build TESTE 3.0.0 com log completo\n'
set +e
bash "$APP_DIR/build-3.0.0-codespace.sh" 2>&1 | tee "$LOG"
status=${PIPESTATUS[0]}
set -e

if [ "$status" -eq 0 ]; then
  printf '\n==> BUILD CONCLUÍDO\n'
  printf 'Log salvo em: %s\n' "$LOG"
  exit 0
fi

printf '\n============================================================\n'
printf 'CAUSA PROVÁVEL DO BUILD FAILED\n'
printf '============================================================\n'

# First show the Gradle failure block when present.
awk '
  /FAILURE: Build failed with an exception\./ {show=1}
  show {print}
  show && /BUILD FAILED/ {exit}
' "$LOG" | tail -n 120

printf '\n---------------- ERROS/TAREFAS RELEVANTES ------------------\n'
grep -nEi \
  '(^|[[:space:]])(error:|fatal:)|Execution failed for task|What went wrong|Lint found|lintVital|lint.*failed|FAILED$|Compilation failed|Could not resolve|Could not determine|Caused by:|Exception|AssertionError|FAILURES!!!|There (was|were) [0-9]+ failure' \
  "$LOG" | tail -n 120 || true

printf '\n---------------- ÚLTIMAS 100 LINHAS -------------------------\n'
tail -n 100 "$LOG"

printf '\n============================================================\n'
printf 'Build falhou (código %s). Log completo: %s\n' "$status" "$LOG"
printf '============================================================\n'
exit "$status"
