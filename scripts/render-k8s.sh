#!/usr/bin/env bash
# Sustituye SOLO las variables no secretas de los manifests y escribe el resultado en <outdir>.
# Uso: scripts/render-k8s.sh <outdir> [dir-origen ...]   (por defecto: k8s/triage)
# Variables requeridas en el entorno (todas no secretas): ver VARS.
set -euo pipefail

OUT="${1:?directorio de salida}"; shift || true
SRC=("${@:-k8s/triage}")
VARS='${IMAGE} ${TRIAGE_WORKLOAD_CLIENT_ID} ${AZURE_TENANT_ID} ${KV_NAME} ${DB_HOST} ${DB_USER} ${KAFKA_BOOTSTRAP_SERVERS} ${INGRESS_HOST} ${INGRESS_DNS_LABEL} ${ACME_EMAIL} ${ASISTENTE_WORKLOAD_CLIENT_ID} ${REDIS_HOST} ${AZURE_OPENAI_ENDPOINT} ${ASISTENTE_RAG_THRESHOLD}'

for name in $(echo "$VARS" | tr -d '${}'); do
  if [[ -z "${!name:-}" && "$(grep -rl "\${$name}" "${SRC[@]}" 2>/dev/null | wc -l)" -gt 0 ]]; then
    echo "Falta la variable $name" >&2; exit 1
  fi
done

mkdir -p "$OUT"
for dir in "${SRC[@]}"; do
  for f in "$dir"/*.yaml; do
    envsubst "$VARS" < "$f" > "$OUT/$(basename "$dir")-$(basename "$f")"
  done
done
echo "Manifests renderizados en $OUT"
