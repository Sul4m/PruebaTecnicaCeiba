variable "subscription_id" {
  description = "ID de la suscripción de Azure donde se crea el entorno."
  type        = string
}

variable "location" {
  description = "Región de Azure con cuota para AKS y PostgreSQL Flexible Server."
  type        = string
}

variable "suffix" {
  description = "Sufijo corto y único (minúsculas/dígitos). Forma rg-pruebatecnicaceiba-<sufijo> y nombres globales (ACR, Key Vault, Event Hubs)."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9]{3,8}$", var.suffix))
    error_message = "suffix debe tener 3-8 caracteres en minúsculas o dígitos."
  }
}

variable "aks_node_vm_size" {
  description = "Tamaño del nodo único de AKS. Debe tener cuota en la suscripción/región."
  type        = string
  default     = "Standard_D2s_v6"
}

variable "postgres_sku_name" {
  description = "SKU burstable mínimo de PostgreSQL Flexible Server."
  type        = string
  default     = "GP_Standard_D2ds_v4"
}

variable "postgres_admin_login" {
  description = "Usuario administrador de PostgreSQL (no es secreto; la contraseña sí y vive en Key Vault)."
  type        = string
  default     = "pgadmin"
}

variable "db_password_version" {
  description = "Incrementar para rotar la contraseña de PostgreSQL y su secreto en Key Vault (atributos write-only)."
  type        = number
  default     = 1
}

variable "eventhub_message_retention_days" {
  description = "Retención de los event hubs (Standard admite hasta 7). Define el horizonte de replay del Asistente."
  type        = number
  default     = 7
}

variable "eventhub_partitions" {
  description = "Particiones de triaje.eventos y su DLT."
  type        = number
  default     = 3
}

variable "github_repository" {
  description = "owner/repo de GitHub para los federated credentials OIDC del CI. Vacío = no crear identidades de CI."
  type        = string
  default     = ""
}

variable "github_subject_prefix" {
  description = "Prefijo del subject OIDC de GitHub, p. ej. repo:owner@<ownerId>/repo@<repoId> (subject inmutable). Vacío = repo:<github_repository>."
  type        = string
  default     = ""
}

variable "admin_object_id" {
  description = "Object ID de Entra ID del administrador humano (az ad signed-in-user show --query id). Recibe admin de AKS y Secrets Officer; es explícito para que un apply desde CI no reemplace esos roles."
  type        = string
}

variable "tfstate_storage_account_id" {
  description = "ID de la cuenta de almacenamiento del estado (salida de scripts/bootstrap-tfstate.sh). Vacío = no asignar roles de estado a las identidades de CI."
  type        = string
  default     = ""
}

variable "postgres_zone" {
  description = "Zona de disponibilidad de PostgreSQL. En suscripciones nuevas de eastus2 la zona 1 no está disponible y los SKU Burstable no se aprueban."
  type        = string
  default     = "2"
}

variable "aks_node_count" {
  description = "Nodos del pool del sistema. Con 2 vCPU por nodo, 1 solo no alcanza para add-ons + cert-manager + ingress + Triage; la 3.4 (Asistente) también necesita el segundo."
  type        = number
  default     = 2
}

variable "asistente_consumer_group" {
  description = "Consumer group del Asistente en triaje.eventos (= ASISTENTE_KAFKA_GROUP_ID)."
  type        = string
  default     = "clinical-assistant-urgencias"
}

variable "redis_sku_name" {
  description = "SKU de Azure Managed Redis. Balanced_B0 (0,5 GB) es el más barato; suficiente para la proyección de urgencias."
  type        = string
  default     = "Balanced_B0"
}

variable "openai_chat_capacity" {
  description = "Capacidad (miles de tokens por minuto) del deployment gpt-5.4-mini (DataZoneStandard)."
  type        = number
  default     = 10
}

variable "openai_embedding_capacity" {
  description = "Capacidad (miles de tokens por minuto) del deployment text-embedding-3-small."
  type        = number
  default     = 10
}
