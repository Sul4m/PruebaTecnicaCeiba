---
title: 'Story 3.4: Exponer el Asistente Clínico en Azure'
type: 'feature'
created: '2026-11-08'
status: 'done'
route: 'dispatch'
context:
  - '{project-root}/docs/adr/0004-aks-terraform-y-despliegue.md'
  - '{project-root}/docs/adr/0007-proyeccion-redis-de-urgencias.md'
  - '{project-root}/docs/adr/0008-autenticacion-api-key-por-servicio.md'
---

> **NO VALIDADO CONTRA AZURE REAL.** El código está escrito pero no aplicado. Solo se ejecutaron `terraform fmt/validate/plan` (10 recursos a crear, 3 cambios de deriva menor ya existente, 1 reemplazo de la asignación RBAC de la identidad de apply), el render de los manifests (YAML válido, sin variables sin sustituir) y los tests del Asistente. La historia no pasa a `done` hasta aplicar y pasar la puerta E2E. Detalle y orden de pasos en `docs/despliegue-azure.md` (sección "Story 3.4 y 4.4").

## Intent

El Asistente accesible por HTTPS en el mismo host que Triage (`/asistente`), consumiendo `triaje.eventos` de Event Hubs, con Redis gestionado (TLS + auth) y Azure OpenAI (`gpt-5.4-mini`, `text-embedding-3-small`) para la parte de IA de la 4.4, sin secretos en Git y minimizando los del estado.

## Entregado

- Terraform: `redis.tf` (Azure Managed Redis `Balanced_B0`, TLS, access key), `openai.tf` (cuenta OpenAI con claves locales desactivadas, 2 deployments, rol `Cognitive Services OpenAI User` para la identidad del Asistente), consumer group en `triaje.eventos`, `asistente` en `local.workloads`, rol OpenAI añadido a los roles asignables por la condición ABAC del apply.
- `scripts/bootstrap-secrets.sh`: `asistente-api-key`, `asistente-eventhubs-connection-string` (SAS propia), `redis-password`. `scripts/render-k8s.sh`: variables nuevas.
- `k8s/asistente/`: Namespace, ServiceAccount, ConfigMap, SecretProviderClass, Service, Ingress (host compartido, TLS propio), Deployment (1 réplica, requests 100m/384Mi).
- App: `application-azure.yaml` (SASL_SSL a Event Hubs, Redis TLS+auth sin defaults, OpenAI por Entra con `api-key` vacío, umbral por env, `assistant.projection.create-topics=false`) y `KafkaProjectionConfig` condicional con `KafkaProjectionConfigTest`.
- CI/CD: `triage.yml` (ahora "deploy") prueba ambos servicios, valida ambos manifests, construye/publica ambas imágenes con tag SHA inmutable y aplica ambos, con la misma identidad `id-gh-deploy` (sin ampliar privilegios; la identidad de PR no se toca).

## Decisiones

- Entra ID para Azure OpenAI (sin clave en ningún lado); access key para Redis (Lettuce no soporta Entra sin librería); SAS por servicio para Event Hubs (diferido existente).
- El PRIMER apply de 3.4 lo hace el administrador humano (Owner) manualmente: cambia la condición ABAC y reemplaza la asignación RBAC Administrator de la identidad de CI, que no puede reescribirse a sí misma. Después el CI ya puede aplicar. Sin ampliar privilegios.
- Ingress del Asistente con TLS propio (`asistente-tls`, cert-manager): ingress-nginx lee el secreto en el namespace del Ingress, por lo que no se puede reutilizar `triage-tls`.
- Deployment: límite 768Mi, requests 100m/384Mi, `MaxRAMPercentage=50`.

## Pendiente para `done`

Aplicar Terraform y `bootstrap-secrets.sh`, crear el namespace, desplegar, y verificar con curl: `GET /asistente/estado-urgencias` refleja un paciente registrado en Triage vía Event Hubs, SSE de Triage sin cortes, las tres consultas del baseline responden (4.4) y calibrar el umbral de similitud.

## Diferidos que toca esta historia

- Cerrados en código: Redis auth+TLS exigidos en el perfil `azure`; `SPRING_PROFILES_ACTIVE=azure` en el ConfigMap; topics por Terraform; consumer group por Terraform.
- Abiertos: hilos/cuotas por API key del chat; calibrar umbral con embeddings reales (tras el despliegue); roles Data Receiver con Workload Identity para Event Hubs; vault/secretos por servicio.
