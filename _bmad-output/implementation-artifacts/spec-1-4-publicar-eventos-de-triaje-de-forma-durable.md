---
title: 'Story 1.4: Publicar eventos de triaje de forma durable'
type: 'feature'
created: '2026-11-08'
status: 'done'
route: 'dispatch'
baseline_commit: 'd54c2b8a1fe76f49903a017684acdd2c092a5ad3'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/docs/adr/0005-outbox-y-contrato-kafka.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** El baseline exige publicar `paciente_registrado`, `paciente_en_consulta` y `paciente_alta` en `triaje.eventos` sin que Triage dependa de Kafka para confirmar operaciones. Hoy no se publica nada.

**Approach:** Usar un outbox transaccional (ADR-0005). El registro y cada transición válida insertan, en su misma transacción R2DBC, una fila en `outbox_event` con el envelope JSON v1 ya serializado, e incrementan `patient.aggregate_version`. Un único relay reactivo lee las filas pendientes en orden, las publica con `KafkaTemplate` (`acks=all`, idempotencia, clave `patientId`) y las marca publicadas solo después de la confirmación del broker. Si el broker no responde, reintenta con backoff. Si un evento falla de forma no reintentable N veces, lo envía al topic `triaje.eventos.dlt` y registra una métrica. Docker Compose suma Kafka en modo KRaft.

## Boundaries & Constraints

**Always:**
- **Envelope:** `{eventId, eventType, schemaVersion: 1, aggregateId, aggregateVersion, occurredAt (ISO UTC), payload: {patientId, nivel, estado, escalated}}`. No lleva nombre, edad, queja ni signos vitales. `eventId` es el id de la fila del outbox. `occurredAt` es el mismo instante que la auditoría.
- **Versión:** el registro deja la versión en 1. Cada transición aplica `aggregate_version = aggregate_version + 1` en el mismo UPDATE condicional y toma la versión nueva y `escalated` con `RETURNING`; no las calcula en memoria.
- **Orden:** el relay procesa en orden de `seq` (BIGSERIAL), uno por uno (`concatMap`). Ante el primer fallo corta el ciclo, así no publica una versión posterior antes que la anterior.
- **Clasificación del fallo:**
  - Una `org.apache.kafka.common.errors.RetriableException` (o un timeout) no suma intentos: el relay espera con backoff exponencial y reintenta el mismo evento.
  - Cualquier otro error suma `attempts` y guarda `last_error`. Al llegar a `max-attempts`, publica el envelope en `triaje.eventos.dlt`, marca `dead_lettered_at` y suma la métrica `triage.outbox.dead_letter`.
  - Si el envío al DLT falla, la fila queda pendiente.
- **Sin bloquear el event loop:** el relay corre en un scheduler propio o `boundedElastic`, porque `KafkaTemplate.send` puede bloquear esperando metadata. Debe tener `max.block.ms`, `request.timeout.ms` y `delivery.timeout.ms` acotados.
- **Configuración por propiedades (`triage.outbox.*`):** `enabled`, `interval` (1s), `batch-size` (100), `max-attempts` (5), `max-backoff` (30s). Kafka se configura con `KAFKA_BOOTSTRAP_SERVERS`.
- **Topics:** `triaje.eventos` y `triaje.eventos.dlt` se crean con beans `NewTopic` (3 particiones, réplica 1 en local).
- **Métricas Micrometer:** `triage.outbox.pending` (gauge que se actualiza en cada ciclo) y `triage.outbox.dead_letter` (counter). Ninguna usa ids de paciente como tag.
- **Disponibilidad:** el registro y las transiciones responden aunque Kafka esté caído. La readiness no incluye Kafka.

**Never:**
- Publicar directo desde los casos de uso, o antes o después del commit fuera del relay.
- Transacciones Kafka distribuidas.
- reactor-kafka.
- Varios relays en paralelo.
- `collectList()` sin límite.
- Datos personales en eventos o logs.
- `paciente_escalado` (es de la Story 1.5).
- Consumidores (son de la Epic 3).
- Cambiar los contratos HTTP existentes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Registro | POST válido | 201. Fila de outbox `paciente_registrado` en versión 1. En el topic: clave = patientId, envelope sin PII, `estado: REGISTRADO` | N/A |
| Transición | PATCH `EN_CONSULTA` y luego `ALTA` | Eventos `paciente_en_consulta` (versión 2) y `paciente_alta` (versión 3), en orden y en la misma partición | N/A |
| Rechazo | PATCH inválido (409), 400 o rollback por fallo de auditoría | No se crea ninguna fila de outbox y la versión no cambia | N/A |
| Broker caído | Kafka pausado y se registra un paciente | 201 en tiempo normal. La fila queda pendiente | Al reanudar el broker, se publica y se marca `published_at` |
| Evento venenoso | El publicador falla con un error no reintentable | `attempts` sube hasta `max-attempts`, luego va al DLT con `dead_lettered_at`, el counter sube y el relay sigue con la siguiente fila | Si el DLT también falla, la fila queda pendiente |
| Error reintentable | El publicador falla con `RetriableException` | `attempts` no cambia, se aplica backoff y se reintenta el mismo evento | N/A |

