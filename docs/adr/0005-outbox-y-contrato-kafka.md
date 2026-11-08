# ADR-0005: Outbox transaccional y contrato de eventos Kafka

- Estado: Aceptada
- Fecha: 2026-11-07
- Alcance: Triage, Asistente Clínico y topic `triaje.eventos`

## Contexto

Triage debe persistir el resultado de sus casos de uso sin depender de Kafka; Asistente consume eventos para construir su proyección y no puede leer la API/base de Triage. Un write local seguido de un publish directo puede perder el evento si el proceso falla entre ambas operaciones. Kafka puede volver a entregar un evento, por lo que el consumidor debe tolerar duplicados.

## Decisión

Triage escribirá mutación de paciente, auditoría y mensaje de salida en una sola transacción PostgreSQL reactiva. Un publicador separado lee el outbox y publica a `triaje.eventos`; marcará el registro como publicado solo tras confirmación del broker. El publish reintenta con backoff y el backlog genera métrica/alerta. Se inicia con un relay para preservar orden por paciente; al escalarlo se serializa por `aggregateId` para no invertir versiones. El consumidor compara cada snapshot recibido con el estado previamente guardado, ignora versiones repetidas/antiguas y deriva los deltas de conteo; el snapshot completo permite recuperarse ante una versión saltada, que de todos modos debe generar métrica/alerta.

El contrato es un envelope JSON versionado: `eventId` UUID, `eventType`, `schemaVersion`, `aggregateId` (patientId), `aggregateVersion` monotónica por paciente, `occurredAt` UTC y un snapshot mínimo completo del nuevo estado en `payload` (`patientId`, `nivel`, `estado`, `escalated`). La clave Kafka es `patientId`, preservando orden por agregado. Los únicos tipos iniciales son `paciente_registrado`, `paciente_escalado`, `paciente_en_consulta` y `paciente_alta`; no se incluyen datos personales ni signos vitales.

El productor usa confirmación durable (`acks=all`, idempotencia habilitada) según capacidades del broker. La entrega global es al menos una vez. Asistente ignora `eventId` ya aplicado y solo materializa una versión mayor que la versión almacenada. Un mensaje inválido o no soportado se reintenta de forma acotada y va a dead-letter con alarma; no se descarta sin registro.

## Consecuencias

- La API confirma tras persistencia local aunque Kafka esté temporalmente caído; el evento se entrega después de recuperarse el broker.
- Una transacción distribuida no es necesaria. Puede haber duplicados en Kafka, cubiertos por idempotencia del consumidor.
- Deben monitorizarse edad y tamaño del outbox, retraso de consumer group y dead-letter topic.
- Redis puede reconstruirse mediante replay dentro del horizonte de retención definido para el topic; no se ofrece replay anterior a esa retención.
- El esquema debe evolucionar de forma compatible; cambios incompatibles requieren nueva versión y coordinación de consumidores.

## Alternativas consideradas

- **Publicar Kafka directamente después del commit:** puede perder eventos si el proceso cae antes de publicar.
- **Kafka transaction/distributed transaction entre PostgreSQL y Kafka:** aumenta complejidad y no vuelve atómico el estado de Redis del consumidor; no se adopta.
- **Publicación directa antes del commit:** puede emitir eventos para cambios que luego hacen rollback; no se adopta.

## Fuentes técnicas

- [Spring Kafka 3.3: listeners asíncronos (`Mono` desde 3.2)](https://docs.spring.io/spring-kafka/reference/3.3/kafka/receiving-messages/async-returns.html)
- [Spring Kafka 3.3: contenedores de listener](https://docs.spring.io/spring-kafka/reference/3.3/kafka/receiving-messages/message-listener-container.html)
