---
title: 'Story 2.1: Consultar el reporte de operación por hora'
type: 'feature'
created: '2026-11-08'
status: 'done'
route: 'dispatch'
baseline_commit: 'd54c2b8'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** RF-05 exige un reporte de operación por hora (total registrado, conteo por nivel, promedio y p95 de la espera hasta la primera consulta) calculado con SQL avanzado, sin iterar en memoria. Hoy no existe.

**Approach:** Agregar `GET /triage/reportes/operacion?desde=...&hasta=...` (ISO 8601; rango `[desde, hasta)` en UTC). Una sola consulta SQL con CTE resuelve todo: pacientes del rango, primera entrada `paciente_en_consulta` por paciente (`MIN(occurred_at)` sobre `audit_event`), `date_trunc('hour', ..., 'UTC')`, conteos con `FILTER` y `AVG`/`percentile_cont(0.95)` de minutos. La aplicación solo mapea filas agrupadas. Una migración nueva agrega los dos índices.

## Boundaries & Constraints

**Always:**
- Bucket por hora UTC de `patient.registered_at`; el rango se filtra con `registered_at >= desde AND registered_at < hasta`.
- La espera es `primera EN_CONSULTA (audit_event.event_type = 'paciente_en_consulta') - registered_at`, en minutos. Si hay varias entradas se usa solo la primera.
- Los pacientes sin consulta cuentan en `totalRegistrados` y `porNivel`, pero `AVG` y `percentile_cont` ignoran su `NULL` (no se imputa cero ni tiempo actual). Si ningún paciente del bucket entró a consulta, promedio y p95 son `null`.
- Promedio y p95 se redondean a 2 decimales en SQL. Las horas sin pacientes no aparecen.
- `desde`/`hasta` son obligatorios y deben ser ISO 8601 con offset (`Z` o `+hh:mm`); sin offset se interpreta como UTC. Ausente, inválido o `desde >= hasta` devuelve 400 ProblemDetail con `campo` igual al parámetro (`desde >= hasta` informa `desde`).
- El endpoint lo protege la API key existente. Logs solo con rango y cantidad de filas, sin PII. Todo reactivo, sin `block()` en producción.
- Migración nueva `V3__report_indexes.sql` (la V2 la crea otra rama) con `patient(registered_at, level)` y `audit_event(patient_id, event_type, occurred_at)`.

**Never:**
- Iterar pacientes en memoria ni calcular percentiles en Java.
- Tocar `clinical-assistant-service`, el contrato de `POST`/`GET`/`PATCH` de pacientes, ni crear la migración V2.
- Imputar esperas para pacientes sin consulta.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Reporte normal | 5 pacientes en la hora 10, 1 en la 11 | 200, lista de 2 filas ordenadas por `hora` con `totalRegistrados`, `porNivel`, `promedioMinutosHastaConsulta`, `p95MinutosHastaConsulta` | N/A |
| Sin consulta en el bucket | Hora 11 con un paciente sin `EN_CONSULTA` | Cuenta en total y nivel; promedio y p95 `null` | N/A |
| Paciente sin consulta en bucket mixto | Hora 10 con un paciente sin consulta | Cuenta en total y nivel; excluido de promedio y p95 | N/A |
| Varias entradas a consulta | Paciente con consultas a los 5 y 120 min | Espera = 5 | N/A |
| Consulta en otra hora | Registro 10:30, consulta 11:10 | Cuenta en el bucket 10 (por `fechaRegistro`), espera 40 | N/A |
| Límite inferior | Registro exacto en `desde` | Incluido | N/A |
| Límite superior | Registro exacto en `hasta` | Excluido | N/A |
| Offset distinto de UTC | `desde=...T05:00:00-05:00` | Se normaliza a UTC (10:00Z) | N/A |
| Rango vacío | Sin pacientes | 200 con lista vacía | N/A |
| `desde` o `hasta` ausente | falta uno | 400, `campo` = parámetro faltante | Sin consulta a la DB |
| Formato inválido | `desde=ayer`, fecha sin hora | 400, `campo` = parámetro | N/A |
| Rango invertido o vacío | `desde >= hasta` | 400, `campo: desde` | N/A |
| Sin API key | cualquiera | 401 con cuerpo vacío | N/A |

</frozen-after-approval>

## Code Map

- `.../application/OperationsReportPort.java` -- puerto `Flux<HourlyOperationsRow> hourlyReport(Instant from, Instant to)`.
- `.../application/HourlyOperationsRow.java` -- record de fila agrupada (hora, total, conteo por `TriageLevel`, promedio y p95 `BigDecimal` nullable).
- `.../application/GetOperationsReportUseCase.java` -- valida `desde < hasta` y delega al puerto; patrón de `GetPatientUseCase`.
- `.../application/InvalidReportParameterException.java` -- parámetro + motivo; base del 400.
- `.../infrastructure/persistence/R2dbcOperationsReportRepository.java` -- la consulta única con `DatabaseClient`, igual que `R2dbcAuditRepository`; solo mapea filas.
- `.../infrastructure/web/ReportController.java` y `ReporteOperacionHoraResponse.java` -- ruta `/triage/reportes/operacion`, parseo ISO 8601 y DTO en español.
- `.../infrastructure/web/ApiExceptionHandler.java` -- `@ExceptionHandler` para el 400 con `campo`.
- `.../infrastructure/config/BeanConfig.java` -- bean del caso de uso.
- `src/main/resources/db/migration/V3__report_indexes.sql` -- índices.
- Tests de referencia: `PatientQueryIntegrationTest` y `PostgresTestSupport`.

