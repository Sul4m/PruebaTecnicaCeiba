data "azurerm_client_config" "current" {}

locals {
  rg_name = "rg-pruebatecnicaceiba-${var.suffix}"

  tags = {
    project = "PruebaTecnicaCeiba"
  }

  # Una entrada por servicio desplegado en AKS. La Story 3.4 añade "asistente" aquí
  # (y su Redis en redis.tf) sin tocar el resto.
  workloads = {
    triage = {
      namespace       = "triage"
      service_account = "triage"
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
