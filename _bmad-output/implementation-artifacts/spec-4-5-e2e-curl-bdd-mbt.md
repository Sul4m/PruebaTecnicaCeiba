---
title: 'Story 4.5 - Puerta E2E con curl, BDD y MBT'
type: 'test'
created: '2026-11-08'
status: 'review'
baseline: 'docs/requirements/technical-test-baseline.md'
context:
  - '_bmad-output/planning-artifacts/epics.md'
  - 'e2e/README.md'
---

## Intent

**Problem:** Los tests Gradle (Testcontainers/mocks) no prueban el stack tal como lo ejecuta un evaluador. Falta una puerta E2E sobre `docker compose up` real, equivalente a una coleccion Postman, antes de pasar a Azure.

**Approach:** `e2e/run.sh` (bash + curl + jq) levanta el compose con un env-file sintetico y un override de SLA corto, ejecuta escenarios BDD en Gherkin (`e2e/features/*.feature`, ids `@S01..@S24`) y un MBT en Python (`e2e/model/`) que cubre todas las transiciones de un modelo `(estado, escalado)`; reinicia el Asistente con un proveedor LLM inalcanzable para validar el 503. Sale con codigo != 0 si algo falla y baja el stack.

## Boundaries & Constraints

**Always:** no crear ni editar `.env`; secretos sinteticos en `e2e/.generated/` (ignorado por git); proyecto compose propio (`vidacruz-e2e`); sin instalar herramientas globales; la fuente de verdad es el baseline/PDF.
**Never:** modificar logica de producto; validar calidad del LLM real (el perfil `dev` usa dobles).

## Acceptance Criteria

1. **Dado** Docker disponible, **cuando** se corre `e2e/run.sh`, **entonces** levanta el stack, espera readiness, ejecuta BDD + MBT + caso LLM caido y deja el stack abajo (`down -v`).
2. **Dado** los ACs de las historias 1.1-4.3, **entonces** hay escenarios para: E1-E6 y fronteras RN-01, validaciones 400 con `campo`, 401, transiciones 200/409/400/404, reportes, escalamiento una sola vez y nunca en EN_CONSULTA/ALTA, alertas persistidas, SSE con `Last-Event-ID`, conteo del Asistente via Kafka, contenido de eventos, RAG con cita, abstencion (RN-05) y 503.
3. **Dado** el modelo, **entonces** el MBT cubre el 100 % de sus transiciones (validas, invalidas, de tiempo) y comprueba estado, `escalado`, alertas y el conteo final del Asistente.

## Tasks

- [x] `e2e/run.sh`, `lib/lib.sh`, `scenarios.sh`, `compose.e2e.yml`, `compose.llm-down.yml`
- [x] `features/*.feature` (24 escenarios)
- [x] `model/patient_model.json` + `model/mbt.py`
- [x] `e2e/README.md`
- [x] Ejecucion contra compose real y registro de hallazgos

## Hallazgos de producto

Ver el reporte de la ejecucion (seccion al final de este archivo, actualizada tras correr la puerta).

## Limites

- SLA minimo = 1 min (minutos enteros); en E2E CRITICO usa 1 min, por lo que `slaMinutos` de CRITICO (5 en el PDF) no se valida con el valor real.
- IA local = dobles de desarrollo: no se valida GPT-5.4-mini, `text-embedding-3-small` ni el umbral real; tampoco AKS/Terraform (1.6, 3.4, 4.4-Azure).