</frozen-after-approval>

## Code Map

- `triage-service/build.gradle` -- ya tiene `spring-kafka` y `spring-kafka-test`. Agregar `org.testcontainers:kafka`, con versión gestionada por Boot.
- `src/main/resources/db/migration/V1__patient_and_audit.sql` -- no se toca. Crear `V2__outbox_and_aggregate_version.sql`:
  - en `patient`: `aggregate_version BIGINT NOT NULL DEFAULT 1`;
  - tabla `outbox_event`: `id UUID PK`, `seq BIGSERIAL UNIQUE`, `aggregate_id UUID`, `aggregate_version BIGINT`, `event_type`, `payload TEXT` o `JSONB`, `created_at`, `published_at NULL`, `dead_lettered_at NULL`, `attempts INT DEFAULT 0`, `last_error TEXT NULL`;
  - índice parcial sobre `seq` para las filas pendientes.
- `domain/Patient.java` -- agregar `long aggregateVersion` al record y actualizar todos los constructores (main y test). `withStatus` recibe la versión nueva y `escalated`.
- `application/RegisterPatientUseCase.java` y `ChangePatientStatusUseCase.java` -- agregar `outbox.append(...)` dentro del mismo `.as(tx::transactional)`, después de la auditoría.
- `application/PatientRepositoryPort.updateStatus` -- hoy devuelve `Mono<Boolean>`. Pasa a devolver un valor con la versión nueva y `escalated` (`RETURNING`), o vacío si afectó 0 filas. Ajustar el caso de uso: vacío significa carrera perdida, con la lógica actual. Ajustar también `ChangePatientStatusUseCaseTest` y el test de la guarda SQL en `PatientStatusChangeIntegrationTest`.
- `infrastructure/persistence/R2dbcPatientRepository.java` -- `INSERT` con `aggregate_version`, `SELECT_BY_ID` que lo lee y `UPDATE_STATUS ... RETURNING`.
- Nuevos archivos:
  - `application/OutboxPort` (append);
  - `domain/OutboxEvent` o `TriageEventEnvelope`, con la construcción del envelope;
  - `infrastructure/persistence/R2dbcOutboxRepository` (append, `findPending(limit)`, `markPublished`, `recordFailure`, `markDeadLettered`, `countPending`);
  - `infrastructure/messaging/` con:
    - `EventPublisherPort`/`KafkaEventPublisher` (`Mono<Void> publish(topic, key, json)` sobre `KafkaTemplate<String,String>` con `Mono.fromFuture`);
    - `OutboxRelay` (bucle iniciado en `ApplicationReadyEvent` y detenido en shutdown);
    - `OutboxProperties`;
    - `KafkaTopicsConfig`.
  - El JSON se serializa con el `ObjectMapper` de Spring (fechas ISO).
- `application.yaml` -- `spring.kafka.bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`, productor con `StringSerializer`, `acks: all`, `enable.idempotence: true` y timeouts acotados, más el bloque `triage.outbox`.
- `docker-compose.yml` -- servicio `kafka` (`apache/kafka:3.9.0`, KRaft de un nodo, listener interno `kafka:9092`, healthcheck). `triage` recibe `KAFKA_BOOTSTRAP_SERVERS=kafka:9092` y `depends_on: kafka: condition: service_started` (no healthy: Triage no depende de Kafka).
- `src/test/.../PostgresTestSupport.java` -- agregar un `KafkaContainer` singleton (`org.testcontainers.kafka.KafkaContainer`, `apache/kafka:3.9.0`) y registrar `spring.kafka.bootstrap-servers`. Las ITs existentes no deben cambiar su comportamiento.
- `docs/eventos-triaje.md` -- nuevo documento con los topics, la clave, un ejemplo de envelope por tipo, la semántica al menos una vez, el orden por paciente, el DLT y las propiedades configurables. Enlaza ADR-0005.

## Tasks & Acceptance

