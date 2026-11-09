resource "azurerm_container_registry" "main" {
  name                = "acrptc${var.suffix}"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  sku                 = "Basic"
  admin_enabled       = false # sin credenciales estáticas: acceso solo por identidades
  tags                = local.tags
}
