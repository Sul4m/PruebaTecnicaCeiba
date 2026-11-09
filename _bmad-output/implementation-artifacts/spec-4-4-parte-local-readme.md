---
title: 'Story 4.4 (parte local) - README y ADR de entrega'
type: 'docs'
created: '2026-11-08'
status: 'done'
---

## Intent

Entregar la parte local (documental) de la Story 4.4: README completo, ADR faltantes y trazabilidad de lo pendiente. La story permanece `in-progress` hasta cerrar la parte Azure.

## Hecho (parte local)

- [x] README: comando local (`cp .env.example .env` + `docker compose up --build`), variables requeridas, diagrama Mermaid, tecnologías, decisiones (ADR), formato de eventos Kafka, decisión RF-06, supuestos/ambigüedades, pruebas, ejemplos curl de ambos servicios, URLs de Azure como "pendiente de despliegue".
- [x] ADR-0006 (resiliencia LLM, Story 4.3), ADR-0007 (proyección Redis, Story 3.3), ADR-0008 (API key por servicio). Con los 5 existentes suman 8 (mínimo exigido: 2).

## Pendiente (parte Azure)

- Terraform: deployments `gpt-5.4-mini` y `text-embedding-3-small` y su clave por entorno desde Key Vault (Stories 1.6 y 3.4).
- Dockerfiles ya existen; faltan manifests Kubernetes, pipeline CI/CD, ACR, AKS e ingress HTTPS. Activar `SPRING_PROFILES_ACTIVE=azure` y calibrar el umbral de similitud (0.45 provisional).
- Verificar en la URL HTTPS del Asistente las tres consultas del baseline.
- Prueba E2E en Azure con SLA corto: registro, conteo activo, escalamiento, alerta SSE y alta que decrementa el conteo.
- Completar la tabla "URLs de Azure" del README y retirar el aviso de estado.
- Agregar `ASISTENTE_API_KEY` a `.env.example` (no tocado aquí) y quitar el TODO del README.
- Puerta E2E local (curl/BDD/MBT) antes de marcar `done`.

## Discrepancias encontradas (baseline vs. código/repo)

- `AGENTS.md` (citado por el baseline y `CLAUDE.md`) no existe en el repositorio.
- `.env.example` no se pudo leer (permiso denegado); la presencia de `ASISTENTE_API_KEY` quedó sin verificar.
- El Compose no pasa variables `AZURE_OPENAI_*` ni el perfil `azure`: en local el Asistente usa dobles de desarrollo.
- Métricas Micrometer de latencia LLM existen pero Actuator del Asistente expone solo `health` (diferido de 4.3).
- No existen aún manifests Kubernetes, Terraform ni CI, exigidos por el enunciado.
