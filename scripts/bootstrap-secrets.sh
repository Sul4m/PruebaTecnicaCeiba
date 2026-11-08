#!/usr/bin/env bash
# Escribe en Key Vault los secretos que Terraform NO gestiona (así no existen en su estado):
#   - triage-api-key               (generada aquí con openssl; nunca se imprime)
#   - eventhubs-connection-string  (regla SAS Send+Listen creada aquí; la clave nunca pasa por Terraform)
# Uso: scripts/bootstrap-secrets.sh <resource-group> <key-vault> <eventhubs-namespace> [--rotate-api-key]
# Requiere: az login (rol "Key Vault Secrets Officer" en el vault y permisos sobre Event Hubs).
set -euo pipefail

RG="${1:?resource group}"
KV="${2:?key vault}"
NS="${3:?namespace de Event Hubs}"
ROTATE="${4:-}"
RULE="triage-app"

if ! az keyvault secret show --vault-name "$KV" --name triage-api-key --query id -o tsv >/dev/null 2>&1 \
   || [[ "$ROTATE" == "--rotate-api-key" ]]; then
  az keyvault secret set --vault-name "$KV" --name triage-api-key \
    --value "$(openssl rand -base64 36 | tr -d '\n=+/')" --output none
  echo "triage-api-key escrita en $KV"
else
  echo "triage-api-key ya existe (use --rotate-api-key para regenerarla)"
fi

az eventhubs namespace authorization-rule create \
  --resource-group "$RG" --namespace-name "$NS" --name "$RULE" \
  --rights Send Listen --output none

CONN="$(az eventhubs namespace authorization-rule keys list \
  --resource-group "$RG" --namespace-name "$NS" --name "$RULE" \
  --query primaryConnectionString -o tsv)"
az keyvault secret set --vault-name "$KV" --name eventhubs-connection-string \
  --value "$CONN" --output none
unset CONN
echo "eventhubs-connection-string escrita en $KV"
echo "Para leer la API key al probar: az keyvault secret show --vault-name $KV --name triage-api-key --query value -o tsv"
