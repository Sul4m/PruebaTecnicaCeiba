# Despliegue de Triage en Azure (Story 1.6; ampliado en 3.4, ver la última sección)

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

## Lecciones del primer despliegue real (2026-11-08)

- **Suscripciones nuevas restringen tamaños:** AKS solo aceptó tamaños v5/v6/v7 y B v2, y la cuota inicial de varias familias es 0. Se usó `Standard_D2s_v6` (cuota de `StandardDsv6Family`: 10). PostgreSQL Burstable no se aprobó en `eastus2`; se usó `GP_Standard_D2ds_v4` en la zona 2 (la zona 1 no está disponible). Valores en `variables.tf`.
- **Un solo nodo de 2 vCPU no alcanza** (add-ons + cert-manager + ingress + Triage): `aks_node_count` vale 2.
- **Contraseña de PostgreSQL efímera:** si un `apply` falla a medias y se repite, el secreto `db-password` de Key Vault y el servidor pueden quedar con valores distintos (cada ejecución genera una nueva). Síntoma: `password authentication failed`. Arreglo: fijar la misma contraseña en ambos (`az postgres flexible-server update --admin-password` y `az keyvault secret set --name db-password`) y reiniciar el pod. Pendiente: hacerlo idempotente.
- **Imagen:** `az acr build` funciona en la suscripción (evita el problema arm64/amd64 de construir en Mac).
- **ACME:** el `ClusterIssuer` se aplicó sin `email` (opcional en Let's Encrypt) para no enviar un correo personal.
- **kubelogin:** instalar el de Azure (`az aks install-cli`), no el paquete homónimo de Homebrew (otro programa).
- **Verificado:** HTTPS con certificado emitido, 401 sin API key, 404 para id inexistente, `POST /triage/pacientes` (E1) → 201 `CRITICO` SLA 5, SSE 200 sin cortes hasta el heartbeat (~15 s), productor Kafka conectado a Event Hubs con idempotencia.

## Story 3.4 y 4.4 (infraestructura de IA): Asistente Clínico en Azure

> **Estado: código listo, NO validado contra Azure real.** Solo `terraform fmt/validate/plan` (sin apply), render de manifests y los tests del Asistente. Nada de esta sección se ha aplicado.

Qué añade: Azure Managed Redis (`redis.tf`), cuenta Azure OpenAI con los deployments `gpt-5.4-mini` y `text-embedding-3-small` (`openai.tf`), identidad/federación/rol de KV `asistente` (en `local.workloads`), consumer group en `triaje.eventos`, `k8s/asistente/` y el pipeline para ambas imágenes.

### Orden de pasos para aplicar

1. **Terraform (administrador, a mano; no por CI)**: el plan cambia la condición ABAC de `ci_terraform_apply_rbac_admin` (añade el rol `Cognitive Services OpenAI User`, que el apply de CI necesita para asignarlo), lo que **reemplaza** esa asignación de la propia identidad de apply; un apply desde CI no puede modificarse a sí mismo. Por eso el primer apply de 3.4 lo hace el administrador humano:
   ```bash
   cd infra/terraform && terraform init -backend-config=backend.hcl && terraform plan -out=tfplan && terraform apply tfplan
   ```
   Crea (plan de referencia: 10 a crear, 3 modificaciones menores de deriva ya existente, 1 reemplazo): cuenta OpenAI + 2 deployments, Managed Redis, consumer group, identidad `id-asistente`, su federated credential, rol KV y rol OpenAI. Los deployments y Redis tardan varios minutos.
2. **Secretos** (reejecutable; conserva las API keys existentes):
   ```bash
   scripts/bootstrap-secrets.sh $(terraform -chdir=infra/terraform output -raw resource_group) \
     $(terraform -chdir=infra/terraform output -raw key_vault_name) \
     $(terraform -chdir=infra/terraform output -raw eventhubs_namespace) \
     $(terraform -chdir=infra/terraform output -raw redis_name)
   ```
   Escribe `asistente-api-key`, `asistente-eventhubs-connection-string` (regla SAS propia `asistente-app`, Listen+Send: consume y publica al DLT) y `redis-password`.
3. **Namespace (una vez, administrador)**: `kubectl apply -f k8s/asistente/namespace.yaml`.
4. **Variables de GitHub Actions** nuevas: `ASISTENTE_WORKLOAD_CLIENT_ID` (`asistente_workload_client_id`), `REDIS_HOST` (`redis_host`), `AZURE_OPENAI_ENDPOINT` (`openai_endpoint`); opcional `ASISTENTE_RAG_THRESHOLD` (defecto 0,45).
5. **Despliegue**: push a `master` (workflow `triage.yml`, ahora "deploy") construye y publica `triage-service:<SHA>` y `clinical-assistant-service:<SHA>` (tags inmutables) y aplica ambos manifests con la misma identidad `id-gh-deploy` (sin ampliar privilegios: los roles de AKS ya son de clúster). Primer despliegue a mano: `az acr build` de cada servicio, `scripts/render-k8s.sh <out> k8s/asistente` (con `IMAGE` y las variables) y `kubectl apply`.

### Verificación (sin cambiar la API de Triage)

```bash
HOST=$(terraform -chdir=infra/terraform output -raw ingress_host)
AKEY=$(az keyvault secret show --vault-name <kv> --name asistente-api-key --query value -o tsv)
curl -i https://$HOST/asistente/estado-urgencias -H "X-API-Key: $AKEY"      # 200, conteos por nivel
# registrar un paciente en Triage (ver arriba) y repetir: el conteo del nivel sube (vía Event Hubs)
curl -N https://$HOST/triage/alertas/stream -H "X-API-Key: $TKEY"            # sigue sin cortarse
curl -i -X POST https://$HOST/asistente/consulta -H "X-API-Key: $AKEY" -H 'Content-Type: application/json' -d '<consulta del baseline>'
kubectl -n asistente logs deploy/asistente | head   # sin errores de Redis/Kafka/OpenAI
```

### Decisiones de autenticación

| Recurso | Mecanismo | Por qué |
|---|---|---|
| Azure OpenAI | **Entra ID** (Workload Identity + rol `Cognitive Services OpenAI User` sobre la cuenta); `local_auth_enabled = false` | Spring AI 1.0.9 usa `DefaultAzureCredential` cuando `api-key` está vacío (verificado en el bytecode del autoconfigure). No existe clave: ni en estado, ni en Key Vault, ni en el clúster. Requiere subdominio propio (`custom_subdomain_name`). |
| Redis | **Access key** → Key Vault (`redis-password`, escrita por script) + TLS 10000 | Lettuce/Spring Data Redis no hace Entra sin librería adicional. Honesto: `azurerm_managed_redis` guarda `primary_access_key` en el estado (sensible); Terraform no lo expone ni lo escribe en Key Vault. |
| Event Hubs | SAS por servicio (`asistente-app`) → Key Vault | Igual que Triage; el diferido a roles Data Receiver/Sender con OAUTHBEARER sigue abierto. |
| API del Asistente | API key propia (`asistente-api-key`) por script | Distinta de la de Triage (ADR-0008). |

Notas: la identidad `id-asistente` y la de Triage tienen `Key Vault Secrets User` sobre todo el vault (patrón existente), así que cada una puede leer los secretos de la otra; endurecer con un vault por servicio o asignaciones por secreto queda como diferido. El perfil `azure` del Asistente exige `REDIS_HOST` y `REDIS_PASSWORD` (sin valores por defecto, TLS siempre activo) y desactiva la creación de topics (`assistant.projection.create-topics=false`; los crea Terraform).

### Ingress y SSE

El Ingress del Asistente (namespace `asistente`) usa el mismo `nginx-triage` y host, path `/asistente`, **sin sección `tls`**: ingress-nginx fusiona los Ingress del mismo host y reutiliza el certificado `triage-tls` (no se pide otro certificado). El Ingress de Triage no se toca, así que `/triage/alertas/stream` conserva `proxy-buffering: off` y timeouts de 3600 s (annotations por location). Supuesto sin verificar: que la fusión por host reutilice el certificado como se espera; alternativa si falla: añadir `tls` + `cert-manager.io/cluster-issuer` al Ingress del Asistente.

### Capacidad del clúster

Triage pide 250m CPU/512Mi; el Asistente 100m CPU/384Mi (límites 500m/640Mi), `replicas: 1`, `Recreate`. Con 2 nodos de 2 vCPU debería programarse en el segundo nodo; si queda `Pending`, revisar `kubectl describe pod` y reducir requests.

### Costos estimados adicionales (ESTIMACIONES, verificar en la calculadora de Azure)

| Concepto | USD/mes aprox. |
|---|---|
| Azure Managed Redis `Balanced_B0` (sin HA) | 15-20 |
| Azure OpenAI (pago por token; demo con unas decenas de consultas + embeddings del corpus) | <5 |
| Consumer group / identidades / roles | 0 |
| Segundo nodo AKS `D2s_v6` (ya existe, antes no contado) | ~70 |

El total real del entorno (2 nodos D2s_v6, PostgreSQL `GP_Standard_D2ds_v4`, Event Hubs, ACR, IP/LB, Redis, OpenAI) ronda **300-350 USD/mes (~10-12 USD/día)**; la cifra de 120-140 USD de la sección anterior corresponde al diseño original de 1 nodo B2ms y PostgreSQL Burstable. Eliminar al terminar con `scripts/destroy-azure.sh`.

### Qué NO se pudo validar

- `terraform apply`: cuota/disponibilidad de `Balanced_B0` en `eastus2`, de la capacidad de los deployments y de `GlobalStandard` en la suscripción; que `azurerm_managed_redis` acepte `EnterpriseCluster` con `high_availability_enabled = false`; versión `2026-03-17` de gpt-5.4-mini (tomada de `az cognitiveservices model list`).
- `az redisenterprise database list-keys` (nombre de comando/forma de la salida para Managed Redis) en `bootstrap-secrets.sh`.
- Conexión TLS+auth a Redis desde Lettuce, SASL_SSL a Event Hubs del consumidor, token Entra contra OpenAI desde el pod y los timeouts del cliente Azure.
- Reutilización del certificado en el Ingress del Asistente y que el pod programe junto a Triage.
- Umbral de similitud (0,45) sin calibrar con `text-embedding-3-small` real.
- El workflow ampliado (no ejecutado).
