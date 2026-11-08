# Backend remoto (Azure Storage). Los valores NO se versionan: se pasan al inicializar con
#   terraform init -backend-config=backend.hcl
# Ver backend.hcl.example y docs/despliegue-azure.md (scripts/bootstrap-tfstate.sh crea el almacenamiento).
# Para validar sin backend: terraform init -backend=false
terraform {
  backend "azurerm" {}
}
