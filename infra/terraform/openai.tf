# Azure OpenAI para el Asistente (Story 3.4 / infraestructura de IA de la 4.4).
# Autenticación SOLO por Entra ID: local_auth_enabled = false desactiva las claves de la cuenta, así
# que no existe ninguna clave que filtrar ni que guardar en Key Vault. Spring AI usa
# DefaultAzureCredential (Workload Identity) cuando `spring.ai.azure.openai.api-key` está vacío.
# El subdominio propio es obligatorio para tokens de Entra.
resource "azurerm_cognitive_account" "openai" {
  name                          = "oai-ptc-${var.suffix}"
  location                      = azurerm_resource_group.main.location
  resource_group_name           = azurerm_resource_group.main.name
  kind                          = "OpenAI"
  sku_name                      = "S0"
  custom_subdomain_name         = "oai-ptc-${var.suffix}"
  local_auth_enabled            = false
  public_network_access_enabled = true
  tags                          = local.tags
}

resource "azurerm_cognitive_deployment" "chat" {
  name                 = "gpt-5.4-mini"
  cognitive_account_id = azurerm_cognitive_account.openai.id

  model {
    format  = "OpenAI"
    name    = "gpt-5.4-mini"
    version = "2026-03-17"
  }

  sku {
    # Las suscripciones nuevas solo tienen cuota de gpt-5.4-mini en DataZoneStandard (datos en EE. UU.).
    name     = "DataZoneStandard"
    capacity = var.openai_chat_capacity
  }
}

resource "azurerm_cognitive_deployment" "embedding" {
  name                 = "text-embedding-3-small"
  cognitive_account_id = azurerm_cognitive_account.openai.id

  model {
    format  = "OpenAI"
    name    = "text-embedding-3-small"
    version = "1"
  }

  sku {
    name     = "GlobalStandard"
    capacity = var.openai_embedding_capacity
  }

  # Los deployments de una misma cuenta se crean en serie (si no, 409 por operación en curso).
  depends_on = [azurerm_cognitive_deployment.chat]
}

# Solo inferencia: sin rol de gestión de la cuenta.
resource "azurerm_role_assignment" "asistente_openai_user" {
  scope                = azurerm_cognitive_account.openai.id
  role_definition_name = "Cognitive Services OpenAI User"
  principal_id         = azurerm_user_assigned_identity.workload["asistente"].principal_id
}
