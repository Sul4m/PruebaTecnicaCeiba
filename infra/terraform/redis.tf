# Azure Managed Redis para la proyección de urgencias del Asistente (Story 3.4).
# TLS obligatorio (client_protocol Encrypted, puerto 10000) y autenticación por access key.
# Spring Data Redis (Lettuce) no soporta Entra ID sin una librería adicional, por eso se usa la clave:
#  - la clave se lee con `az` y se escribe en Key Vault con scripts/bootstrap-secrets.sh
#    (secreto `redis-password`); Terraform no la expone en ninguna salida.
#  - Limitación honesta: azurerm guarda `primary_access_key` como atributo computado del recurso en
#    el estado (marcado sensible). Evitarlo exigiría desactivar las claves y usar Entra (no viable aquí).
#  - EnterpriseCluster: un único endpoint, compatible con el cliente Lettuce no-cluster.
resource "azurerm_managed_redis" "main" {
  name                = "redis-ptc-${var.suffix}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  sku_name            = var.redis_sku_name
  # Entorno desechable: sin alta disponibilidad (la HA duplica el costo).
  high_availability_enabled = false
  tags                      = local.tags

  default_database {
    access_keys_authentication_enabled = true
    client_protocol                    = "Encrypted"
    clustering_policy                  = "EnterpriseCluster"
    # Proyección reconstruible desde Event Hubs (replay): no se evicta (mejor fallar que perder
    # estado en silencio).
    eviction_policy = "NoEviction"
  }
}
