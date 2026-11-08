#!/usr/bin/env bash
# Crea el almacenamiento del estado remoto de Terraform en un grupo SEPARADO (el estado debe existir
# antes que el grupo principal). Mismo tag project=PruebaTecnicaCeiba para el inventario de limpieza.
# Uso: scripts/bootstrap-tfstate.sh <suffix> <location>
set -euo pipefail

SUFFIX="${1:?suffix (3-8 minúsculas/dígitos)}"
LOCATION="${2:?location}"
RG="rg-pruebatecnicaceiba-${SUFFIX}-tfstate"
SA="sttfptc${SUFFIX}"

az group create --name "$RG" --location "$LOCATION" --tags project=PruebaTecnicaCeiba --output none
az storage account create --name "$SA" --resource-group "$RG" --location "$LOCATION" \
  --sku Standard_LRS --min-tls-version TLS1_2 --allow-blob-public-access false \
  --allow-shared-key-access false --tags project=PruebaTecnicaCeiba --output none

ME="$(az ad signed-in-user show --query id -o tsv)"
SCOPE="$(az storage account show --name "$SA" --resource-group "$RG" --query id -o tsv)"
az role assignment create --assignee-object-id "$ME" --assignee-principal-type User \
  --role "Storage Blob Data Contributor" --scope "$SCOPE" --output none
# La propagación del rol puede tardar unos minutos antes de poder crear el contenedor.
for _ in 1 2 3 4 5 6; do
  az storage container create --name tfstate --account-name "$SA" --auth-mode login --output none 2>/dev/null && break
  sleep 20
done

cat <<EOF
Complete infra/terraform/backend.hcl (ignorado por Git):
  resource_group_name  = "$RG"
  storage_account_name = "$SA"
  container_name       = "tfstate"
  key                  = "pruebatecnicaceiba.tfstate"
  use_azuread_auth     = true
EOF
