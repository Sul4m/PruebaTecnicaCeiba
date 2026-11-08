---
title: 'Story 3.2: Recibir alertas en vivo por SSE con recuperación'
type: 'feature'
created: '2026-11-08'
status: 'done'
route: 'dispatch'
baseline_commit: '126a9fb'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/docs/adr/0002-alertas-medicas-sse.md'
  - '{project-root}/docs/alertas.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** RF-06 exige alertar al equipo médico cuando se escala un paciente. La Story 3.1 persiste las alertas y las expone por consulta, pero un cliente conectado no se entera hasta que consulta.

**Approach:** `GET /triage/alertas/stream` (`text/event-stream`) lee la tabla `medical_alert` con polling reactivo acotado y emite cada alerta con `id: <deliveryCursor>` y datos mínimos. Con `Last-Event-ID` entrega primero las alertas con cursor mayor y luego las nuevas; sin él entrega solo las nuevas. Un heartbeat periódico mantiene viva la conexión. La tabla es la única fuente de verdad (ADR-0002): no hay `Sink` local, así que funciona tras reinicios y con varias réplicas.

## Boundaries & Constraints

**Always:**
- El stream autentica con la misma API key (`X-API-Key`); sin clave válida, 401 con cuerpo vacío.
- Cada evento: `id` = `deliveryCursor`, `event` = `alerta`, `data` = JSON con exactamente `alertId`, `deliveryCursor`, `patientId`, `nivel` y `timestamp` (el mismo contrato que `GET /triage/alertas`). Sin nombre ni signos vitales.
- Cada conexión mantiene su propio cursor `ultimoEnviado` y consulta `findAfter(ultimoEnviado, batch)` cada `poll-interval`. Si el lote viene lleno, repite sin esperar. Nunca lee más de `batch-size` filas por consulta.
- Sin `Last-Event-ID`: empieza en el cursor máximo actual, es decir, solo alertas nuevas. Con `Last-Event-ID` numérico ≥ 0: parte de ese cursor. Si no es numérico o es negativo: 400 con `campo: Last-Event-ID`.
- Heartbeat: comentario SSE (`:heartbeat`) cada `heartbeat-interval`, sin `id`, para que no avance el cursor del cliente.
- Cliente lento: el buffer por conexión es acotado y, si se desborda, la conexión se cancela con un log; el cliente se recupera con `Last-Event-ID`. Nunca crece sin límite.
- Métrica Micrometer `triage.alertas.sse.conexiones` (gauge de conexiones activas), que sube al conectar y baja al cerrarse o cancelarse.
- Propiedades `triage.alertas.stream.*`: `poll-interval` (1s), `heartbeat-interval` (15s), `batch-size` (100), `buffer-size` (256). Validadas como positivas al arrancar.
- La entrega es al menos una vez: el contrato documenta que puede haber repeticiones y que el cliente deduplica por `alertId`.
- Reactivo, sin `block()` ni hilos propios. Logs sin PII: solo cursor, `alertId` y conteos.

**Never:**
- Un `Sink`, `Processor` o cola en memoria como fuente de verdad de las alertas.
- Kafka como fuente del stream.
- Cambios al contrato de `GET /triage/alertas`, al escalamiento o a la migración V5.
- Un endpoint para modificar o borrar alertas.
- Una suscripción por réplica que dependa del estado local.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Alerta en vivo | Cliente conectado y se escala un paciente | Recibe un evento con `id` = cursor y los 5 campos, dentro de `poll-interval` más margen | N/A |
| Sin header | Alertas anteriores ya existentes, sin `Last-Event-ID` | No recibe las anteriores; solo las nuevas | N/A |
| Reconexión | `Last-Event-ID: n` con alertas n+1..n+k pendientes | Recibe primero n+1..n+k en orden y luego las nuevas | N/A |
| Sin pérdidas | Cliente desconectado durante varias escalaciones y reconectado | Recibe todas las del intervalo, sin saltos de cursor | N/A |
| Varios clientes | Dos conexiones simultáneas | Ambas reciben la misma alerta | N/A |
| Heartbeat | Sin alertas durante `heartbeat-interval` | Llega un comentario `:heartbeat` sin `id` | N/A |
| Header inválido | `Last-Event-ID: abc` o `-1` | 400 con `campo: Last-Event-ID` | Sin abrir el stream |
| Sin API key | cualquiera | 401 con cuerpo vacío | N/A |
| Cliente lento | Buffer acotado desbordado | Se cancela la conexión; la métrica baja | Recuperación por `Last-Event-ID` |
| Métrica | Conectar y desconectar | El gauge sube y baja | N/A |

