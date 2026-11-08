---
title: 'Story 1.3: Cambiar el estado con transiciones válidas'
type: 'feature'
created: '2026-11-08'
status: 'done'
route: 'dispatch'
baseline_commit: '70289802ae461c53e6a12674bc55b12c5df51b8b'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** RF-03 exige mover al paciente a consulta o darle alta con transiciones válidas y auditoría. Hoy el estado queda fijo en `REGISTRADO`.

**Approach:** Agregar `PATCH /triage/pacientes/{id}/estado` con cuerpo `{ "estado": "EN_CONSULTA" | "ALTA" }`. La regla de transiciones vive en el dominio. Un caso de uso transaccional hace una actualización condicional por estado esperado y agrega la auditoría (`paciente_en_consulta` o `paciente_alta`). Responde 200 con el mismo cuerpo que `GET /triage/pacientes/{id}`. Una transición inválida o una carrera perdida devuelve 409 ProblemDetail con el estado actual y el pedido.

## Boundaries & Constraints

**Always:**
- Solo se permiten `REGISTRADO→EN_CONSULTA`, `REGISTRADO→ALTA` y `EN_CONSULTA→ALTA`. Todo lo demás es inválido, incluido el mismo estado actual.
- En una sola transacción R2DBC: `UPDATE patient SET status, status_changed_at WHERE id = :id AND status = :expected` y luego `INSERT audit_event` (estado anterior y nuevo, `occurred_at` igual a `status_changed_at`, UTC truncado a microsegundos con el `Clock` inyectable).
- Si el UPDATE no afecta filas, se aborta con 409 y no se inserta auditoría.
- El endpoint lo protege la API key existente.
- Los logs solo llevan id y los estados anterior y nuevo, sin PII.
- Todo es reactivo, sin `block()` en el código de producción.

**Never:**
- Migraciones nuevas.
- Outbox, Kafka o `aggregateVersion` (son de la Story 1.4).
- Endpoints para borrar o modificar la auditoría.
- Cambiar `escalated` o el contrato de `POST` o `GET`.
- Locks pesimistas (`SELECT ... FOR UPDATE`) en lugar de la actualización condicional.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| A consulta | `REGISTRADO`, `{"estado":"EN_CONSULTA"}` | 200, `estado: EN_CONSULTA`, `minutosEnEstadoActual: 0`, auditoría `paciente_en_consulta` | N/A |
| Alta directa | `REGISTRADO`, `ALTA` | 200, auditoría `paciente_alta` (anterior `REGISTRADO`) | N/A |
| Alta desde consulta | `EN_CONSULTA`, `ALTA` | 200, auditoría `paciente_alta` (anterior `EN_CONSULTA`) | N/A |
| Retroceso | `EN_CONSULTA`, `REGISTRADO` | 409, `estadoActual: EN_CONSULTA`, `estadoSolicitado: REGISTRADO` | Sin cambios ni auditoría |
| Desde alta | `ALTA`, cualquier estado | 409 | Sin cambios ni auditoría |
| Mismo estado | `REGISTRADO`, `REGISTRADO` | 409 | Sin cambios ni auditoría |
| Estado desconocido | `{"estado":"FOO"}` | 400, `campo: estado` | Sin cambios |
| Estado ausente o null | `{}` o `{"estado":null}` | 400, `campo: estado` | Sin cambios |
| Inexistente | UUID desconocido, `ALTA` válido | 404 "Paciente no encontrado" | N/A |
| Id mal formado | `/no-uuid/estado` | 400, `campo: id` | N/A |
| Sin API key | cualquiera | 401 con cuerpo vacío | N/A |
| Carrera | dos PATCH simultáneos (`EN_CONSULTA` y `ALTA`) sobre un `REGISTRADO` | uno 200 y el otro 409; exactamente una auditoría de transición | Actualización condicional |

</frozen-after-approval>

## Code Map

