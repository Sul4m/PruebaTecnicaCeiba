---
title: 'Story 1.6: Exponer Triage en Azure'
type: 'feature'
created: '2026-11-08'
status: 'review'
route: 'dispatch'
context:
  - '{project-root}/docs/adr/0004-aks-terraform-y-despliegue.md'
  - '{project-root}/docs/adr/0005-outbox-y-contrato-kafka.md'
---

> **NO VALIDADO CONTRA AZURE REAL.** El código está escrito pero no aplicado (no había suscripción activa). Solo se ejecutaron `terraform fmt/init/validate` y las pruebas de Triage. La historia no pasa a `done` hasta desplegar y pasar la puerta E2E. Detalle en `docs/despliegue-azure.md`.

## Intent

Triage accesible por HTTPS en AKS, con infraestructura en Terraform, manifests versionados y pipeline OIDC, sin secretos en Git ni (donde es evitable) en el estado.

## Entregado

- `infra/terraform/`: RG + tag, VNet, AKS Free (OIDC + workload identity, Entra RBAC, CSI KV, app routing), ACR, Key Vault RBAC, PostgreSQL Flexible privado, Event Hubs Standard con `triaje.eventos` y `triaje.eventos.dlt`, identidades de workload (mapa `local.workloads`, extensible para 3.4) e identidades OIDC de CI. Contraseña de BD efímera + write-only.
- `k8s/triage/` (Deployment `replicas: 1` + `Recreate`, Service, ServiceAccount, ConfigMap, SecretProviderClass, Ingress SSE-safe) y `k8s/platform/` (ingress controller, ClusterIssuer).
- App: perfil `azure` (SASL_SSL, SSL en R2DBC/Flyway) y `triage.kafka.create-topics` (defecto true) con `KafkaTopicsConfigTest`.
- `.github/workflows/triage.yml` e `infra.yml`; `scripts/` (bootstrap, render, `destroy-azure.sh.example`; `destroy-azure.sh` ignorado).
- Diferidos cerrados en código: relay único (replicas 1), topics por Terraform/parametrizable. Binding env de SLA: NO verificado, se evita (defaults).

## Pendiente para `done`

Aplicar Terraform, ejecutar `bootstrap-secrets.sh`, desplegar, y verificar con curl: POST /triage/pacientes por HTTPS con API key, evento en Event Hubs, SSE sin cortes, Flyway con TLS, productor idempotente contra Event Hubs.

## Review Triage Log

Ronda 1 (parches aplicados, aun sin validar contra Azure real; solo `terraform fmt/validate`):
- P1: identidad de plan en PR solo lectura (`id-gh-tf-plan`), separada de la de apply (`id-gh-tf-apply`, solo `environment:azure-infra`); RBAC Administrator con condicion ABAC sobre roles asignables.
- P2: federated credentials por `environment:azure` (deploy) y `environment:azure-infra` (apply); `pull_request` solo para plan. Los workflows usan esos environments.
- P3: Storage Blob Data Contributor (apply) / Reader (plan) sobre la cuenta del estado, mas Reader para refrescar asignaciones.
- P4: `admin_object_id` explicito en lugar del caller actual.
- P5: `triage.yml` omite build+push si el tag SHA ya existe en ACR. P6: `EXPECTED_SUBSCRIPTION_ID` obligatorio en el script de destruccion. P7: `vars.*` pasados por `env:` en los `run:`.

Diferido (ver `deferred-work.md`): SAS de namespace -> roles Data Sender/Receiver con Workload Identity; usuario de BD de minimo privilegio y `sslmode=verify-full`; checksum de kubeconform.
