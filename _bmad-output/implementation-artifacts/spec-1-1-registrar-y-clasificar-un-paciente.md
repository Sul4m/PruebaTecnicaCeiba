---
title: 'Story 1.1: Registrar y clasificar un paciente'
type: 'feature'
created: '2026-11-07'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: '03461f52b7e9889717a4fc1b99dbb7bb0fcd6512'
context:
  - '{project-root}/docs/requirements/technical-test-baseline.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** El triaje de VidaCruz es manual y la clasificación varía. Triage no tiene todavía ningún endpoint, persistencia ni reglas.

**Approach:** Implementar `POST /triage/pacientes` en el esqueleto WebFlux con arquitectura hexagonal liviana: validar entrada (rangos RF-01), clasificar con RN-01 y asignar SLA con RN-02 en un dominio puro, y persistir paciente y auditoría inicial en una transacción R2DBC. Se protege con API key, se agrega observabilidad mínima y se levanta con Docker Compose junto a PostgreSQL. El PDF/baseline es la fuente de verdad.

## Boundaries & Constraints

**Always:**
- Respetar exactamente los rangos, comparadores inclusivos/exclusivos, el orden de gravedad y la lista administrativa del baseline. El nivel 5 aplica solo si los niveles 1-3 no se cumplen y la queja, normalizada (trim, minúsculas, sin tildes, espacios colapsados), es igual a un ítem de la lista.
- Respuesta 201 con `id` (UUID), `nivel` (`CRITICO`…`NO_URGENTE_2`), `slaMinutos`, `estado: REGISTRADO` y `fechaRegistro` ISO 8601 UTC.
- Contrato del request (el PDF no fija nombres): `nombreCompleto`, `edad`, `quejaPrincipal` y `signosVitales{frecuenciaCardiaca, presionSistolica, presionDiastolica, spo2, temperatura, dolor, gcs}`. `temperatura` es decimal (`BigDecimal`) y el resto son enteros.
- Los errores usan `ProblemDetail` con la propiedad `campo` (ruta JSON, p. ej. `signosVitales.spo2`) y un mensaje que nombra el campo.
- Paciente y auditoría se guardan en una única transacción reactiva (`TransactionalOperator`/`@Transactional` reactivo). Todo `Instant` sale de un `Clock` inyectable, en UTC.
- Header `X-API-Key` validado contra `triage.security.api-key` (env `TRIAGE_API_KEY`). `/actuator/health/**` queda público y el resto de actuator y la API exigen la clave.
- No bloquear el event loop. Flyway (JDBC) solo ejecuta migraciones al arrancar.

**Never:**
- Nada de Kafka, outbox ni publicación de eventos (Story 1.4). Nada de GET/PATCH (1.2/1.3), scheduler/escalamiento (1.5) ni tablas distintas de `patient` y `audit_event`.
- No loggear nombre, signos vitales, queja ni API key. No hardcodear secretos (la clave local va en `.env`, ignorado por Git, con `.env.example` versionado).
- No crear endpoint de borrado de auditoría.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| E1-E6 | Vitales del baseline, nombre/edad válidos | Niveles CRITICO, URGENTE, URGENTE_2, NO_URGENTE, NO_URGENTE_2, CRITICO; SLA 5/10/30/60/120 | N/A |
| Prevalece gravedad | SpO2 85 + dolor 8 | CRITICO | N/A |
| Admin con gravedad | "Vacunación" + FC 95 | URGENTE_2 (no nivel 5) | N/A |
| Fronteras | FC 39/40/120/121, SpO2 89/90/93/94, sistólica 89/90/100/101, temp 37.9/38.0/38.4/38.5, GCS 8/9/13/14, dolor 6/7 | Nivel según RN-01 exacto | N/A |
| Fuera de rango | Cualquier campo fuera de su rango o ausente | 400, `campo` identifica el campo; nada persistido | ProblemDetail |
| Diastólica ≥ sistólica | 120/120 | 400 `campo: signosVitales.presionDiastolica` | ProblemDetail |
| JSON mal formado | cuerpo inválido | 400 | ProblemDetail |
| Sin/clave errónea | sin `X-API-Key` | 401 sin cuerpo con datos | N/A |

</frozen-after-approval>

## Code Map

