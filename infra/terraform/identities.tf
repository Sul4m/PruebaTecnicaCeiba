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
# Separación de privilegios:
#   ci_deploy          -> subject environment:azure        : AcrPush + AKS (despliegue de apps)
#   ci_terraform_apply -> subject environment:azure-infra  : Contributor + RBAC Admin (con condición) + KV Officer
#   ci_terraform_plan  -> subject pull_request             : SOLO LECTURA (Reader + lectura del estado)
# Los environments de GitHub llevan reglas de aprobación/ramas protegidas; una PR no puede asumir
# las identidades con escritura.

locals {
  ci_enabled   = var.github_repository != ""
  state_scoped = local.ci_enabled && var.tfstate_storage_account_id != ""
  gh_issuer    = "https://token.actions.githubusercontent.com"
  gh_audience  = ["api://AzureADTokenExchange"]
}

data "azurerm_role_definition" "assignable" {
  for_each = toset([
    "AcrPull",
    "AcrPush",
    "Key Vault Secrets Officer",
    "Key Vault Secrets User",
    "Azure Kubernetes Service Cluster User Role",
    "Azure Kubernetes Service RBAC Writer",
    "Azure Kubernetes Service RBAC Cluster Admin",
  ])
  name  = each.value
  scope = "/subscriptions/${var.subscription_id}"
}

locals {
  # Condición ABAC: el RBAC Administrator solo puede crear/borrar asignaciones de estos roles
  # (nunca Owner, Contributor ni User Access Administrator).
  assignable_role_ids = join(",", [for r in data.azurerm_role_definition.assignable : r.role_definition_id])
  rbac_admin_condition = join(" AND ", [
    "((!(ActionMatches{'Microsoft.Authorization/roleAssignments/write'})) OR (@Request[Microsoft.Authorization/roleAssignments:RoleDefinitionId] ForAnyOfAnyValues:GuidEquals {${local.assignable_role_ids}}))",
    "((!(ActionMatches{'Microsoft.Authorization/roleAssignments/delete'})) OR (@Resource[Microsoft.Authorization/roleAssignments:RoleDefinitionId] ForAnyOfAnyValues:GuidEquals {${local.assignable_role_ids}}))",
  ])
}

# ---------- Despliegue de aplicaciones ----------

resource "azurerm_user_assigned_identity" "ci_deploy" {
  count               = local.ci_enabled ? 1 : 0
  name                = "id-gh-deploy-${var.suffix}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags
}

resource "azurerm_federated_identity_credential" "ci_deploy" {
  count               = local.ci_enabled ? 1 : 0
  name                = "fic-gh-deploy-env-azure"
  resource_group_name = azurerm_resource_group.main.name
  parent_id           = azurerm_user_assigned_identity.ci_deploy[0].id
  audience            = local.gh_audience
  issuer              = local.gh_issuer
  subject             = "repo:${var.github_repository}:environment:azure"
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

# ---------- Terraform: APPLY (escritura, solo environment azure-infra) ----------

resource "azurerm_user_assigned_identity" "ci_terraform_apply" {
  count               = local.ci_enabled ? 1 : 0
  name                = "id-gh-tf-apply-${var.suffix}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags
}

resource "azurerm_federated_identity_credential" "ci_terraform_apply" {
  count               = local.ci_enabled ? 1 : 0
  name                = "fic-gh-tf-apply-env-azure-infra"
  resource_group_name = azurerm_resource_group.main.name
  parent_id           = azurerm_user_assigned_identity.ci_terraform_apply[0].id
  audience            = local.gh_audience
  issuer              = local.gh_issuer
  subject             = "repo:${var.github_repository}:environment:azure-infra"
}

resource "azurerm_role_assignment" "ci_terraform_apply_contributor" {
  count                = local.ci_enabled ? 1 : 0
  scope                = azurerm_resource_group.main.id
  role_definition_name = "Contributor"
  principal_id         = azurerm_user_assigned_identity.ci_terraform_apply[0].principal_id
}

# Terraform crea role assignments; la condición ABAC limita QUÉ roles puede asignar.
resource "azurerm_role_assignment" "ci_terraform_apply_rbac_admin" {
  count                = local.ci_enabled ? 1 : 0
  scope                = azurerm_resource_group.main.id
  role_definition_name = "Role Based Access Control Administrator"
  principal_id         = azurerm_user_assigned_identity.ci_terraform_apply[0].principal_id
  condition_version    = "2.0"
  condition            = local.rbac_admin_condition
}

resource "azurerm_role_assignment" "ci_terraform_apply_kv_officer" {
  count                = local.ci_enabled ? 1 : 0
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = azurerm_user_assigned_identity.ci_terraform_apply[0].principal_id
}

# Estado remoto (cuenta en otro grupo): blobs lectura/escritura + Reader para refrescar las asignaciones.
resource "azurerm_role_assignment" "ci_terraform_apply_state_blob" {
  count                = local.state_scoped ? 1 : 0
  scope                = var.tfstate_storage_account_id
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = azurerm_user_assigned_identity.ci_terraform_apply[0].principal_id
}

resource "azurerm_role_assignment" "ci_terraform_apply_state_reader" {
  count                = local.state_scoped ? 1 : 0
  scope                = var.tfstate_storage_account_id
  role_definition_name = "Reader"
  principal_id         = azurerm_user_assigned_identity.ci_terraform_apply[0].principal_id
}

# ---------- Terraform: PLAN (SOLO LECTURA, subject pull_request) ----------

resource "azurerm_user_assigned_identity" "ci_terraform_plan" {
  count               = local.ci_enabled ? 1 : 0
  name                = "id-gh-tf-plan-${var.suffix}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags
}

resource "azurerm_federated_identity_credential" "ci_terraform_plan" {
  count               = local.ci_enabled ? 1 : 0
  name                = "fic-gh-tf-plan-pr"
  resource_group_name = azurerm_resource_group.main.name
  parent_id           = azurerm_user_assigned_identity.ci_terraform_plan[0].id
  audience            = local.gh_audience
  issuer              = local.gh_issuer
  subject             = "repo:${var.github_repository}:pull_request"
}

resource "azurerm_role_assignment" "ci_terraform_plan_reader" {
  count                = local.ci_enabled ? 1 : 0
  scope                = azurerm_resource_group.main.id
  role_definition_name = "Reader"
  principal_id         = azurerm_user_assigned_identity.ci_terraform_plan[0].principal_id
}

resource "azurerm_role_assignment" "ci_terraform_plan_state_blob" {
  count                = local.state_scoped ? 1 : 0
  scope                = var.tfstate_storage_account_id
  role_definition_name = "Storage Blob Data Reader"
  principal_id         = azurerm_user_assigned_identity.ci_terraform_plan[0].principal_id
}

resource "azurerm_role_assignment" "ci_terraform_plan_state_reader" {
  count                = local.state_scoped ? 1 : 0
  scope                = var.tfstate_storage_account_id
  role_definition_name = "Reader"
  principal_id         = azurerm_user_assigned_identity.ci_terraform_plan[0].principal_id
}
