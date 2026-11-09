---
title: 'Story 3.3 - Consultar la carga activa de urgencias por nivel'
type: 'feature'
created: '2026-11-08'
status: 'done'
route: 'dispatch'
baseline: 'docs/requirements/technical-test-baseline.md (RF-04B)'
context:
  - 'docs/adr/0005-outbox-y-contrato-kafka.md'
  - 'docs/eventos-triaje.md'
  - '_bmad-output/planning-artifacts/architecture/solution-design-PruebaTecnicaCeiba.md'
---

<frozen-after-approval reason="human-owned intent - do not modify unless human renegotiates">

## Intent

**Problem:** El equipo medico no puede saber cuantos pacientes activos hay por nivel de triaje sin consultar el sistema de admision, y el Asistente no debe llamar a la API ni a la base de Triage (RF-04B).

**Approach:** El Asistente consume `triaje.eventos` (envelope v1, clave `patientId`) con un listener reactivo y mantiene en Redis una proyeccion reconstruible: ultimo estado por paciente (nivel, estado, `aggregateVersion`, `eventId` procesados) y conteos por nivel, actualizados de forma atomica con un script Lua que solo aplica versiones mayores y ajusta los conteos comparando el snapshot nuevo con el previo. `GET /asistente/estado-urgencias` lee solo esa proyeccion.

## Boundaries & Constraints

