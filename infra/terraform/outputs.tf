output "resource_group" {
  value = azurerm_resource_group.main.name
}

output "aks_name" {
  value = azurerm_kubernetes_cluster.main.name
}

output "acr_name" {
  value = azurerm_container_registry.main.name
}

output "acr_login_server" {
  value = azurerm_container_registry.main.login_server
}

output "key_vault_name" {
  value = azurerm_key_vault.main.name
}

output "tenant_id" {
  value = data.azurerm_client_config.current.tenant_id
}

output "subscription_id" {
  value = data.azurerm_client_config.current.subscription_id
}

output "postgres_host" {
  description = "FQDN privado (solo resoluble dentro de la VNet)."
  value       = azurerm_postgresql_flexible_server.main.fqdn
}

output "postgres_admin_login" {
  value = var.postgres_admin_login
}

output "eventhubs_namespace" {
  value = azurerm_eventhub_namespace.main.name
}

output "kafka_bootstrap_servers" {
  value = "${azurerm_eventhub_namespace.main.name}.servicebus.windows.net:9093"
}

output "workload_client_ids" {
  description = "clientId de la identidad de cada workload (ServiceAccount y SecretProviderClass)."
  value       = { for k, v in azurerm_user_assigned_identity.workload : k => v.client_id }
}

output "ingress_dns_label" {
  value = local.ingress_dns_label
}

output "ingress_host" {
  description = "Host público HTTPS esperado (etiqueta DNS de la IP del ingress; ver k8s/platform)."
  value       = "${local.ingress_dns_label}.${var.location}.cloudapp.azure.com"
}

output "ci_deploy_client_id" {
  value = try(azurerm_user_assigned_identity.ci_deploy[0].client_id, null)
}

output "ci_terraform_apply_client_id" {
  value = try(azurerm_user_assigned_identity.ci_terraform_apply[0].client_id, null)
}

output "ci_terraform_plan_client_id" {
  value = try(azurerm_user_assigned_identity.ci_terraform_plan[0].client_id, null)
}

output "redis_name" {
  value = azurerm_managed_redis.main.name
}

output "redis_host" {
  description = "Hostname de Azure Managed Redis (TLS en el puerto 10000)."
  value       = azurerm_managed_redis.main.hostname
}

output "openai_endpoint" {
  description = "Endpoint (subdominio propio, requerido para autenticación Entra) de Azure OpenAI."
  value       = azurerm_cognitive_account.openai.endpoint
}

output "openai_account_name" {
  value = azurerm_cognitive_account.openai.name
}

output "asistente_workload_client_id" {
  value = azurerm_user_assigned_identity.workload["asistente"].client_id
}
