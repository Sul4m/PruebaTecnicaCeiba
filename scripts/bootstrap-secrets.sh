#!/usr/bin/env bash
# Escribe en Key Vault los secretos que Terraform NO gestiona (así no existen en su estado):
#   - triage-api-key                        (openssl; nunca se imprime)
#   - eventhubs-connection-string           (regla SAS Send+Listen "triage-app")
#   - asistente-api-key                     (openssl; clave PROPIA del Asistente, distinta de la de Triage)
#   - asistente-eventhubs-connection-string (regla SAS Listen+Send "asistente-app": consume
#                                            triaje.eventos y publica al DLT)
#   - redis-password                        (access key de Azure Managed Redis; solo si se pasa <redis-name>)
# Azure OpenAI no necesita secreto: se accede por Entra ID (Workload Identity).
# Uso: scripts/bootstrap-secrets.sh <resource-group> <key-vault> <eventhubs-namespace> [redis-name] [--rotate-api-key]
# Requiere: az login (rol "Key Vault Secrets Officer" en el vault y permisos sobre Event Hubs y Redis).
# Idempotente: las API keys existentes se conservan salvo --rotate-api-key; el resto se reescribe.
set -euo pipefail

RG="${1:?resource group}"
KV="${2:?key vault}"
NS="${3:?namespace de Event Hubs}"
shift 3
REDIS=""
ROTATE=""
for arg in "$@"; do
  case "$arg" in
    --rotate-api-key) ROTATE="--rotate-api-key" ;;
    *) REDIS="$arg" ;;
  esac
done

ensure_api_key() {
  local name="$1"
  if ! az keyvault secret show --vault-name "$KV" --name "$name" --query id -o tsv >/dev/null 2>&1 \
     || [[ "$ROTATE" == "--rotate-api-key" ]]; then
    az keyvault secret set --vault-name "$KV" --name "$name" \
      --value "$(openssl rand -base64 36 | tr -d '\n=+/')" --output none
    echo "$name escrita en $KV"
  else
    echo "$name ya existe (use --rotate-api-key para regenerarla)"
  fi
}

ensure_sas_secret() {
  local rule="$1" rights="$2" secret="$3" conn
  # shellcheck disable=SC2086
  az eventhubs namespace authorization-rule create \
    --resource-group "$RG" --namespace-name "$NS" --name "$rule" \
    --rights $rights --output none
  conn="$(az eventhubs namespace authorization-rule keys list \
    --resource-group "$RG" --namespace-name "$NS" --name "$rule" \
    --query primaryConnectionString -o tsv)"
  az keyvault secret set --vault-name "$KV" --name "$secret" --value "$conn" --output none
  unset conn
  echo "$secret escrita en $KV"
}

ensure_api_key triage-api-key
ensure_sas_secret triage-app "Send Listen" eventhubs-connection-string

ensure_api_key asistente-api-key
ensure_sas_secret asistente-app "Listen Send" asistente-eventhubs-connection-string

if [[ -n "$REDIS" ]]; then
  KEY="$(az redisenterprise database list-keys --cluster-name "$REDIS" --resource-group "$RG" \
    --query primaryKey -o tsv)"
  az keyvault secret set --vault-name "$KV" --name redis-password --value "$KEY" --output none
  unset KEY
  echo "redis-password escrita en $KV"
else
  echo "Aviso: sin <redis-name> no se escribe redis-password (el Asistente no arrancará sin ella)."
fi

echo "Para leer una API key al probar: az keyvault secret show --vault-name $KV --name triage-api-key --query value -o tsv"
echo "                                 az keyvault secret show --vault-name $KV --name asistente-api-key --query value -o tsv"