- `.../domain/PatientStatus.java` -- agregar `canTransitionTo(PatientStatus target)` con la tabla de transiciones, sin Spring.
- `.../domain/Patient.java` -- record. Agregar `withStatus(PatientStatus, Instant)`, que conserva el resto de los campos.
- `.../domain/AuditEvent.java` -- agregar las constantes `PATIENT_IN_CONSULTATION = "paciente_en_consulta"` y `PATIENT_DISCHARGED = "paciente_alta"`, con los mismos nombres que tendrán los eventos Kafka de 1.4.
- `.../application/PatientRepositoryPort.java` -- agregar `Mono<Boolean> updateStatus(UUID id, PatientStatus expected, PatientStatus next, Instant changedAt)`, que devuelve true si afectó una fila.
- `.../application/RegisterPatientUseCase.java` -- patrón a copiar: `Mono.defer`, `Clock` truncado a micros y `.as(tx::transactional)`.
- `.../application/GetPatientUseCase.java` y `PatientNotFoundException` -- reutilizar `PatientNotFoundException` para el 404.
- `.../infrastructure/persistence/R2dbcPatientRepository.java` -- implementar `updateStatus` con `DatabaseClient`, igual que `save`: `OffsetDateTime` en UTC y `rowsUpdated() > 0`.
- `.../infrastructure/persistence/R2dbcAuditRepository.java` -- reutilizar sin cambios.
- `.../infrastructure/config/BeanConfig.java` -- registrar el bean del nuevo caso de uso.
- `.../infrastructure/web/PatientController.java` -- agregar el PATCH. Reutilizar `PacienteDetalleResponse.from(p, clock.instant())` y `@PathVariable("id") UUID`.
- `.../infrastructure/web/ApiExceptionHandler.java` -- el 404 y el 400 con `campo` (path variable y JSON) ya funcionan. Agregar un `@ExceptionHandler` para el 409.
- Tests de referencia: `src/test/.../web/PatientQueryIntegrationTest.java` (helpers `register()`, `DatabaseClient` para manipular estado y `API_KEY`) y `PostgresTestSupport`.

## Tasks & Acceptance

**Execution:**
- [x] `domain/PatientStatus.java`, `domain/Patient.java`, `domain/AuditEvent.java` -- tabla de transiciones, `withStatus` y constantes de evento -- regla en el dominio, testeable sin Spring
- [x] `application/InvalidStatusTransitionException.java` -- excepción con `current` y `requested` y mensaje en español -- base del 409
- [x] `application/ChangePatientStatusUseCase.java` -- `findById` (si no existe, 404) → `canTransitionTo` (si no, 409) → `updateStatus` condicional (si devuelve false, releer el estado actual y lanzar 409 con ese estado) → auditoría, todo dentro de `tx::transactional` -- atomicidad y carrera
- [x] `application/PatientRepositoryPort.java`, `infrastructure/persistence/R2dbcPatientRepository.java` -- `updateStatus` condicional -- control de concurrencia
- [x] `infrastructure/config/BeanConfig.java` -- bean del caso de uso
- [x] `infrastructure/web/CambiarEstadoRequest.java` -- record `{ @NotNull PatientStatus estado }` -- un valor desconocido devuelve 400 por deserialización
- [x] `infrastructure/web/PatientController.java` -- `@PatchMapping("/{id}/estado")`, 200 con `PacienteDetalleResponse` y log de id y estados -- contrato HTTP
- [x] `infrastructure/web/ApiExceptionHandler.java` -- 409 con título "Transición de estado inválida", `detail` con ambos estados y propiedades `estadoActual`/`estadoSolicitado` -- contrato de error
- [x] `src/test/.../domain/PatientStatusTest.java` -- las 9 combinaciones origen/destino -- cobertura de la regla
- [x] `src/test/.../web/PatientStatusChangeIntegrationTest.java` -- todas las filas de la matriz. Verificar filas de `audit_event` y `status_changed_at` en la DB. La carrera se prueba con dos PATCH en paralelo (`Mono.zip` sobre `WebTestClient` o un `CountDownLatch`) -- NFR7

**Acceptance Criteria:**
- Given una transición válida, when se confirma, then `patient.status_changed_at` es igual a `audit_event.occurred_at` y `GET` devuelve el estado nuevo.
- Given una transición rechazada (409, 400 o 404), when se consulta la DB, then el paciente no cambió y hay una sola auditoría (`paciente_registrado`).
- Given que la inserción de auditoría falla, when se ejecuta el PATCH, then el estado del paciente no cambia (rollback, con `@MockitoSpyBean` sobre `AuditRepositoryPort` igual que en 1.1).

