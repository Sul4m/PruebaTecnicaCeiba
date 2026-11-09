---
title: 'Story 3.1: Persistir y consultar alertas de escalamiento'
type: 'feature'
created: '2026-11-08'
status: 'done'
route: 'dispatch'
baseline_commit: '7a4edb6'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/docs/adr/0002-alertas-medicas-sse.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** RF-06 exige alertar al equipo médico cuando se escala un paciente (ADR-0002: alertas persistidas + SSE). Hoy el escalamiento solo marca el flag, audita y encola el evento; no existe una alerta consultable ni recuperable.

**Approach:** Al escalar (Story 1.5), en la misma transacción se inserta una fila en `medical_alert` con `alertId` UUID y un `deliveryCursor` BIGINT monotónico, asignado con un contador singleton bloqueado con `SELECT ... FOR UPDATE` hasta el commit. `GET /triage/alertas?despuesDe&limite` devuelve las alertas con cursor mayor, ordenadas y paginadas. El stream SSE es de la Story 3.2 y no entra aquí.

## Boundaries & Constraints

**Always:**
- Una alerta por escalamiento, insertada en la misma transacción que el flag, la auditoría y el outbox. `UNIQUE (patient_id)` respalda que un reintento no cree una segunda alerta. Si falla la inserción de la alerta, se revierte todo el escalamiento.
- Cursor: se toma el lock del contador singleton (`SELECT ... FOR UPDATE`) una vez por lote y se asignan los cursores en el orden de las filas escaladas. El lock se mantiene hasta el commit, así que no se confirma un cursor posterior antes que uno anterior y no hay huecos por rollback.
- Contenido de la alerta: solo `alertId`, `deliveryCursor`, `patientId`, `nivel` y `timestamp` (instante del escalamiento, UTC, micros). Sin nombre, edad, queja ni signos vitales.
- `GET /triage/alertas`: `despuesDe` (entero ≥ 0, default 0) y `limite` (1 a 200, default 50). Respuesta: arreglo de alertas con `deliveryCursor` mayor a `despuesDe`, en orden ascendente. Parámetro inválido (no numérico, negativo, fuera de rango): 400 ProblemDetail con `campo`. Sin API key: 401. Los errores 400 siguen el patrón de `ApiExceptionHandler`.
- Logs sin PII: solo `alertId`, cursor y nivel. Reactivo, sin `block()`.

**Never:**
- SSE, polling del stream o `Last-Event-ID` (Story 3.2).
- Endpoints para borrar o modificar alertas.
- Cambiar el envelope Kafka, el outbox o los contratos HTTP existentes.
- Asignar el cursor con una secuencia de PostgreSQL (`nextval` no garantiza orden de commit) o en memoria.
- Un lock sobre `patient` más largo que el de la 1.5.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Escalamiento | Paciente que supera el SLA | Fila en `medical_alert` con cursor n+1, misma transacción que el flag, la auditoría y el outbox | N/A |
| Varios en un lote | k pacientes escalados juntos | k alertas con cursores consecutivos n+1..n+k | N/A |
| Ciclos concurrentes | Dos ciclos sobre pacientes distintos | Cursores únicos, estrictamente crecientes en orden de commit, sin huecos | N/A |
| Reintento | El proceso corre otra vez sobre un paciente ya escalado | No se crea una segunda alerta | N/A |
| Rollback | Falla el insert de la alerta | El paciente sigue sin escalar, sin auditoría, sin outbox y sin alerta; el contador no avanza | Error propagado al ciclo |
| Consulta | `despuesDe=5&limite=2` | Alertas con cursor 6 y 7, ascendentes | N/A |
| Sin más | `despuesDe` ≥ último cursor | `[]` con 200 | N/A |
| Parámetro inválido | `despuesDe=-1`, `limite=0`, `limite=201`, `limite=abc` | 400 con `campo` = el parámetro | Sin consulta a la base |
| Sin API key | cualquiera | 401 con cuerpo vacío | N/A |
| Privacidad | Cualquier respuesta | Solo las 5 claves del contrato | N/A |

</frozen-after-approval>

## Code Map

