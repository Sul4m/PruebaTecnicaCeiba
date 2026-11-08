resource "azurerm_key_vault" "main" {
  name                       = "kv-ptc-${var.suffix}"
  location                   = azurerm_resource_group.main.location
  resource_group_name        = azurerm_resource_group.main.name
  tenant_id                  = data.azurerm_client_config.current.tenant_id
  sku_name                   = "standard"
  rbac_authorization_enabled = true
  # Entorno desechable: sin purge protection para poder borrar y recrear el mismo nombre.
  purge_protection_enabled   = false
  soft_delete_retention_days = 7
  tags                       = local.tags
}

# El administrador humano escribe secretos (plano de datos). Explícito (admin_object_id) y no el
# caller actual, para que un apply desde CI no reemplace la asignación.
resource "azurerm_role_assignment" "deployer_kv_officer" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = var.admin_object_id
}

# Contraseña de PostgreSQL: se genera de forma EFÍMERA (no llega al estado ni al plan) y se
# escribe con atributos write-only tanto en el servidor como en Key Vault. Rotación: db_password_version.
ephemeral "random_password" "postgres_admin" {
  length  = 40
  special = false
}

resource "azurerm_key_vault_secret" "db_password" {
  name             = "db-password"
  key_vault_id     = azurerm_key_vault.main.id
  value_wo         = ephemeral.random_password.postgres_admin.result
  value_wo_version = var.db_password_version
  content_type     = "text/plain"
  tags             = local.tags

  depends_on = [
    azurerm_role_assignment.deployer_kv_officer,
    azurerm_role_assignment.ci_terraform_apply_kv_officer,
  ]
}

# Los secretos `triage-api-key` y `eventhubs-connection-string` NO los gestiona Terraform:
# los escribe scripts/bootstrap-secrets.sh con `az keyvault secret set`, así no existen en el estado.
