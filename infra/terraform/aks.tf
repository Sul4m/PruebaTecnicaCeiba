resource "azurerm_kubernetes_cluster" "main" {
  name                = "aks-ptc-${var.suffix}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  dns_prefix          = "aks-ptc-${var.suffix}"
  sku_tier            = "Free"
  tags                = local.tags

  oidc_issuer_enabled       = true
  workload_identity_enabled = true

  # Entra ID + Azure RBAC para Kubernetes: el CI se autentica con su identidad OIDC, sin kubeconfig estático.
  azure_active_directory_role_based_access_control {
    azure_rbac_enabled = true
    tenant_id          = data.azurerm_client_config.current.tenant_id
  }

  default_node_pool {
    name            = "system"
    node_count      = var.aks_node_count
    vm_size         = var.aks_node_vm_size
    vnet_subnet_id  = azurerm_subnet.aks.id
    os_disk_size_gb = 64
  }

  identity {
    type = "SystemAssigned"
  }

  network_profile {
    network_plugin      = "azure"
    network_plugin_mode = "overlay"
    load_balancer_sku   = "standard"
    service_cidr        = "10.20.0.0/16"
    dns_service_ip      = "10.20.0.10"
  }

  # Driver CSI de Key Vault (SecretProviderClass).
  key_vault_secrets_provider {
    secret_rotation_enabled = true
  }

  # Controlador de ingress NGINX gestionado (app routing). Sin zonas DNS de Azure: se usa la
  # etiqueta DNS de la IP pública (ver k8s/platform).
  web_app_routing {
    dns_zone_ids = []
  }
}

# El kubelet descarga imágenes de ACR.
resource "azurerm_role_assignment" "kubelet_acr_pull" {
  scope                = azurerm_container_registry.main.id
  role_definition_name = "AcrPull"
  principal_id         = azurerm_kubernetes_cluster.main.kubelet_identity[0].object_id
}

# El administrador humano administra el clúster (crear namespace, instalar cert-manager).
resource "azurerm_role_assignment" "deployer_aks_admin" {
  scope                = azurerm_kubernetes_cluster.main.id
  role_definition_name = "Azure Kubernetes Service RBAC Cluster Admin"
  principal_id         = var.admin_object_id
}