- `src/main/resources/db/migration/` -- la última es la V4 (índice de escalamiento). Crear `V5__medical_alert.sql`: tabla `medical_alert` (`alert_id UUID PK`, `delivery_cursor BIGINT NOT NULL UNIQUE`, `patient_id UUID NOT NULL UNIQUE REFERENCES patient(id)`, `level VARCHAR`, `created_at TIMESTAMPTZ NOT NULL`) y tabla singleton del contador (una fila con `last_cursor BIGINT` en 0 y `CHECK` de fila única).
- `application/EscalateOverduePatientsUseCase.java` -- agregar el insert de alertas dentro de `escalateBatch`, después del outbox y antes del commit. No cambiar su firma pública ni `escalateAll`.
- `application/AlertRepositoryPort.java` (nuevo) -- `Flux<MedicalAlert> appendAll(List<EscalatedPatient>, Instant)` (toma el lock, asigna cursores en orden) y `Flux<MedicalAlert> findAfter(long cursor, int limit)`.
- `domain/MedicalAlert.java` (nuevo) -- record con `alertId`, `deliveryCursor`, `patientId`, `level`, `createdAt`.
- `infrastructure/persistence/R2dbcAlertRepository.java` (nuevo) -- `SELECT last_cursor ... FOR UPDATE`, `UPDATE` del contador y `INSERT` (batch), todo en la transacción del llamador. `findAfter` con `ORDER BY delivery_cursor LIMIT`.
- `application/GetAlertsUseCase.java` y `infrastructure/web/AlertController.java` + `AlertaResponse.java` (nuevos) -- la validación de parámetros va en el controller o el caso de uso y lanza una excepción que `ApiExceptionHandler` mapea a 400 con `campo` (reutilizar `InvalidReportParameterException` o generalizarla a `InvalidQueryParameterException`, actualizando sus usos de la Story 2.1).
- `infrastructure/config/BeanConfig.java` -- beans nuevos. `EscalateOverduePatientsUseCase` recibe el puerto de alertas; ajustar sus constructores en `main` y `test`.
- `src/test/resources/application.properties` -- el scheduler sigue apagado en tests.
- `docs/alertas.md` (nuevo) -- contrato de `GET /triage/alertas`, cursor, deduplicación por `alertId`; enlazar ADR-0002. No editar `deferred-work.md` ni `sprint-status.yaml`.

## Tasks & Acceptance

**Execution:**
- [x] `V5__medical_alert.sql`, `MedicalAlert`, `AlertRepositoryPort`, `R2dbcAlertRepository` -- persistencia y contador bajo lock -- orden de commit
- [x] `EscalateOverduePatientsUseCase`, `BeanConfig` -- alerta dentro de la transacción del escalamiento -- atomicidad
- [x] `GetAlertsUseCase`, `AlertController`, `AlertaResponse`, `ApiExceptionHandler` -- consulta paginada y validación -- contrato HTTP
- [x] `docs/alertas.md` -- contrato documentado -- RF-06
- [x] `src/test/.../AlertsIntegrationTest.java` -- todas las filas de la matriz contra Postgres. Cursores consecutivos en un lote. Dos ciclos concurrentes sin duplicados ni huecos. Rollback con `@MockitoSpyBean` sobre el puerto de alertas. Privacidad: las claves del JSON son exactamente las cinco. Verificar que no existe ruta DELETE ni PUT
- [x] `EscalateOverduePatientsUseCaseTest` -- actualizar y cubrir el caso de alerta fallida

**Acceptance Criteria:**
- Given un escalamiento confirmado, when se consulta la base, then hay exactamente una fila de `medical_alert` por paciente escalado y su cursor es mayor que el de la alerta anterior.
- Given la suite completa, when corre, then los 183 tests previos (ajustados a las nuevas firmas) y los nuevos pasan.

## Implementation Notes

- Suite completa tras la revisión: 198 tests, 0 fallos.
- `escalateBatch` devuelve los pacientes escalados junto con las alertas creadas; tras el commit registra una línea por lote (`count`, `cursorDesde`, `cursorHasta`).
- La validación de `despuesDe` y `limite` vive en `GetAlertsUseCase`; el controller solo parsea. Reutiliza `InvalidReportParameterException` de la Story 2.1.
- Las rutas sin escritura devuelven 405 en `/triage/alertas` y 404 en `/triage/alertas/{id}`.
- Limitación: el test de cursores asume que ningún otro test escala pacientes a la vez (el scheduler está apagado en los tests).