## Tasks & Acceptance

**Execution:**
- [x] `db/migration/V3__report_indexes.sql` -- índices `patient(registered_at, level)` y `audit_event(patient_id, event_type, occurred_at)` -- soporte de la consulta
- [x] `application/*` -- puerto, fila, caso de uso y excepción -- capa de aplicación hexagonal
- [x] `infrastructure/persistence/R2dbcOperationsReportRepository.java` -- CTE `in_range` → `first_consultation` (MIN sobre auditoría) → `waits` (LEFT JOIN) → agregación con `date_trunc`, `FILTER`, `AVG`, `percentile_cont(0.95)` -- RF-05 en una consulta
- [x] `infrastructure/web/*`, `config/BeanConfig.java` -- controller, DTO, handler 400 y bean -- contrato HTTP
- [x] `src/test/.../web/OperationsReportIntegrationTest.java` -- datos sintéticos del 2001-03-10 con resultados a mano (hora 10: total 5, niveles 2/1/1/0/1, promedio 18.75, p95 37.00; hora 11 sin consulta: null), límites del rango, primera consulta, offsets, 400 y 401 -- verificación contra PostgreSQL

**Acceptance Criteria:**
- Given los datos sintéticos, when se consulta `[10:00, 12:00)`, then hora 10 = total 5, promedio 18.75, p95 37.00 y hora 11 = promedio y p95 `null`.
- Given registros exactos en `desde` y `hasta`, when se consulta, then el primero entra y el segundo no.
- Given un parámetro ausente, inválido o `desde >= hasta`, when se consulta, then 400 con `campo`.

## Implementation Notes

- `date_trunc('hour', ts, 'UTC')` (PostgreSQL 12+) evita depender de la zona de la sesión.
- `percentile_cont` y `AVG` ignoran `NULL`, por eso el `LEFT JOIN` de pacientes sin consulta no requiere filtros adicionales.
- La hora de cada fila es la del registro: una consulta posterior al rango sigue contando en el bucket de su registro.
- Las horas sin pacientes se omiten (no se rellenan con ceros).
- El nombre del tipo de evento viene de `AuditEvent.PATIENT_IN_CONSULTATION`, el mismo que escribe la Story 1.3.

## Spec Change Log

## Review Triage Log

| # | Hallazgo (capa) | Veredicto | Evidencia | Ruta |
|---|---|---|---|---|
| 1 | Sin límite máximo de rango; consulta y `collectList()` sin tope (Blind, Edge) | low | El endpoint exige API key y las filas son una por hora con pacientes, acotadas por los datos reales. Un tope sería una decisión de contrato que el baseline no pide y agrega una rama | rechazado |
| 2 | Fechas fuera del rango de `timestamptz` dan 500 en vez de 400 (Edge) | low | Requiere años como -5000, que un cliente real no envía; la guarda agrega complejidad | rechazado |
| 3 | `+` sin codificar en el offset llega como espacio y se rechaza con 400 (Edge) | medium | Un `curl` con `+05:00` sin codificar es ISO 8601 válido y hoy falla. Corrección directa: reemplazar el espacio por `+`, con test | patch |
| 4 | Los niveles están escritos a mano en el SQL y en `toRow` (Blind, Edge) | low | Hoy coinciden con el enum. Se agrega un test que compara las claves de `porNivel` con `TriageLevel` | patch (test) |
| 5 | Sin test unitario del caso de uso con `verifyNoInteractions` (Blind) | low | Los 400 ya están cubiertos de punta a punta y la validación ocurre antes de la consulta | rechazado |
| 6 | `idx_audit_event_patient` queda redundante (Blind) | low | El índice nuevo empieza por `patient_id` y lo cubre; el viejo encarece cada inserción de auditoría. Se elimina en V3 | patch |
| 7 | El índice `(registered_at, level)` no permite index-only scan (Blind) | low | El índice es el que fija la historia; solo la nota de diseño afirmaba de más. Se corrige la nota | patch (doc) |
| 8 | `CREATE INDEX` sin `CONCURRENTLY` bloquea escrituras (Blind) | low | Las tablas están vacías o casi al aplicar la migración en este ejercicio | rechazado |
| 9 | La zona horaria UTC no se prueba con una sesión no UTC (Blind) | low | La consulta usa `date_trunc(..., 'UTC')` explícito; el test requeriría manipular la zona de la conexión | rechazado |
| 10 | Esperas negativas si `occurred_at < registered_at` (Blind, Edge) | low | Solo con datos corruptos o desfase de reloj entre instancias; el filtro cambiaría la semántica del promedio | rechazado |
| 11 | Sin log de error ni métrica de latencia del reporte (Blind) | low | El baseline pide contadores de clasificación y escalamiento; los errores ya los registra el framework | rechazado |
| 12 | La limpieza del test depende de la fecha < 2002 (Blind) | low | Los demás tests siembran datos con la fecha actual; no hay colisión | rechazado |
| 13 | Faltan sprint-status, Verification y documentación del endpoint (Blind) | low | El estado lo actualizo al integrar la rama; el README completo es la Story 4.4 | rechazado |

## Design Notes

El índice `audit_event(patient_id, event_type, occurred_at)` cubre el `MIN(occurred_at)` por paciente; `patient(registered_at, level)` acelera el filtro de rango; como la consulta también lee `id`, no es un index-only scan. En volúmenes pequeños el planificador puede preferir un seq scan; los índices son para escala.

## Verification

**Commands:**
- `cd triage-service && ./gradlew test` -- expected: BUILD SUCCESSFUL, los 134 tests previos más los 11 nuevos en verde (145 en total)