**Execution:**
- [x] `V2__outbox_and_aggregate_version.sql` -- columna de versión y tabla de outbox -- persistencia del outbox
- [x] `domain/Patient.java` + constructores en main y test -- `aggregateVersion` -- versión del agregado
- [x] `application/OutboxPort`, el envelope y `R2dbcOutboxRepository` -- append en la transacción del caso de uso -- atomicidad
- [x] `RegisterPatientUseCase`, `ChangePatientStatusUseCase`, `PatientRepositoryPort`/`R2dbcPatientRepository` -- outbox y versión con `RETURNING` -- contrato ADR-0005
- [x] `infrastructure/messaging/*` -- publicador, relay con orden, backoff, DLT y métricas, propiedades, topics -- entrega al menos una vez
- [x] `application.yaml`, `docker-compose.yml` -- Kafka local y productor durable -- un solo comando
- [x] `docs/eventos-triaje.md` -- formato documentado -- AC de documentación
- [x] `PostgresTestSupport` -- Kafka con Testcontainers -- infraestructura de tests
- [x] `src/test/.../messaging/OutboxRelayTest.java` (unitario, publicador falso y reloj fijo) -- orden, corte al fallar, reintentable sin sumar intentos, no reintentable hasta el DLT, DLT fallido que queda pendiente
- [x] `src/test/.../messaging/OutboxKafkaIntegrationTest.java` -- consumidor de test sobre `triaje.eventos`. Cubre: registro y transiciones con versiones 1, 2 y 3; clave; ausencia de PII (el JSON no contiene el nombre ni la queja); `published_at` marcado; rechazo sin fila. Broker caído con `pause`/`unpause` del contenedor (vía `getDockerClient()`) y Awaitility.

**Acceptance Criteria:**
- Given cualquier transacción que hace rollback, when se consulta la DB, then no existe la fila de outbox y `aggregate_version` no cambió.
- Given `docker compose up --build`, when se registra un paciente, then el evento aparece en `triaje.eventos` (verificable con `kafka-console-consumer` dentro del contenedor).
- Given la suite completa, when corre, then los 134 tests previos (ajustados a la nueva firma) y los nuevos pasan.

## Implementation Notes

- Suite completa tras la revisión: 147 tests, 0 fallos.
- El contenedor de Kafka de los tests y el de Compose usan `apache/kafka:3.9.1` (la 3.9.0 no arranca con Testcontainers).
- Las demás integraciones desactivan el relay con `src/test/resources/application.properties`; solo `OutboxKafkaIntegrationTest` lo activa.
- `updateStatus` devuelve `StatusUpdate(aggregateVersion, escalated)` con `RETURNING`; vacío significa carrera perdida.
- Pendiente de verificar a mano: `docker compose up --build` de punta a punta con un consumidor de consola.

## Spec Change Log

## Review Triage Log

