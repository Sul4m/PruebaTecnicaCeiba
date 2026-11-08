resource "azurerm_postgresql_flexible_server" "main" {
  name                = "psql-ptc-${var.suffix}"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  version             = "16"
  sku_name            = var.postgres_sku_name
  storage_mb          = 32768
  tags                = local.tags

  backup_retention_days         = 7
  geo_redundant_backup_enabled  = false
  public_network_access_enabled = false
  delegated_subnet_id           = azurerm_subnet.postgres.id
  private_dns_zone_id           = azurerm_private_dns_zone.postgres.id

  administrator_login               = var.postgres_admin_login
  administrator_password_wo         = ephemeral.random_password.postgres_admin.result
  administrator_password_wo_version = var.db_password_version

  depends_on = [azurerm_private_dns_zone_virtual_network_link.postgres]

  lifecycle {
    # Azure asigna la zona de disponibilidad si no se fija.
    ignore_changes = [zone]
  }
}

resource "azurerm_postgresql_flexible_server_database" "triage" {
  name      = "triage"
  server_id = azurerm_postgresql_flexible_server.main.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}