## Implementation Notes

- `ChangePatientStatusUseCase` devuelve `Change(patient, previous)` para que el log del controller muestre el estado anterior sin otra lectura.
- Carrera perdida: si `updateStatus` afecta 0 filas, se relee el paciente y el 409 informa el estado real; no se inserta auditoría.
- La fila "Carrera" de la matriz (`EN_CONSULTA` + `ALTA` en paralelo) no es determinística: si la segunda llega después del commit de la primera, `EN_CONSULTA→ALTA` es válida y ambas dan 200. El test `concurrentRequestsYieldOneWinner` exige coherencia entre los 200 y las auditorías. La garantía "una sola gana" se cubre de forma determinística con `concurrentSameTargetYieldsExactlyOne409` (dos `ALTA` en paralelo) y `ChangePatientStatusUseCaseTest` (carrera perdida forzada con mocks).
- Se agregó `reactor-test` como dependencia de test por `StepVerifier`.
- Suite completa tras la revisión: 134 tests, 0 fallos.

## Spec Change Log

## Review Triage Log

| # | Hallazgo (capa) | Veredicto | Evidencia | Ruta |
|---|---|---|---|---|
| 1 | El 409 por carrera perdida dice "no se puede de EN_CONSULTA a ALTA" aunque esa transición está permitida (Blind, Edge, VerifGap) | medium | `ChangePatientStatusUseCase` relanza `InvalidStatusTransitionException(latest, requested)` con el mismo mensaje. El AC exige 409 para la perdedora, así que no se reintenta; solo se distingue el mensaje de carrera | patch |
| 2 | `eventType` manda cualquier destino que no sea EN_CONSULTA a `paciente_alta` (Blind, Edge) | low | Hoy es inalcanzable, pero un cambio de la tabla auditaría mal. Se corrige con un switch exhaustivo | patch |
| 3 | `change()` con `requested` nulo devuelve 409 (Edge) | false | El único llamador es el controller, que tiene `@NotNull` en `CambiarEstadoRequest.estado`; no es alcanzable | rechazado |
| 4 | La guarda `AND status = :expected` solo tiene verificación dependiente del timing (VerifGap) | medium | Pre-verificado: sin la cláusula, los tests pasan cuando no hay intercalado. Se agrega un test de integración directo de `updateStatus` | patch |
| 5 | `ChangePatientStatusUseCaseTest` cubre solo 2 caminos (Blind) | low | El 404, el 409 inválido y el rollback ya están cubiertos de punta a punta en el IT | rechazado |
| 6 | Sin métrica ni log de transiciones rechazadas (Blind) | low | El PDF exige contadores de clasificaciones y escalamientos, no de transiciones. Se agrega solo un log de rechazo, igual que el 404 del GET | patch |
| 7 | El `detail` del 409 no se verifica (Blind) | low | Los tests verifican title y propiedades, pero no el detail | patch |
| 8 | Filas de la matriz con asserts parciales: `directDischarge` y `fromDischarge` (Blind) | low | Faltan el estado en la DB y `ALTA→REGISTRADO` | patch |
| 9 | El nombre de `concurrentRequestsYieldOneWinner` no coincide con lo que verifica (Blind) | low | Su aserción admite dos 200 | patch (renombrar) |
| 10 | `Schedulers` escrito con nombre completo y bloque duplicado (Blind) | low | Se corrige el import; el helper no aporta | patch (import) |
| 11 | Orden de asignación en el constructor del controller (Blind) | low | Corrección directa | patch |
| 12 | Nombres de enum crudos en el mensaje en español (Blind) | false | Son los mismos valores del contrato JSON (`estado`), así que es intencional | rechazado |

## Design Notes

En la carrera, la transacción perdedora espera el lock de fila del UPDATE ganador. Bajo READ COMMITTED, Postgres reevalúa el `WHERE status = :expected` sobre la versión ya confirmada, así que el UPDATE afecta 0 filas. Por eso no hace falta un lock explícito. Cuando hay 0 filas, se relee el paciente para que el 409 informe el estado real y no el leído al principio.

## Verification

**Commands:**
- `cd triage-service && ./gradlew test` -- expected: BUILD SUCCESSFUL, los 108 tests previos más los nuevos en verde