| # | Hallazgo (capa) | Veredicto | Evidencia | Ruta |
|---|---|---|---|---|
| 1 | Nada impide que dos réplicas ejecuten el relay a la vez (Blind, Edge) | medium | La spec fija un único relay; con dos réplicas habría duplicados y órdenes rotos. El despliegue es de la Story 1.6, donde se fijan las réplicas | patch (doc) + defer |
| 2 | `nivel` del evento puede quedar desactualizado (Blind, Edge) | false | Ningún código actualiza `patient.level` después del registro; el escalamiento solo cambia `escalated`, que sí viene de `RETURNING` | rechazado |
| 3 | Los topics pueden crearse con 1 partición si Kafka no estaba listo al arrancar (Blind, Edge) | low | La clave por `patientId` mantiene el orden con 1 partición. `service_started` es decisión de la spec para que Triage no dependa de Kafka. Queda documentado | patch (doc) |
| 4 | Un error reintentable pero permanente bloquea la cola (Edge) | false | Es el diseño: ante `NotEnoughReplicas` o `UnknownTopic` el broker está degradado y esperar es lo correcto; el DLT es solo para errores deterministas | rechazado |
| 5 | Tras una caída solo se drenan `batchSize` filas por intervalo (Edge) | low | Son 100 eventos por segundo, muy por encima del volumen de urgencias; el fix agrega una rama | rechazado |
| 6 | `nextDelay` puede lanzar y terminar el bucle en silencio (Edge) | low | Solo con configuración inválida; se resuelve validando las propiedades | patch (con 7) |
| 7 | `OutboxProperties` acepta valores no positivos (Edge) | medium | `batchSize <= 0` da un LIMIT negativo en cada ciclo e `interval = 0` hace un bucle ocupado. Se agrega `@Validated` | patch |
| 8 | Si el publish al DLT falla con error reintentable se omite el backoff (Edge) | low | Solo ocurre justo al agotar los reintentos y el intervalo de 1 s limita el efecto | rechazado |
| 9 | `recordFailure` vacío permitiría publicar eventos posteriores (Edge) | false | Ningún código borra filas del outbox, así que la fila siempre existe | rechazado |
| 10 | DLT publicado pero `markDeadLettered` falla republica en el DLT (Edge) | low | La entrega es al menos una vez; un duplicado en el DLT es aceptable | rechazado |
| 11 | Pacientes previos a V2 no tienen evento de registro (Edge) | low | No hay datos previos en este ejercicio; la base de Azure se crea vacía | rechazado |
| 12 | El ejemplo de `occurredAt` en la doc no coincide con Jackson (Edge) | low | `ISO_INSTANT` omite los ceros finales. Corrección directa de la doc | patch (doc) |
| 13 | El DLT no lleva headers de diagnóstico ni procedimiento de redrive (Blind) | low | `last_error` y `attempts` quedan en la fila; se documenta el procedimiento SQL | patch (doc) |
| 14 | Hueco de versión tras un dead-letter sin regla para el consumidor (Blind) | low | El ADR-0005 ya define el snapshot completo y la métrica; se referencia en la doc | patch (doc) |
| 15 | Un evento venenoso bloquea a todos los pacientes (Blind) | low | Es el costo explícito del orden estricto; se documenta el peor caso | patch (doc) |
| 16 | El outbox crece sin purga (Blind) | low | Real a escala, no en este ejercicio | defer |
| 17 | Sin `UNIQUE (aggregate_id, aggregate_version)` ni índice por agregado (Blind) | medium | La invariante de versión monotónica no la protege el esquema; el índice único sirve también para consultas por paciente | patch |
| 18 | El SQL de `recordFailure`, `markDeadLettered` y `countPending` solo se prueba con un fake (VerifGap, Blind) | medium | Pre-verificado: ningún test ejecuta ese SQL contra Postgres | patch |
| 19 | La clasificación reintentable no se prueba con errores envueltos reales (VerifGap) | medium | Pre-verificado: solo se prueba una `NetworkException` sin envolver y el test de broker caído no revisa `attempts` | patch |
| 20 | Falta test unitario de `RegisterPatientUseCase` (Blind) | low | El IT de Kafka ya verifica versión 1, nivel, clave y envelope | rechazado |
| 21 | Compose usa Kafka 3.9.0 y los tests 3.9.1 (Blind) | low | Conviene probar lo que se ejecuta localmente | patch |
| 22 | El puerto de Kafka queda abierto en todas las interfaces (Blind) | medium | PLAINTEXT sin autenticación; se enlaza a 127.0.0.1 | patch |
| 23 | El bootstrap por defecto `localhost:9092` no coincide con el puerto del host 9094 (Blind) | medium | `./gradlew bootRun` nunca entregaría eventos | patch |
| 24 | Réplicas y particiones de los topics no son configurables (Blind) | low | Se resuelve en la Story 1.6 con Terraform y propiedades | defer |
| 25 | Todos los tests de integración arrancan Kafka (Blind) | low | Es un costo de segundos y se paga una sola vez por ejecución | rechazado |
| 26 | Estado compartido estático y umbral de 5 s en el test de broker caído (Blind) | low | El umbral solo detectaría un registro bloqueado por Kafka, que tardaría al menos 5 s | rechazado |
| 27 | Un fallo de `markPublished` se registra como problema del broker (Blind) | low | Es solo un mensaje de log | rechazado |
| 28 | El contexto con `MockitoSpyBean` mantiene el relay activo en otras suites (VerifGap, observación) | maybe-false | No se pudo verificar un daño; no hay evidencia de flakiness | rechazado |

## Design Notes

Hay un solo relay con corte en el primer fallo, y el backoff se aplica a todo el relay, no por fila. Así se preserva el orden por paciente sin necesidad de bloqueos. Un broker caído no cuenta como intento (`RetriableException`), así que no se manda al DLT un evento sano después de una caída larga. El DLT queda solo para errores deterministas (por ejemplo, `RecordTooLargeException`, serialización o autorización).

Bucle de referencia (orientativo):
```java
Mono.defer(this::runCycle)          // concatMap sobre findPending(batch)
    .onErrorResume(e -> Mono.empty())
    .then(Mono.defer(() -> Mono.delay(nextDelay())))
    .repeat().subscribeOn(scheduler).subscribe();
```

## Verification

**Commands:**
- `cd triage-service && ./gradlew test` -- expected: BUILD SUCCESSFUL, 0 fallos
- `docker compose config -q` -- expected: sin errores (requiere `.env`)