- `triage-service/build.gradle` -- Boot 3.5.16, Java 17 toolchain. Ya trae webflux, r2dbc, r2dbc-postgresql, security, validation, actuator y kafka. Agregar flyway-core, flyway-database-postgresql, postgresql (JDBC, runtime), micrometer-tracing-bridge-otel y Testcontainers (postgresql, junit-jupiter, `spring-boot-testcontainers`). No tocar la dependencia Kafka.
- `triage-service/settings.gradle` -- si el toolchain 17 no está instalado (la máquina tiene JDK 21), agregar el plugin `org.gradle.toolchains.foojay-resolver-convention`.
- `triage-service/src/main/java/com/vidacruz/triage_service/TriageServiceApplication.java` -- punto de entrada; reutilizar.
- `triage-service/src/main/resources/application.yaml` -- hoy solo tiene el nombre. Agregar R2DBC/Flyway por env, API key, logging estructurado ECS y context propagation.
- `triage-service/src/test/.../TriageServiceApplicationTests.java` -- `contextLoads` necesitará Postgres: usar la config de Testcontainers compartida.
- No existe `docker-compose.yml` ni `Dockerfile` (los crea esta historia). `clinical-assistant-service` no se toca.

## Tasks & Acceptance

**Execution:**
- [x] `triage-service/build.gradle`, `settings.gradle` -- dependencias y toolchain -- habilitar migraciones, tracing y tests de integración
- [x] `.../domain/` (`Patient`, `VitalSigns`, `TriageLevel` con `slaMinutes`, `PatientStatus`, `TriageClassifier`, `AuditEvent`) -- dominio puro sin Spring -- RN-01/RN-02 testeables
- [x] `.../application/RegisterPatientUseCase` + puertos `PatientRepositoryPort`, `AuditRepositoryPort` -- orquestar clasificación, `Clock` y transacción -- hexagonal ADR-0001
- [x] `.../infrastructure/persistence/` -- adaptadores R2DBC (`DatabaseClient` o repositorios) -- persistencia reactiva
- [x] `src/main/resources/db/migration/V1__patient_and_audit.sql` -- `patient` (UUID, datos, nivel, sla, estado, `escalated` false, `registered_at`, `status_changed_at` timestamptz) y `audit_event` (UUID, `patient_id` FK, `previous_status` null, `new_status`, `event_type`, `occurred_at`) -- solo tablas de 1.1
- [x] `.../infrastructure/web/` -- `PatientController`, DTOs con Bean Validation, validador cruzado diastólica<sistólica, `@RestControllerAdvice` con ProblemDetail -- contrato HTTP
- [x] `.../infrastructure/security/` -- `SecurityWebFilterChain` y filtro/`ReactiveAuthenticationManager` de API key, CSRF off, comparación en tiempo constante -- NFR4
- [x] `.../infrastructure/observability/` -- contador `triage.clasificaciones{nivel}` -- NFR6
- [x] `src/main/resources/application.yaml` -- config por env (`DB_HOST`, `DB_USER`, `DB_PASSWORD`, `TRIAGE_API_KEY`), `logging.structured.format.console: ecs`, `spring.reactor.context-propagation: auto`, probes de liveness/readiness -- NFR6
- [x] `triage-service/Dockerfile` (multi-stage, usuario no root), `docker-compose.yml` en la raíz (postgres:16 con healthcheck + triage en 8080), `.env.example`, `.gitignore` con `.env` -- un comando local
- [ ] Tests: unitarios de `TriageClassifier` (E1-E6, fronteras, nivel 5, prevalencia) y de validación; integración `@SpringBootTest` + WebTestClient + Testcontainers Postgres (201 + filas en ambas tablas, 400 sin persistencia, 401, métrica incrementada) -- NFR7

**Acceptance Criteria:**
- Given un registro válido, when se confirma, then `patient` y `audit_event` (estado anterior nulo, nuevo `REGISTRADO`, `event_type: paciente_registrado`) existen y comparten timestamp UTC.
- Given `docker compose up` con `.env`, when se envía E1 con `X-API-Key`, then responde 201 `CRITICO` y `/actuator/health/readiness` está UP.
- Given cualquier request, when se loguea, then la salida es JSON ECS con `traceId` y sin PII ni clave.

## Design Notes

El clasificador evalúa en orden 1→2→3 y retorna el primero que cumple. Si ninguno cumple, devuelve 5 cuando la queja administrativa coincide y 4 en caso contrario. Temperatura con `compareTo` de `BigDecimal`: nivel 2 `>= 38.5` y nivel 3 `>= 38.0 && < 38.5`. SpO2 nivel 2 cubre `90..93` y sistólica nivel 2 cubre `90..100` (inclusivos). El evento de auditoría `paciente_registrado` usa el mismo nombre del evento Kafka futuro para que 1.4 no renombre nada.

