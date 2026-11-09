data "azurerm_client_config" "current" {}

locals {
  rg_name = "rg-pruebatecnicaceiba-${var.suffix}"

  tags = {
    project = "PruebaTecnicaCeiba"
  }

  # Una entrada por servicio desplegado en AKS (Redis en redis.tf, Azure OpenAI en openai.tf).
  workloads = {
    triage = {
      namespace       = "triage"
      service_account = "triage"
    }
    asistente = {
      namespace       = "asistente"
      service_account = "asistente"
    }
  }

  # Etiqueta DNS pública del ingress: <label>.<location>.cloudapp.azure.com
  ingress_dns_label = "ptc-${var.suffix}-api"
}

resource "azurerm_resource_group" "main" {
  name     = local.rg_name
  location = var.location
  tags     = local.tags
}