</frozen-after-approval>

## Code Map

- `application/AlertRepositoryPort.java` -- ya tiene `findAfter(cursor, limit)`; agregar `Mono<Long> findMaxCursor()` (0 si no hay alertas) y su implementación R2DBC en `R2dbcAlertRepository`.
- `application/AlertStreamService.java` (nuevo) -- genera el `Flux<MedicalAlert>` por conexión: cursor inicial, polling con `Flux.defer`/`repeat` y `Mono.delay`, lotes llenos sin espera. No depende de Spring Web.
- `infrastructure/web/AlertStreamController.java` (nuevo) -- `GET /triage/alertas/stream` con `produces = TEXT_EVENT_STREAM_VALUE`; lee `Last-Event-ID`; compone alertas y heartbeat con `ServerSentEvent`; aplica el buffer acotado y la métrica. Reutilizar `AlertaResponse` de la 3.1 y el mapeo a 400 con `campo` de `ApiExceptionHandler`.
- `infrastructure/config/AlertStreamProperties.java` (nuevo) -- `@Validated`, siguiendo el patrón de `OutboxProperties`.
- `infrastructure/observability/` -- gauge de conexiones, igual que las métricas existentes (puerto y adaptador).
- `infrastructure/config/BeanConfig.java` y `infrastructure/security/SecurityConfig.java` -- cablear el servicio. La API key ya cubre `/triage/**`; verificar que el stream también pasa por el filtro.
- `src/test/resources/application.properties` -- propiedades cortas para los tests (poll de ~100 ms, heartbeat de ~300 ms).
- `docs/alertas.md` -- agregar el contrato del stream, `Last-Event-ID`, el heartbeat y la deduplicación por `alertId`. No editar `deferred-work.md` ni `sprint-status.yaml`.

## Tasks & Acceptance

**Execution:**
- [x] `AlertRepositoryPort.findMaxCursor` + `R2dbcAlertRepository` -- cursor inicial sin header -- solo alertas nuevas
- [x] `AlertStreamService` + `AlertStreamProperties` -- polling acotado por conexión -- sin `Sink`
- [x] `AlertStreamController`, gauge de conexiones, `BeanConfig` -- SSE con `id`, heartbeat, buffer acotado y 400 por header inválido -- contrato HTTP
- [x] `docs/alertas.md` -- contrato del stream documentado -- RF-06
- [x] `src/test/.../AlertStreamIntegrationTest.java` -- las filas de la matriz con `WebTestClient` y `StepVerifier`, usando `Awaitility` o tiempos virtuales para la cadencia. Escalar con el caso de uso real. Verificar `id`, el `event` y los 5 campos exactos. Desconexión y reconexión con `Last-Event-ID` sin pérdidas. Dos clientes. Heartbeat sin `id`. Métrica
- [x] `src/test/.../AlertStreamServiceTest.java` -- unitario con un puerto falso: lote lleno sin espera, cursor avanza, error de la base se propaga o reintenta según el diseño elegido y queda documentado

**Acceptance Criteria:**
- Given que el servicio se reinicia, when un cliente se reconecta con `Last-Event-ID`, then recibe las alertas persistidas posteriores a ese cursor (la tabla es la fuente).
- Given la suite completa, when corre, then los 198 tests previos y los nuevos pasan.

## Implementation Notes

