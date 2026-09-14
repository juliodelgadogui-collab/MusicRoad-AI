#!/usr/bin/env bash
set -euo pipefail

# Limpa GitHub Actions/Artifacts sem depender de um runner do GitHub Actions.
# Uso em Codespaces ou qualquer terminal com `gh` autenticado:
#   bash scripts/cleanup-github-history.sh
# Opcional:
#   KEEP_RUNS=150 MAX_AGE_HOURS=24 bash scripts/cleanup-github-history.sh

KEEP_RUNS="${KEEP_RUNS:-150}"
MAX_AGE_HOURS="${MAX_AGE_HOURS:-24}"
REPO="${REPO:-juliodelgadogui-collab/MusicRoad-AI}"

[[ "$KEEP_RUNS" =~ ^[0-9]+$ ]] || { echo "KEEP_RUNS inválido: $KEEP_RUNS"; exit 1; }
[[ "$MAX_AGE_HOURS" =~ ^[0-9]+$ ]] || { echo "MAX_AGE_HOURS inválido: $MAX_AGE_HOURS"; exit 1; }
[ "$KEEP_RUNS" -ge 20 ] || { echo "KEEP_RUNS deve ser pelo menos 20"; exit 1; }

command -v gh >/dev/null 2>&1 || { echo "Erro: GitHub CLI (gh) não encontrado."; exit 1; }
gh auth status >/dev/null

echo "Repositório: $REPO"
echo "Manter Actions: $KEEP_RUNS"
echo "Manter artifacts com até: $MAX_AGE_HOURS hora(s)"
echo

cutoff=$(date -u -d "$MAX_AGE_HOURS hours ago" +%s)
artifact_total=0
artifact_deleted=0

while IFS=$'\t' read -r id name created_at; do
  [ -z "${id:-}" ] && continue
  artifact_total=$((artifact_total + 1))
  created_epoch=$(date -u -d "$created_at" +%s)

  if [ "$created_epoch" -le "$cutoff" ]; then
    echo "[artifact] apagando $name (id=$id, criado=$created_at)"
    if gh api --method DELETE "/repos/${REPO}/actions/artifacts/${id}"; then
      artifact_deleted=$((artifact_deleted + 1))
    else
      echo "[artifact] aviso: falha ao apagar id=$id"
    fi
  fi
done < <(gh api --paginate "/repos/${REPO}/actions/artifacts?per_page=100" --jq '.artifacts[] | [.id, .name, .created_at] | @tsv')

echo
printf 'Artifacts encontrados: %s\nArtifacts apagados: %s\n' "$artifact_total" "$artifact_deleted"
echo

runs_file=$(mktemp)
trap 'rm -f "$runs_file"' EXIT

gh api --paginate "/repos/${REPO}/actions/runs?per_page=100" \
  --jq '.workflow_runs[] | select(.status == "completed") | [.id, .name, .created_at] | @tsv' > "$runs_file"

run_total=$(wc -l < "$runs_file" | tr -d ' ')
run_deleted=0
index=0

echo "Actions concluídos encontrados: $run_total"
echo "Mantendo os $KEEP_RUNS mais recentes..."

while IFS=$'\t' read -r id name created_at; do
  [ -z "${id:-}" ] && continue
  index=$((index + 1))

  if [ "$index" -le "$KEEP_RUNS" ]; then
    continue
  fi

  echo "[action] apagando $name (run=$id, criado=$created_at)"
  if gh api --method DELETE "/repos/${REPO}/actions/runs/${id}"; then
    run_deleted=$((run_deleted + 1))
  else
    echo "[action] aviso: falha ao apagar run=$id"
  fi
done < "$runs_file"

echo
echo "Limpeza finalizada."
echo "Actions apagados: $run_deleted"
echo "Actions preservados: até $KEEP_RUNS mais recentes"
echo "Releases, tags, branches e código não foram alterados."
