---
title: 'Story 1.5: Escalar por SLA una sola vez'
type: 'feature'
created: '2026-11-08'
status: 'done'
route: 'dispatch'
baseline_commit: '63679c8'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/docs/adr/0005-outbox-y-contrato-kafka.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** RF-03 exige un proceso periódico que escale a los pacientes `REGISTRADO` que superan su SLA: marca el flag, audita y publica `paciente_escalado`, una sola vez aunque haya varias instancias. Hoy el flag `escalated` nunca cambia y los SLA están fijos en el enum.

**Approach:** Un scheduler reactivo propio (como `OutboxRelay`) llama a un caso de uso que, por lote, hace un `UPDATE ... RETURNING` condicionado a `status='REGISTRADO' AND escalated=false AND registered_at + sla_minutes < :now`, con `FOR UPDATE SKIP LOCKED` y `LIMIT`. En la misma transacción inserta, por cada fila devuelta, la auditoría y la fila del outbox (`paciente_escalado`, versión +1). Los SLA por nivel y el intervalo salen de propiedades; `sla_minutes` se persiste al registrar y es la fuente que usan el escalamiento y el `GET` (cierra el diferido de la Story 1.2).

## Boundaries & Constraints

**Always:**
- Se escala solo cuando el tiempo desde el registro **supera** el SLA (estrictamente mayor). Exactamente igual al SLA no escala. Se mide con el `Clock` inyectable: `now` se pasa como parámetro al SQL, no se usa `now()` de la base.
- La actualización condicional y las inserciones de auditoría y outbox ocurren en una sola transacción. La auditoría usa `previous_status = new_status = REGISTRADO`, `event_type = paciente_escalado` y `occurred_at = now` (UTC, micros).
- El outbox lleva el envelope v1 con `escalated: true` y `aggregateVersion` igual a la versión devuelta por el `UPDATE` (+1). `status_changed_at` no cambia.
- Idempotencia con instancias en paralelo: el `FOR UPDATE SKIP LOCKED` y la condición `escalated=false` hacen que cada paciente se escale una sola vez. Nunca se escala `EN_CONSULTA` ni `ALTA`.
- Trabajo por lotes: `LIMIT batch-size`; si el lote vino lleno se repite en el mismo ciclo, secuencialmente (concurrencia 1 por instancia). Nunca `collectList()` sin límite.
- Propiedades: `triage.escalation.enabled` (true), `.interval` (30s), `.batch-size` (100), y `triage.sla.minutes.<NIVEL>` con los defaults 5/10/30/60/120. Se validan como positivas al arrancar. El registro persiste `sla_minutes` desde estas propiedades; `Patient.slaMinutes` pasa a ser el valor persistido y `GET` lo devuelve.
- Métrica Micrometer `triage.escalamientos{nivel}`. Los logs llevan solo id y nivel, sin PII.
- Si un ciclo falla, se registra, se aplica backoff acotado y el scheduler sigue vivo.

**Never:**
- Alertas persistidas, SSE o `medical_alert` (Story 3.1).
- Endpoints nuevos o cambios al contrato HTTP, salvo que `slaMinutos` salga de la columna.
- Escalar desde el `GET` o en el camino de una petición.
- Cambiar `status`, `status_changed_at` o `level` al escalar.
- Un scheduler bloqueante o `@Scheduled` con `block()`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Supera el SLA | `REGISTRADO`, sin escalar, `registered_at + sla < now` | `escalated=true`, versión +1, 1 auditoría `paciente_escalado` y 1 fila de outbox con `escalated: true`; el contador sube | N/A |
| Frontera | `now - registered_at` = SLA exacto vs. SLA + 1 µs, en los 5 niveles | Exacto: no escala. +1 µs: escala | N/A |
| Ya escalado | `escalated=true` y otro ciclo | Sin cambios, sin auditoría ni outbox nuevos | N/A |
| En paralelo | Dos ciclos concurrentes sobre los mismos pacientes vencidos | Cada paciente tiene exactamente 1 auditoría y 1 fila de outbox de escalamiento | N/A |
| Activos | `EN_CONSULTA` o `ALTA` con el SLA vencido | Nunca se escalan | N/A |
| Lote | Más pacientes vencidos que `batch-size` | Se escalan todos, en varios lotes secuenciales del mismo ciclo | N/A |
| SLA configurado | `triage.sla.minutes.CRITICO=1` | El paciente se registra con `sla_minutes=1`, `GET` devuelve `slaMinutos: 1` y escala a los 1 min + 1 µs | Un valor no positivo impide el arranque |
| Carrera con PATCH | Transición a `EN_CONSULTA` que confirma antes del ciclo | No se escala y no hay efectos | N/A |
| Visibilidad | Escalado | `GET` devuelve `flags.escalado: true` | N/A |

</frozen-after-approval>

## Code Map