## Spec Change Log

## Review Triage Log

| # | Hallazgo (capa) | Veredicto | Evidencia | Ruta |
|---|---|---|---|---|
| 1 | Los pacientes escalados antes de la V5 no tienen alerta (Blind, Edge, VerifGap) | low | No hay datos previos en este ejercicio; la base de Azure nace vacía y la V5 llega junto con la 3.1 | rechazado |
| 2 | El log "Alerta creada" se emite dentro de la transacción, una vez por alerta, y puede ser falso (Blind) | low | Si el commit falla, el log afirma alertas revertidas. Se registra una vez por lote después del commit | patch |
| 3 | El test de rollback pasa si no se lanza ninguna excepción (Blind, Edge) | medium | `try/catch` sin `fail()`: no prueba nada si la falla inyectada no ocurre | patch |
| 4 | `thereAreNoWriteRoutes` solo exige 4xx y solo prueba DELETE y PUT (Blind) | low | Un 401 o un 404 también lo pasarían | patch |
| 5 | Validación duplicada entre controller y caso de uso; `limite=-5` da un mensaje equivocado (Blind, Edge) | low | El mensaje dice "mayor o igual a 0" para `limite`; una sola fuente de validación evita el desvío | patch |
| 6 | Faltan pruebas de los valores por defecto, el límite 200 y `limite` negativo (Blind) | low | Se agregan al test de integración. Los tests unitarios del caso de uso y de la lista vacía no aportan | patch (parcial) |
| 7 | La fila del contador podría faltar y el escalamiento confirmaría sin alerta (Edge) | low | La V5 la crea, pero si faltara el fallo sería silencioso. Se vuelve un error que revierte la transacción | patch |
| 8 | Un solo `UPDATE ... RETURNING` evitaría un viaje de ida y vuelta (Blind) | low | El criterio de aceptación pide `SELECT ... FOR UPDATE` sobre el contador | rechazado |
| 9 | El lock global serializa los lotes y no hay métrica de espera (Blind) | low | El ADR-0002 acepta esa serialización breve a cambio del orden de commit | rechazado |
| 10 | `medical_alert.level` sin `CHECK`; un valor inválido rompe el listado (Blind, Edge) | low | Solo la aplicación escribe, con el nombre del enum; se rechazaron los CHECK de esquema desde la 1.1 | rechazado |
| 11 | La FK sin `ON DELETE` y sin retención de alertas (Blind) | low | No existe borrado de pacientes en la API; la purga se resuelve junto con la del outbox | rechazado |
| 12 | El enlace al ADR-0002 podría estar roto (Blind) | false | `docs/adr/0002-alertas-medicas-sse.md` existe | rechazado |
| 13 | `appendAll` fuera de una transacción liberaría el lock al instante (Edge) | low | El único llamador es `escalateBatch`, que ya corre dentro de `TransactionalOperator` | rechazado |
| 14 | Un fallo permanente del insert de alerta bloquearía todos los lotes (Edge) | false | `UNIQUE (patient_id)` no puede violarse: el escalamiento exige `escalated = false`, así que cada paciente tiene una sola alerta | rechazado |
| 15 | La spec lista `ApiExceptionHandler` y el diff no lo toca (Edge, claim) | false | El mapeo a 400 con `campo` ya existía para `InvalidReportParameterException` | rechazado |
| 16 | Estilo: líneas largas e imports completos de Mockito (Blind) | low | Corrección directa | patch |

## Design Notes

Con el contador bajo `FOR UPDATE`, la transacción de escalamiento que llegue segunda espera a que la primera haga commit o rollback. El lock cubre solo el tramo final de la transacción (después de escalar y auditar), así que la serialización es breve. En el orden de adquisición no hay ciclo: una transacción toma primero filas de `patient` con `SKIP LOCKED` y después el contador, y nadie toma el contador y luego pacientes.

## Verification

**Commands:**
- `cd triage-service && ./gradlew test` -- expected: BUILD SUCCESSFUL, 0 fallos
