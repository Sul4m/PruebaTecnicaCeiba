# Despliegue de Triage en Azure (Story 1.6)

> **Estado: código listo, NO validado contra Azure real.** Solo se ejecutaron `terraform fmt/init/validate` y las pruebas de Triage. Nada de lo siguiente se ha aplicado todavía.

Resumen: Terraform crea el grupo `rg-pruebatecnicaceiba-<sufijo>` (tag `project=PruebaTecnicaCeiba`) con VNet, AKS (Free, 1 nodo), ACR, Key Vault (RBAC), PostgreSQL Flexible Server (privado), Event Hubs Standard (`triaje.eventos` y `triaje.eventos.dlt`) e identidades. La imagen y los manifests los despliega el pipeline (ADR-0004).

## Requisitos locales

`az` (Azure CLI), `terraform >= 1.11`, `kubectl`, `kubelogin`, `helm`, `envsubst`, `openssl`. Un rol Owner (o Contributor + User Access Administrator) en la suscripción.

## Pasos

```bash
# 1. Sesión
az login
az account set --subscription <SUBSCRIPTION_ID>

# 2. Estado remoto de Terraform (grupo aparte, mismo tag). Imprime el contenido de backend.hcl
scripts/bootstrap-tfstate.sh <sufijo> <location>
cp infra/terraform/backend.hcl.example infra/terraform/backend.hcl   # completar con lo impreso
cp infra/terraform/terraform.tfvars.example infra/terraform/terraform.tfvars   # editar

# 3. Infraestructura
cd infra/terraform
terraform init -backend-config=backend.hcl
terraform plan -out=tfplan
terraform apply tfplan
terraform output          # valores para los pasos siguientes y las variables de GitHub

# 4. Secretos que Terraform no gestiona (api key + connection string de Event Hubs)
cd ../..
scripts/bootstrap-secrets.sh $(terraform -chdir=infra/terraform output -raw resource_group) \
  $(terraform -chdir=infra/terraform output -raw key_vault_name) \
  $(terraform -chdir=infra/terraform output -raw eventhubs_namespace)

# 5. Acceso al clúster y paso único de plataforma (administrador)
az aks get-credentials -g <rg> -n <aks_name> && kubelogin convert-kubeconfig -l azurecli
kubectl apply -f k8s/triage/namespace.yaml
helm repo add jetstack https://charts.jetstack.io
helm install cert-manager jetstack/cert-manager -n cert-manager --create-namespace \
  --version <versión estable fijada> --set crds.enabled=true
export INGRESS_DNS_LABEL=$(terraform -chdir=infra/terraform output -raw ingress_dns_label) ACME_EMAIL=<correo>
scripts/render-k8s.sh /tmp/platform k8s/platform && kubectl apply -f /tmp/platform
```