## Verification

**Commands:**
- `cd triage-service && ./gradlew test` -- expected: BUILD SUCCESSFUL, tests de E1-E6, fronteras, 400/401 e integración en verde
- `docker compose up -d --build && curl -s -XPOST localhost:8080/triage/pacientes -H 'X-API-Key: ...' -H 'Content-Type: application/json' -d @E1.json` -- expected: 201 con `nivel: CRITICO`

## Implementation Notes

## Spec Change Log

## Review Triage Log

| # | Capa | Hallazgo | Veredicto | Evidencia | Ruta |
|---|---|---|---|---|---|
| 1 | VG + Blind | Rollback paciente+auditoría sin test | medium | Ningún test hace fallar `audits.save`; quitar `tx::transactional` no rompe nada | patch |
| 2 | VG | Columnas de `patient` nunca se leen de vuelta | medium | El test solo consulta `audit_event`; intercambiar binds no rompe nada | patch |
| 3 | VG | `accept-float-as-int: false` sin test | low | Todos los bodies usan enteros; quitar la clave pasa los tests | patch |
| 4 | VG + Blind | Ternario muerto en `validEdgeValuesReturn201` | low | Ambas ramas idénticas | patch |
| 5 | Blind | Mensajes de validación en inglés según el locale de la JVM | low | `messageFor` concatena `getDefaultMessage()`; WebFlux no fija locale y temurin usa `en` | patch |
| 6 | Edge | `campo` principal ordenado alfabéticamente oculta el error de rango real y el orden no es determinista con dos restricciones en el mismo campo | low | `Comparator.comparing(FieldError::getField)` pone `presionDiastolica` antes que `presionSistolica`; sin desempate por código | patch |
| 7 | Edge | Carácter NUL en nombre/queja produce 500 | low | PostgreSQL rechaza `\u0000` en VARCHAR; ningún validador lo filtra | patch |
| 8 | Edge | Espacios Unicode (NBSP) rompen el match administrativo | low | `trim()` y `\\s` no cubren U+00A0 | patch |
| 9 | Edge | `/actuator/health/**` con API key errónea responde 401 | low | `AuthenticationWebFilter` usa `anyExchange()` y falla antes de `permitAll` | patch |
| 10 | Blind | Falta el borde válido diastólica = sistólica − 1 | low | Solo se prueba 120/120 inválido | patch |
| 11 | Blind | `spring-boot-testcontainers` sin usar | low | `PostgresTestSupport` usa `@DynamicPropertySource` | patch |
| 12 | Blind | Coerción de strings numéricos (`"15"`) y campos desconocidos | false | El valor resultante es idéntico; no hay clasificación errónea | rechazado |
| 13 | Blind | Temperatura sin precisión acotada | low | El PDF solo fija rango 34.0-42.0; `BigDecimal` clasifica exacto. Sin daño práctico | rechazado |
| 14 | Blind | Sin `CHECK` en el esquema | low | Integridad cubierta por la única vía de escritura; agrega complejidad | rechazado |
| 15 | Blind + Edge | Dominio/clasificador sin validar nulos | false | El único llamador es el controlador validado; no hay otro adaptador de escritura | rechazado |
| 16 | Blind | Reintento duplica paciente | false | El PDF no exige idempotencia de registro; dos registros pueden ser legítimos | rechazado |
| 17 | Blind | 201 sin `Location` | low | El PDF fija solo el cuerpo; agrega superficie pública | rechazado |
| 18 | Blind | Edad no influye en la clasificación | false | RN-01 del PDF no distingue por edad | rechazado |
| 19 | Blind | API key sin largo mínimo / sin separación de roles | low | `.env.example` es placeholder; roles fuera de alcance del PDF | rechazado |
| 20 | Blind | Dockerfile sin caché de dependencias, sin HEALTHCHECK | low | Comodidad de build; probes se definen en manifests (1.6) | rechazado |
| 21 | Blind | Compose usa superusuario de Postgres | low | Entorno local de demo | rechazado |
| 22 | Blind | Muestreo de trazas 100% sin exportador | low | Bridge usado solo para `traceId` en logs; demo | rechazado |
| 23 | Edge | Métrica que lanza tras el commit devuelve 500 | false | `Counter.increment` de Micrometer no lanza en uso normal | rechazado |