**Always:**
- Offset confirmado solo despues de que Redis aplico el cambio (o de que el mensaje quedo en `triaje.eventos.dlt`). El listener devuelve `Mono` y el ack es manual.
- Escrituras a Redis solo via script Lua atomico: guarda de version (`aggregateVersion` > materializada), idempotencia por `eventId`, conteos por diferencia de snapshot. `paciente_registrado` suma al nivel; `paciente_escalado` y `paciente_en_consulta` no cambian el conteo; `paciente_alta` resta una sola vez. Activo = estado distinto de `ALTA`.
- Duplicados y versiones viejas no alteran conteos. Una version saltada aplica el snapshot completo y se registra (metrica `assistant.projection.version_gaps` + log WARN).
- Mensaje invalido, `eventType` o `schemaVersion` no soportados => `triaje.eventos.dlt` con cabeceras `x-dlt-*`, metrica `assistant.projection.dead_letter{reason}` y log ERROR sin contenido del mensaje. Nunca se descarta en silencio. Fallo de Redis => reintentos acotados (`max-attempts`, backoff exponencial) y luego DLT.
- El endpoint exige la API key del Asistente (mismo filtro que `/asistente/consulta`) y devuelve los cinco niveles, incluidos los de cero. Redis caido => 503 ProblemDetail.
- Readiness incluye Kafka y Redis, NO el proveedor LLM. Redis reactivo (Lettuce), sin bloqueo del event loop.
- Configuracion por variables de entorno (`KAFKA_BOOTSTRAP_SERVERS`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_SSL`, `ASISTENTE_KAFKA_GROUP_ID`, `ASISTENTE_PROJECTION_ENABLED`); nada hardcodeado.
- Logs y claves sin PII: el evento solo lleva identificadores y snapshot minimo.

**Ask First:** cambiar el envelope v1, los nombres de topics o el contrato `GET /asistente/estado-urgencias`.

**Never:** tocar `triage-service`, `docs/adr`, `sprint-status.yaml` ni `deferred-work.md`; llamar a Triage desde el Asistente; crear `.env`; confirmar offset antes de Redis.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Registro | `paciente_registrado` v1, nivel N | conteo(N) +1 | N/A |
| Escalado / en consulta | v2 mismo nivel y paciente | conteo sin cambio | N/A |
| Alta | `paciente_alta` v3 | conteo(N) -1 una sola vez | N/A |
| Duplicado | mismo `eventId` | sin cambios (`duplicado`) | N/A |
| Version vieja | version <= materializada | sin cambios (`version_antigua`) | N/A |
| Version saltada | version > materializada + 1, o primera vista > 1 | snapshot completo aplicado; metrica + log | N/A |
| Alta sin estado previo | v3 `ALTA` de paciente desconocido | conteo sin cambio, nunca negativo | N/A |
| Mensaje invalido / tipo o schema no soportado | JSON roto, campos faltantes, estado incoherente, `schemaVersion` != 1 | DLT con cabeceras; offset confirmado tras publicar | DLT caido: no se confirma, se reintenta sin limite |
| Redis caido al consumir | error de conexion/timeout | reintentos acotados, luego DLT | metrica y log |
| Consulta | GET con API key valida | 200 `{niveles:{CRITICO..NO_URGENTE_2}, totalActivos}` con ceros | Redis caido: 503 |
| Sin/incorrecta API key | GET sin `X-API-Key` | 401 cuerpo vacio | N/A |

</frozen-after-approval>

## Code Map

Paquete base `com.vidacruz.clinical_assistant_service` (`clinical-assistant-service/src/main/...`):

- `domain/` - `NivelTriaje`, `EstadoPaciente`, `EventoTriaje`, `ResultadoProyeccion`, `EstadoUrgencias`, `EventoInvalidoException`, `ProyeccionNoDisponibleException`.
- `application/ProyeccionUrgenciasService` + `application/port/out/ProyeccionUrgenciasPort` - casos de uso (aplicar evento, estado con los cinco niveles).
- `infrastructure/projection/RedisProyeccionUrgencias` + `resources/lua/apply-triage-event.lua` - adaptador Redis; claves con hash tag `{urgencias}`.
- `infrastructure/kafka/` - `TriageEventParser` (validacion estricta v1), `TriageEventListener` (Mono, reintentos, DLT), `KafkaProjectionConfig` (topics, error handler), `KafkaHealthIndicator`.
- `infrastructure/config/ProjectionProperties` - `assistant.projection.*`.
- `infrastructure/web/EstadoUrgenciasController|Response`, `ApiExceptionHandler` (503).
- `application.yaml` - Redis, Kafka, readiness (`readinessState,redis,kafka`).
- Raiz: `docker-compose.yml` (`redis`, `clinical-assistant`), `clinical-assistant-service/Dockerfile`, `.dockerignore`, `.env.example` (`ASISTENTE_API_KEY`).
- Tests: `TriageEventParserTest`, `ProyeccionUrgenciasServiceTest`, `RedisProyeccionContainersIT`, `ProyeccionKafkaRedisContainersIT`; `src/test/resources/config/application.yaml` desactiva la proyeccion en las pruebas ordinarias.

## Tasks & Acceptance

**Execution:**
- [x] Dominio, puertos y servicio de aplicacion.
- [x] Script Lua + adaptador Redis reactivo.
- [x] Parser v1 estricto y listener reactivo con reintentos acotados y DLT.
- [x] Endpoint `GET /asistente/estado-urgencias` y 503 si Redis falla.
- [x] Readiness con Kafka y Redis (sin LLM).
- [x] `docker-compose.yml` con Redis y Asistente; Dockerfile del Asistente.
- [x] Pruebas unitarias y de integracion (Testcontainers Redis + Kafka).
- [ ] Prueba de replay completo del topic con grupo nuevo (documentado; no automatizado).
- [ ] Verificacion manual `docker compose up --build` de punta a punta.

**Acceptance Criteria:**
- Given eventos v1 ordenados de varios pacientes, then el endpoint refleja los conteos por nivel con ceros.
- Given un duplicado o version vieja, then los conteos no cambian.
- Given una version saltada, then se aplica el snapshot completo y sube la metrica de saltos.
- Given una alta, then resta una sola vez aunque se reentregue.
- Given un mensaje invalido o no soportado, then llega al DLT con motivo.
- Given una peticion sin API key, then 401.

## Verification

**Commands:**
- `cd clinical-assistant-service && ./gradlew test` -- las pruebas `*ContainersIT` requieren Docker; el resto no.
- `docker compose config -q` (con `DB_USER`, `DB_PASSWORD`, `TRIAGE_API_KEY`, `ASISTENTE_API_KEY` definidas).