- Suite completa tras la revisión: 218 tests, 0 fallos.
- El catch-up respeta la demanda del cliente: solo el heartbeat usa un buffer acotado con `DROP_LATEST`; un desborde completa el stream sin error y con un log.
- `Last-Event-ID` se valida con `^[0-9]{1,18}$` y el cursor inicial se acota al máximo actual.
- El test del cliente lento suscribe el controlador directamente, no por HTTP: los buffers de TCP absorben los heartbeats pequeños de un cliente que no lee.
- El 400 de un parámetro inválido sale como `application/problem+json`, aunque el endpoint produce `text/event-stream`.
- Limitación: el heartbeat es de intervalo fijo y no se reinicia tras cada alerta.

## Spec Change Log

## Review Triage Log

| # | Hallazgo (capa) | Veredicto | Evidencia | Ruta |
|---|---|---|---|---|
| 1 | Un cliente que reconecta con más alertas pendientes que `buffer-size` se desborda, se cancela y repite en bucle (Blind, Edge, VerifGap) | high | El buffer pide demanda ilimitada aguas arriba: el drenaje de lotes llenos lo llena más rápido de lo que escribe la red, y la recuperación con `Last-Event-ID` nunca termina | patch |
| 2 | El desborde por cliente lento no tiene test ni se comprueba que el gauge vuelva a su valor (VerifGap, Blind) | medium | Pre-verificado: ningún test usa un `buffer-size` pequeño ni un cliente que no pide | patch (con 1) |
| 3 | Un cursor puede saltarse alertas confirmadas fuera de orden (Blind, Edge claim) | false | La Story 3.1 asigna los cursores bajo el lock del contador, retenido hasta el commit: el orden de commit coincide con el orden de cursor. `AlertsIntegrationTest` lo prueba con ciclos concurrentes sin huecos | rechazado |
| 4 | `batchSize` mayor que `bufferSize` desborda el buffer con un solo lote (Edge) | low | Validación cruzada de una línea al arrancar | patch |
| 5 | `Last-Event-ID` con `+5` o rango excesivo se acepta; un cursor mayor que el máximo deja al cliente mudo (Blind, Edge) | medium | `Long.parseLong` acepta `+5`, contra el contrato "entero ≥ 0"; un cursor futuro nunca recibiría alertas nuevas | patch |
| 6 | Sin test del `Content-Type` del 400, y el gauge compartido entre tests es frágil (Blind, VerifGap) | low | Se mide el gauge contra una base y se verifica `application/problem+json` | patch |
| 7 | Documentación incompleta: proxies con buffering, ejemplo de cliente (Blind) | low | Nota breve en `docs/alertas.md` | patch |
| 8 | Sin límite de conexiones ni polling compartido (Blind, Edge) | low | El endpoint exige API key y el volumen es de 12 salas; un tope sería una propiedad nueva | rechazado |
| 9 | Un error de base de datos a mitad del stream no tiene backoff ni test de controlador (Blind, VerifGap) | low | El servicio propaga el error y el cliente reconecta con `Last-Event-ID`, como documenta el ADR-0002 | rechazado |
| 10 | Otros errores del endpoint SSE podrían dar 406 por el tipo de contenido (Blind, Edge) | low | Solo se manifiesta si `findMaxCursor` falla antes del primer evento | rechazado |
| 11 | Sin métricas de desbordes, errores o reconexiones (Blind) | low | El baseline pide el gauge de conexiones; las demás serían ampliación | rechazado |
| 12 | Validación de propiedades sin piso mínimo para los intervalos; gauge puede quedar negativo (Edge) | low | Sin evidencia de que `doFinally` ocurra sin su `doOnSubscribe` | rechazado |
| 13 | `findMaxCursor` usa `MAX` y no el contador (VerifGap) | low | Con la tabla sin borrados, ambos coinciden; el efecto de un hueco es inofensivo | rechazado |

## Design Notes

El polling por conexión es simple y suficiente para el volumen de urgencias (12 salas). Cada consulta es indexada por `delivery_cursor` y acotada por `batch-size`. Se descartó `LISTEN/NOTIFY` y un `Sink` compartido: el primero agrega estado de conexión por réplica y el segundo pierde la recuperación tras un reinicio, que el ADR-0002 exige resolver con la tabla.

## Verification

**Commands:**
- `cd triage-service && ./gradlew test` -- expected: BUILD SUCCESSFUL, 0 fallos
