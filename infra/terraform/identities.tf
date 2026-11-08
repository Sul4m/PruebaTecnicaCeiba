# --- Identidades de workload (una por servicio): Workload Identity + federated credential ---

resource "azurerm_user_assigned_identity" "workload" {
  for_each            = local.workloads
  name                = "id-${each.key}-${var.suffix}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags
}

resource "azurerm_federated_identity_credential" "workload" {
  for_each            = local.workloads
  name                = "fic-${each.key}"
  resource_group_name = azurerm_resource_group.main.name
  parent_id           = azurerm_user_assigned_identity.workload[each.key].id
  audience            = ["api://AzureADTokenExchange"]
  issuer              = azurerm_kubernetes_cluster.main.oidc_issuer_url
  subject             = "system:serviceaccount:${each.value.namespace}:${each.value.service_account}"
}

# Lectura de secretos del Key Vault vía CSI.
resource "azurerm_role_assignment" "workload_kv_reader" {
  for_each             = local.workloads
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.workload[each.key].principal_id
}

# --- Identidades de CI (GitHub Actions OIDC, sin secretos) ---

locals {
  ci_enabled      = var.github_repository != ""
  branch_subjects = [for b in var.github_deploy_branches : "repo:${var.github_repository}:ref:refs/heads/${b}"]
}

# Despliegue de aplicaciones: publica imágenes en ACR y aplica manifests.
resource "azurerm_user_assigned_identity" "ci_deploy" {
  count               = local.ci_enabled ? 1 : 0
  name                = "id-gh-deploy-${var.suffix}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags
}

resource "azurerm_federated_identity_credential" "ci_deploy" {
  count               = local.ci_enabled ? length(local.branch_subjects) : 0
  name                = "fic-gh-deploy-${count.index}"
  resource_group_name = azurerm_resource_group.main.name
  parent_id           = azurerm_user_assigned_identity.ci_deploy[0].id
  audience            = ["api://AzureADTokenExchange"]
  issuer              = "https://token.actions.githubusercontent.com"
  subject             = local.branch_subjects[count.index]
}

resource "azurerm_role_assignment" "ci_deploy_acr_push" {
  count                = local.ci_enabled ? 1 : 0
  scope                = azurerm_container_registry.main.id
  role_definition_name = "AcrPush"
  principal_id         = azurerm_user_assigned_identity.ci_deploy[0].principal_id
}

resource "azurerm_role_assignment" "ci_deploy_aks_user" {
  count                = local.ci_enabled ? 1 : 0
  scope                = azurerm_kubernetes_cluster.main.id
  role_definition_name = "Azure Kubernetes Service Cluster User Role"
  principal_id         = azurerm_user_assigned_identity.ci_deploy[0].principal_id
}

resource "azurerm_role_assignment" "ci_deploy_aks_writer" {
  count                = local.ci_enabled ? 1 : 0
  scope                = azurerm_kubernetes_cluster.main.id
  role_definition_name = "Azure Kubernetes Service RBAC Writer"
  principal_id         = azurerm_user_assigned_identity.ci_deploy[0].principal_id
}

# Terraform en CI (plan en PR, apply en rama protegida cuando cambia infra/).
resource "azurerm_user_assigned_identity" "ci_terraform" {
  count               = local.ci_enabled ? 1 : 0
  name                = "id-gh-terraform-${var.suffix}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags
}

resource "azurerm_federated_identity_credential" "ci_terraform" {
  count               = local.ci_enabled ? length(local.branch_subjects) : 0
  name                = "fic-gh-terraform-${count.index}"
  resource_group_name = azurerm_resource_group.main.name
  parent_id           = azurerm_user_assigned_identity.ci_terraform[0].id
  audience            = ["api://AzureADTokenExchange"]
  issuer              = "https://token.actions.githubusercontent.com"
  subject             = local.branch_subjects[count.index]
}

resource "azurerm_federated_identity_credential" "ci_terraform_pr" {
  count               = local.ci_enabled ? 1 : 0
  name                = "fic-gh-terraform-pr"
  resource_group_name = azurerm_resource_group.main.name
  parent_id           = azurerm_user_assigned_identity.ci_terraform[0].id
  audience            = ["api://AzureADTokenExchange"]
  issuer              = "https://token.actions.githubusercontent.com"
  subject             = "repo:${var.github_repository}:pull_request"
}

resource "azurerm_role_assignment" "ci_terraform_contributor" {
  count                = local.ci_enabled ? 1 : 0
  scope                = azurerm_resource_group.main.id
  role_definition_name = "Contributor"
  principal_id         = azurerm_user_assigned_identity.ci_terraform[0].principal_id
}

# Terraform crea role assignments: necesita administrar RBAC en el grupo.
resource "azurerm_role_assignment" "ci_terraform_rbac_admin" {
  count                = local.ci_enabled ? 1 : 0
  scope                = azurerm_resource_group.main.id
  role_definition_name = "Role Based Access Control Administrator"
  principal_id         = azurerm_user_assigned_identity.ci_terraform[0].principal_id
}

resource "azurerm_role_assignment" "ci_terraform_kv_officer" {
  count                = local.ci_enabled ? 1 : 0
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = azurerm_user_assigned_identity.ci_terraform[0].principal_id
}