6. **CI**: en `terraform.tfvars` poner `admin_object_id` (`az ad signed-in-user show --query id -o tsv`; el admin humano es explícito para que un apply desde CI no reemplace sus roles), `github_repository` y `tfstate_storage_account_id` (lo imprime `bootstrap-tfstate.sh`). En GitHub crear los environments y las variables de Actions (todas no secretas, salen de `terraform output`/de los scripts):

   | Environment | Quién lo usa | Reglas recomendadas |
   |---|---|---|
   | `azure` | job `deploy` de `triage.yml` (subject `repo:<owner>/<repo>:environment:azure`) | solo rama `master` |
   | `azure-infra` | job `apply` de `infra.yml` (subject `...:environment:azure-infra`) | solo `master` + revisor obligatorio |

   | Variable | Valor |
   |---|---|
   | `AZURE_CLIENT_ID` | `ci_deploy_client_id` |
   | `AZURE_TF_APPLY_CLIENT_ID` / `AZURE_TF_PLAN_CLIENT_ID` | `ci_terraform_apply_client_id` / `ci_terraform_plan_client_id` |
   | `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, `AZURE_LOCATION`, `SUFFIX` | los de `terraform.tfvars` / `terraform output` |
   | `ADMIN_OBJECT_ID` | el mismo `admin_object_id` |
   | `TFSTATE_RG`, `TFSTATE_STORAGE_ACCOUNT`, `TFSTATE_STORAGE_ACCOUNT_ID` | salida de `bootstrap-tfstate.sh` |
   | `ACR_NAME`, `AKS_NAME`, `RESOURCE_GROUP`, `KV_NAME`, `TRIAGE_WORKLOAD_CLIENT_ID`, `DB_HOST`, `DB_USER`, `KAFKA_BOOTSTRAP_SERVERS`, `INGRESS_HOST` | `terraform output` |

   **Flujo de identidades (mínimo privilegio):** (a) `id-gh-tf-plan` -> subject `pull_request`, **solo lectura** (Reader en el grupo y en la cuenta del estado + Storage Blob Data Reader; el plan corre con `-lock=false -refresh=false`, así que es menos exacto y no refresca secretos de Key Vault); (b) `id-gh-tf-apply` -> solo `environment:azure-infra`: Contributor y RBAC Administrator en el grupo **con condición ABAC** que limita los roles asignables a una lista cerrada (AcrPull/Push, KV Secrets Officer/User, roles RBAC de AKS; nunca Owner/Contributor/UAA), KV Secrets Officer y Storage Blob Data Contributor sobre el estado; (c) `id-gh-deploy` -> solo `environment:azure`: AcrPush y roles de AKS. Una PR (también de rama) nunca puede asumir (b) ni (c). Los roles sobre la cuenta del estado (otro grupo) los crea el primer apply manual. Un push a `master` que toque `triage-service/` o `k8s/` construye, publica `triage-service:<SHA>` en ACR y aplica. Para el primer despliegue sin esperar al CI, ejecutar los mismos pasos a mano (`az acr build`/`docker push`, `scripts/render-k8s.sh` y `kubectl apply`).
7. **URL**: el host es `terraform output ingress_host` (`<label>.<location>.cloudapp.azure.com`); no requiere dominio propio. Let's Encrypt emite el certificado por HTTP-01 (puede tardar unos minutos: `kubectl -n triage get certificate`).

## Verificación

```bash
HOST=$(terraform -chdir=infra/terraform output -raw ingress_host)
KEY=$(az keyvault secret show --vault-name <kv> --name triage-api-key --query value -o tsv)
curl -i https://$HOST/triage/pacientes/00000000-0000-0000-0000-000000000000 -H "X-API-Key: $KEY"   # 404 esperado (no 401/5xx)
curl -i -X POST https://$HOST/triage/pacientes -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  -d '<cuerpo del caso E1 del baseline>'                    # 201
curl -N https://$HOST/triage/alertas/stream -H "X-API-Key: $KEY"   # debe mostrar heartbeats sin cortarse
```

Evento en Event Hubs: Portal > Event Hubs > `triaje.eventos` > Metrics (Incoming Messages) o Data Explorer.

## Secretos: qué queda dónde

| Secreto | Origen | En el estado de Terraform |
|---|---|---|
| Contraseña PostgreSQL | `ephemeral "random_password"` -> atributos write-only (`administrator_password_wo`, `value_wo`) | **No** (ni plan ni estado) |
| `triage-api-key` | `scripts/bootstrap-secrets.sh` (openssl) -> Key Vault | No (Terraform no lo conoce) |
| Connection string Event Hubs | Regla SAS creada por el script -> Key Vault | No |

Inevitable / honesto: (1) el nombre del vault y los IDs de identidades están en el estado (no son secretos); (2) en el clúster, `secretObjects` del CSI copia los secretos a un `Secret` de Kubernetes (en etcd de AKS, cifrado por la plataforma) para inyectarlos como variables de entorno; (3) el estado remoto sigue siendo sensible (revela topología) y su acceso se restringe por Entra ID (sin claves compartidas); (4) el azurerm de Event Hubs/Postgres puede cambiar qué atributos guarda: revisar `terraform state show` tras el primer apply. Rotación de la contraseña: subir `db_password_version`.

## Decisiones y supuestos

- **Event Hubs en vez de Kafka**: la API Kafka no crea tópicos, por eso Terraform crea los event hubs y el perfil `azure` fija `triage.kafka.create-topics=false`. Auth SASL_SSL/PLAIN con connection string (`$ConnectionString`). La entrega sigue `acks=all` + idempotencia (el tier Standard soporta productor idempotente; las transacciones Kafka no, y no se usan).
- **PostgreSQL privado** (VNet + DNS privado, sin IP pública); TLS exigido en R2DBC (`sslMode=require`) y Flyway (`sslmode=require`). `require` cifra pero no valida el certificado.
- **`replicas: 1` + `strategy: Recreate`**: relay del outbox único (deferred-work Story 1.4); evita dos relays durante un rollout.
- **SLA**: se usan los valores por defecto; no se sobreescriben por entorno (binding de `TRIAGE_SLA_MINUTES_URGENTE_2` sin verificar).
- **Ingress**: add-on app routing de AKS con un `NginxIngressController` propio (`nginx-triage`), `proxy-buffering off` y timeouts de 3600 s para SSE. **Aviso:** Microsoft solo da parches críticos de este add-on NGINX hasta noviembre de 2026; para una prueba efímera es aceptable, para producción migrar a Gateway API.
- **Story 3.4**: añadir `"asistente"` a `local.workloads` (identidad + federación + rol KV), un `redis.tf` (Azure Managed Redis) y `k8s/asistente/`; el resto no se reescribe.
- Las acciones de GitHub están fijadas por versión mayor (no por SHA); para endurecer, fijar por SHA.

## Costos estimados (ESTIMACIONES, región y precios varían; verificar en la calculadora de Azure)

Orden de magnitud, 24x7, en USD/mes: AKS Free sin cargo de plano de control + 1 VM B2ms (~60), PostgreSQL B1ms + 32 GB (~15-25), Event Hubs Standard 1 TU (~22), ACR Basic (~5), Key Vault y DNS privado (<2), IP pública y balanceador Standard (~20). Total aproximado **120-140 USD/mes (~4-5 USD/día)**. Se elimina al terminar con `scripts/destroy-azure.sh`.

## Limpieza

```bash
cp scripts/destroy-azure.sh.example scripts/destroy-azure.sh && chmod +x scripts/destroy-azure.sh
scripts/destroy-azure.sh <sufijo>
```

Valida la suscripción activa, el nombre y el tag del grupo, lista los recursos y exige escribir el nombre exacto; luego purga el Key Vault (soft-delete) y repite para el grupo del estado.

## Qué NO se pudo validar sin suscripción

- `terraform plan/apply`: solo `validate` (sintaxis y esquema del proveedor). No se comprobó cuota/región de AKS o PostgreSQL, ni que `Standard_B2ms`/`B_Standard_B1ms` estén disponibles, ni que los nombres globales estén libres.
- Que azurerm 4.x acepte `administrator_password_wo` sin otros efectos, y que `ignore_changes = [zone]` sea suficiente para un plan sin diferencias.
- Los role assignments de CI (en particular que `AKS RBAC Writer` permita crear `SecretProviderClass`; si no, usar `RBAC Admin` acotado al namespace) y la creación de la regla SAS con CLI.
- Los manifests contra un API server (`kubectl --dry-run` necesita un clúster; solo se renderizaron y se comprobó que el YAML parsea), la CRD `NginxIngressController`, la etiqueta DNS del balanceador y la emisión de certificados.
- Conectividad real a Event Hubs (SASL_SSL, idempotencia), a PostgreSQL con TLS, y el stream SSE a través del ingress.
- Los workflows de GitHub Actions (no se ejecutaron).