- `domain/TriageLevel.java` -- los SLA fijos pasan a ser el **default** de `SlaProperties`; el enum conserva `slaMinutes` como valor por defecto, no como fuente de verdad.
- `domain/Patient.java` -- agregar `int slaMinutes` como campo (hoy lo deriva del nivel). Actualizar los constructores en `main` y `test`, `withStatus` y `PacienteDetalleResponse.from`.
- `application/RegisterPatientUseCase.java` -- toma el SLA del nivel desde un `SlaPolicy`/`SlaProperties` inyectado y lo guarda en `Patient`.
- `infrastructure/persistence/R2dbcPatientRepository.java` -- `INSERT` ya envía `sla_minutes`; `SELECT_BY_ID` debe leerlo (cierra el diferido de `deferred-work.md`). Agregar `escalateOverdue(now, limit)` con el `UPDATE ... RETURNING` y `FOR UPDATE SKIP LOCKED`.
- `application/PatientRepositoryPort.java` -- nuevo método que devuelve un `Flux` con los pacientes escalados (id, nivel, estado, versión).
- `application/EscalateOverduePatientsUseCase.java` (nuevo) -- por lote: escalar, auditar, outbox y métrica, dentro de `TransactionalOperator`. Reutilizar `AuditEvent`, `OutboxPort` y `TriageEventEnvelope.of(...)`. Agregar `AuditEvent.PATIENT_ESCALATED = "paciente_escalado"`.
- `infrastructure/messaging/OutboxRelay.java` -- patrón a copiar para el ciclo (`Mono.delay`, backoff, `ApplicationReadyEvent`, `OutboxProperties` validadas). No lo modifiques.
- `infrastructure/scheduling/EscalationScheduler.java` y `EscalationProperties.java` (nuevos), `SlaProperties.java` (nuevo).
- `infrastructure/observability/` -- puerto y adaptador de la métrica `triage.escalamientos`, igual que `MicrometerClassificationMetrics`.
- `src/main/resources/db/migration/V4__escalation_index.sql` -- índice parcial `patient(registered_at) WHERE status='REGISTRADO' AND escalated=false`. La V3 ya existe; no la toques.
- `src/test/resources/application.properties` -- agregar `triage.escalation.enabled=false`; los tests invocan el caso de uso directo y un test activa el scheduler con un intervalo corto.
- `docs/eventos-triaje.md` -- documentar `paciente_escalado`. `_bmad-output/implementation-artifacts/deferred-work.md` -- no editar (se resuelve el diferido por código).

## Tasks & Acceptance

**Execution:**
- [x] `SlaProperties`, `Patient`, `RegisterPatientUseCase`, `R2dbcPatientRepository`, `PacienteDetalleResponse` -- SLA configurable y persistido, `GET` lo lee de la columna -- cierra el diferido de la 1.2
- [x] `PatientRepositoryPort` + `R2dbcPatientRepository.escalateOverdue` + `V4__escalation_index.sql` -- `UPDATE ... RETURNING` condicional con `SKIP LOCKED` y `LIMIT` -- idempotencia y lotes
- [x] `EscalateOverduePatientsUseCase` + `AuditEvent` + métrica -- auditoría, outbox y contador en la misma transacción
- [x] `EscalationScheduler` + `EscalationProperties` -- ciclo reactivo con backoff acotado, drenaje por lotes y arranque con `ApplicationReadyEvent`
- [x] `BeanConfig`, `src/test/resources/application.properties`, `docs/eventos-triaje.md` -- cableado, tests con el scheduler apagado y documentación
- [x] `src/test/.../EscalationIntegrationTest.java` -- todas las filas de la matriz. Frontera por nivel con un `Clock` fijo. Envelope en el outbox (`escalated: true`, versión +1). Paralelo con dos llamadas concurrentes. Un test del scheduler real con intervalo corto y Awaitility
- [x] `src/test/.../EscalateOverduePatientsUseCaseTest.java` -- unitario con puertos falsos: sin vencidos no hay auditoría ni outbox; el error de auditoría se propaga

**Acceptance Criteria:**
- Given una transacción de escalamiento que falla al auditar, when se consulta la base, then `escalated` sigue en false y no hay outbox.
- Given un paciente escalado, when se consulta `GET`, then `flags.escalado` es true y `slaMinutos` coincide con el SLA persistido.
- Given la suite completa, when corre, then los 160 tests previos (ajustados a las nuevas firmas) y los nuevos pasan.

## Implementation Notes

- Suite completa tras la revisión: 183 tests, 0 fallos.
- `Patient` lleva `slaMinutes` como campo; `SlaPolicy` (puerto) y `SlaProperties` dan el SLA al registrar. `GET` lee la columna `sla_minutes` (cierra el diferido de la Story 1.2).
- `escalateOverdue` usa `FOR UPDATE SKIP LOCKED` y `RETURNING`; la auditoría y el outbox van en la misma transacción.
- El backoff del scheduler está acotado por una constante de 5 minutos (o `interval` si es mayor), no por una propiedad.
- No probado: dos transacciones reales concurrentes entre un PATCH y el escalamiento (cubierto por el bloqueo de fila y por el caso `EN_CONSULTA` que nunca escala).

