# Event Hubs expone un endpoint compatible con Kafka (9093, SASL_SSL) en el tier Standard.
# Los "tópicos" Kafka SON event hubs y no se pueden crear por la API Kafka: se crean aquí.
resource "azurerm_eventhub_namespace" "main" {
  name                = "evhns-ptc-${var.suffix}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  sku                 = "Standard"
  capacity            = 1
  tags                = local.tags
}

resource "azurerm_eventhub" "events" {
  name              = "triaje.eventos"
  namespace_id      = azurerm_eventhub_namespace.main.id
  partition_count   = var.eventhub_partitions
  message_retention = var.eventhub_message_retention_days
}

resource "azurerm_eventhub" "events_dlt" {
  name              = "triaje.eventos.dlt"
  namespace_id      = azurerm_eventhub_namespace.main.id
  partition_count   = var.eventhub_partitions
  message_retention = var.eventhub_message_retention_days
}

# La regla SAS y su connection string se crean con scripts/bootstrap-secrets.sh para que la clave
# no quede en el estado de Terraform (un azurerm_eventhub_namespace_authorization_rule la guardaría).

# Grupo de consumidores del Asistente (Story 3.4). Debe coincidir con ASISTENTE_KAFKA_GROUP_ID
# (k8s/asistente/configmap.yaml). El consumidor Kafka lo usa por nombre.
resource "azurerm_eventhub_consumer_group" "asistente" {
  name                = var.asistente_consumer_group
  namespace_name      = azurerm_eventhub_namespace.main.name
  eventhub_name       = azurerm_eventhub.events.name
  resource_group_name = azurerm_resource_group.main.name
}
