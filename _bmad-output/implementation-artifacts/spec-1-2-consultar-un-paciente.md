---
title: 'Story 1.2: Consultar un paciente'
type: 'feature'
created: '2026-11-07'
status: 'done'
route: 'oneshot'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** RF-02 exige `GET /triage/pacientes/{id}` con datos completos, estado actual, flags (p. ej. escalado) y tiempo en el estado actual. Hoy solo existe el registro.

**Approach:** Agregar la lectura por id al puerto y adaptador R2DBC existentes, un caso de uso de consulta y el endpoint protegido por la API key ya configurada. La respuesta incluye `id`, `nombreCompleto`, `edad`, `quejaPrincipal`, `signosVitales` (mismos nombres que el request), `nivel`, `slaMinutos`, `estado`, `fechaRegistro`, `fechaCambioEstado`, `flags.escalado` y `minutosEnEstadoActual` (minutos completos desde `status_changed_at`, calculados con el `Clock` inyectable). Un UUID inexistente devuelve 404 ProblemDetail y un id mal formado devuelve 400 con `campo: id`. No hay migraciones nuevas.

</frozen-after-approval>

## Implementation Notes

- Puerto `PatientRepositoryPort.findById` y su adaptador R2DBC: los `SMALLINT` se castean a `INT` en el SELECT para mapearlos a `Integer` sin depender del codec int2.
- `GetPatientUseCase` lanza `PatientNotFoundException`, que `ApiExceptionHandler` traduce a 404 ProblemDetail ("Paciente no encontrado").
- Un id mal formado ya no cae en el mensaje de "JSON mal formado": el handler detecta el `@PathVariable` y responde 400 con `campo: id`.
- `minutosEnEstadoActual` se calcula en `PacienteDetalleResponse.from(patient, clock.instant())`: son minutos completos y nunca negativos.
- Tests: `PatientQueryIntegrationTest` (6 casos: datos completos, minutos desde el cambio de estado, flag escalado, 404, 400, 401) y `PacienteDetalleResponseTest` (fronteras de minutos con reloj fijo). Suite completa: 108 tests, 0 fallos.

## Review Triage Log

| # | Hallazgo (Blind Hunter) | Veredicto | Evidencia | Ruta |
|---|---|---|---|---|
| 1 | `slaMinutos` sale del enum y no de la columna `sla_minutes` | low | Hoy el enum y la columna valen lo mismo; divergen cuando los SLA sean configurables (Story 1.5) | defer |
| 2 | La lectura de datos clínicos no deja rastro en logs | low | `get` no logueaba nada; se agregó un log con id y estado, sin PII | patch |
| 3 | La regla de minutos vive en el DTO web | low | Es trivial, ya tiene test unitario con reloj fijo; moverla no corrige un defecto | rechazado |
| 4 | Falta test unitario de `GetPatientUseCase` | low | El 404 ya está cubierto de punta a punta; agregarlo no aporta | rechazado |
| 5 | Las fechas del GET se verifican de forma débil | low | Si se intercambiaran `fechaCambioEstado` y `fechaRegistro` el test pasaba; ahora se verifica la diferencia de 450 s | patch |
| 6 | El contrato 404/400 está verificado a medias | low | No se verificaban `detail`, `title` ni `status`; se agregaron | patch |
| 7 | `pathVariableFrom` ignora `name()` | false | El único uso es `@PathVariable("id")`, con `value` definido | rechazado |
| 8 | Un enum inválido en la DB produce 500 | false | Solo la app escribe esas columnas, con `name()` del enum | rechazado |
| 9 | Imports desordenados | low | Corrección directa | patch |
| 10 | El DTO es `public` sin necesidad | low | Es consistente con `PacienteRegistradoResponse`; no causa daño | rechazado |
| 11 | El README no documenta el endpoint | low | El README completo está planificado en la Story 4.4 | rechazado |
