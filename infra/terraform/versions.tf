terraform {
  # >= 1.11: atributos write-only (`*_wo`) y recursos efímeros (`ephemeral`) que mantienen
  # contraseñas fuera del estado.
  required_version = ">= 1.11.0"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.40"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
  }
}

provider "azurerm" {
  subscription_id = var.subscription_id
  features {
    key_vault {
      # Al destruir el entorno de la prueba se debe poder volver a crear con el mismo nombre.
      purge_soft_delete_on_destroy = true
    }
  }
}