## Spec Change Log

## Review Triage Log

| # | Hallazgo (capa) | Veredicto | Evidencia | Ruta |
|---|---|---|---|---|
| 1 | El bucle del scheduler puede morir en silencio si falla `nextDelay` o el `flatMap` (Blind, Edge) | medium | Solo `runCycle()` captura errores y `subscribe()` no tiene consumidor de errores; el overflow de `multipliedBy` lo detendría para siempre | patch |
| 2 | Sin test unitario del scheduler: backoff, tope, reinicio y bucle tras fallo (VerifGap, Blind) | medium | Pre-verificado: solo existe el test de integración con la base sana | patch |
| 3 | La aserción de `SlaPropertiesTest` solo comprueba que algo lanza (VerifGap, Blind, Edge) | low | Cualquier error de binding la pasaría | patch |
| 4 | Filas que dejan los tests de integración pueden afectar a otros o al scheduler real (Blind) | low | Un paciente con SLA de 1 min o de 2019 sigue vencido para un scheduler vivo | patch |
| 5 | Log del fallo sin causa; falta documentar SLA congelado al registrar, NTP y varias instancias (Blind, Edge) | low | Solo se registra `getSimpleName()`; la doc no aclara que cambiar las propiedades no afecta a pacientes ya registrados | patch |
| 6 | Higiene: import sin uso, orden de imports, `@jakarta.annotation.PreDestroy` completo, líneas largas (Blind) | low | Corrección directa | patch |
| 7 | Una fila venenosa hace fallar todos los lotes (Edge) | low | Las filas salen de la misma base; un fallo determinista del insert de auditoría u outbox no tiene origen en los datos | rechazado |
| 8 | `recordEscalation` lanza tras el commit y se reporta como ciclo fallido (Edge) | false | El contador de Micrometer no lanza en uso normal | rechazado |
| 9 | Variable de entorno para niveles con guion bajo (`URGENTE_2`) no verificada (Edge, VerifGap) | medium | Puede importar al configurar los manifests de la Story 1.6; se documenta que no está verificada | defer |
| 10 | Desfase de reloj entre instancias (Blind, Edge) | low | La spec fija el `Clock` inyectable para tests deterministas; se documenta la suposición de NTP | patch (doc) |
| 11 | `escalateAll()` sin tope por ciclo ni progreso parcial (Blind, Edge) | low | Un reabastecimiento continuo de pacientes vencidos más rápido que el escalamiento no es realista; la rama agregaría una propiedad | rechazado |
| 12 | La consulta de vencidos no usa el índice para el chequeo de SLA (Blind) | low | Con el volumen de 12 salas un barrido es trivial; una columna `due_at` es optimización prematura | rechazado |
| 13 | V4 no restringe `sla_minutes > 0` (Blind) | low | La positividad se valida al arrancar; se rechazaron los CHECK de esquema también en la 1.1 | rechazado |
| 14 | Faltan métricas de fallos, duración y backlog (Blind) | low | El baseline pide contadores de escalamiento; las demás serían ampliación | rechazado |
| 15 | El test unitario no prueba atomicidad (Blind) | low | El rollback ya está cubierto en el test de integración contra Postgres | rechazado |
| 16 | `TriageLevel.slaMinutes()` sigue público y falta un ADR del scheduler (Blind) | low | Es el valor por defecto de las propiedades; el diseño de la solución ya describe el proceso periódico | rechazado |
| 17 | La carrera entre PATCH y escalamiento no tiene test (Blind) | low | La versión y `escalated` salen de `RETURNING` bajo lock de fila; el caso `EN_CONSULTA` nunca escala está cubierto | rechazado |
| 18 | Métrica `triage.escalamientos` verificada solo indirectamente (VerifGap) | false | El propio informe lo descarta: los tests de integración leen el contador con su tag | rechazado |

## Design Notes

Referencia del SQL (orientativa):
```sql
WITH due AS (
  SELECT id FROM patient
  WHERE status = 'REGISTRADO' AND escalated = false
    AND registered_at + make_interval(mins => sla_minutes) < :now
  ORDER BY registered_at LIMIT :limit FOR UPDATE SKIP LOCKED)
UPDATE patient p SET escalated = true, aggregate_version = p.aggregate_version + 1
FROM due WHERE p.id = due.id
RETURNING p.id, p.level, p.status, p.escalated, p.aggregate_version
```
Con `SKIP LOCKED`, dos instancias toman filas distintas; con la condición `escalated=false`, una fila ya confirmada no se vuelve a tomar. La transacción de una transición concurrente (PATCH) sobre la misma fila espera el lock y vuelve a evaluar `status = expected`, así que no se pierde ninguna versión.

## Verification

**Commands:**
- `cd triage-service && ./gradlew test` -- expected: BUILD SUCCESSFUL, 0 fallos
