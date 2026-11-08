# Eventos de triaje (topic `triaje.eventos`)

Contrato de los eventos que publica Triage. Decisión de diseño: [ADR-0005](adr/0005-outbox-y-contrato-kafka.md).

## Topics

| Topic | Uso | Particiones |
|-------|-----|-------------|
| `triaje.eventos` | Eventos de dominio | 3 (réplica 1 en local) |
| `triaje.eventos.dlt` | Eventos con fallo no reintentable tras `max-attempts` | 3 (réplica 1 en local) |

- **Clave Kafka:** `patientId`. Todos los eventos de un paciente caen en la misma partición y se leen en orden de `aggregateVersion`.
- **Tipos emitidos hoy:** `paciente_registrado`, `paciente_escalado`, `paciente_en_consulta`, `paciente_alta`.

## Envelope v1

```json
{
  "eventId": "6f1c1f0e-5a43-4d57-9a43-2f0b3c0f8a11",
  "eventType": "paciente_registrado",
  "schemaVersion": 1,
  "aggregateId": "0b7c2c1e-6f77-4a38-8f4e-1d6a1f3c9b52",
  "aggregateVersion": 1,
  "occurredAt": "2026-11-08T10:15:30.123456Z",
  "payload": {
    "patientId": "0b7c2c1e-6f77-4a38-8f4e-1d6a1f3c9b52",
    "nivel": "NO_URGENTE_2",
    "estado": "REGISTRADO",
    "escalated": false
  }
}
```

`paciente_en_consulta` (versión 2) y `paciente_alta` (versión 3) tienen la misma forma, con `estado` `EN_CONSULTA` y `ALTA`:

```json
{"eventId":"…","eventType":"paciente_en_consulta","schemaVersion":1,"aggregateId":"0b7c2c1e-…","aggregateVersion":2,"occurredAt":"2026-11-08T10:20:00.123456Z","payload":{"patientId":"0b7c2c1e-…","nivel":"NO_URGENTE_2","estado":"EN_CONSULTA","escalated":false}}
{"eventId":"…","eventType":"paciente_alta","schemaVersion":1,"aggregateId":"0b7c2c1e-…","aggregateVersion":3,"occurredAt":"2026-11-08T10:40:00.654321Z","payload":{"patientId":"0b7c2c1e-…","nivel":"NO_URGENTE_2","estado":"ALTA","escalated":false}}
```

- `eventId` es el id de la fila del outbox; sirve para deduplicar.
- `aggregateVersion` es monotónica por paciente (+1 por cada cambio publicable) y la asigna la base de datos en el mismo `UPDATE` del cambio.
- `occurredAt` es UTC y coincide con el instante de la auditoría.
- El payload es un snapshot mínimo del nuevo estado. No incluye nombre, edad, queja ni signos vitales.

## paciente_escalado

Se emite una sola vez por paciente cuando, en estado `REGISTRADO`, el tiempo desde el registro **supera estrictamente** su SLA (exactamente igual no escala). El escalamiento no cambia `estado`, `nivel` ni `status_changed_at`; solo marca `escalated` y suma 1 a `aggregateVersion`.

```json
{"eventId":"…","eventType":"paciente_escalado","schemaVersion":1,"aggregateId":"0b7c2c1e-…","aggregateVersion":2,"occurredAt":"2026-11-08T10:20:30.123456Z","payload":{"patientId":"0b7c2c1e-…","nivel":"CRITICO","estado":"REGISTRADO","escalated":true}}
```

- La auditoría correspondiente usa `event_type = paciente_escalado` con `previous_status = new_status = REGISTRADO`.
- Un scheduler reactivo por instancia ejecuta un `UPDATE ... RETURNING` condicional con `FOR UPDATE SKIP LOCKED` y `LIMIT batch-size`; auditoría y outbox se insertan en la misma transacción. Es seguro con varias instancias.
- Métrica `triage.escalamientos{nivel}` (counter). Los logs solo llevan conteos, sin datos personales.

### Propiedades de escalamiento (`triage.escalation.*`, `triage.sla.minutes.*`)

| Propiedad | Defecto | Descripción |
|-----------|---------|-------------|
| `triage.escalation.enabled` | `true` | Activa el scheduler |
| `triage.escalation.interval` | `30s` | Espera entre ciclos (backoff exponencial acotado a 5 min si un ciclo falla) |
| `triage.escalation.batch-size` | `100` | Pacientes por lote; si el lote vuelve lleno se repite en el mismo ciclo |
| `triage.sla.minutes.<NIVEL>` | `5/10/30/60/120` | SLA por nivel (`CRITICO`, `URGENTE`, `URGENTE_2`, `NO_URGENTE`, `NO_URGENTE_2`); se persiste en `patient.sla_minutes` al registrar |

Un valor no positivo impide el arranque.

Notas operativas:

- El SLA queda congelado en `patient.sla_minutes` al registrar: cambiar `triage.sla.minutes.*` solo afecta a pacientes nuevos.
- El escalamiento usa el reloj de la aplicación; se asume que las instancias tienen NTP sincronizado.
- Varias instancias con `enabled=true` son seguras gracias a `FOR UPDATE SKIP LOCKED`.
- Sobrescribir el SLA de niveles con guion bajo (`URGENTE_2`, `NO_URGENTE_2`) mediante variables de entorno NO está verificado; usar un archivo de propiedades hasta verificarlo.

## Garantías

- **Al menos una vez.** El relay marca `published_at` solo después de la confirmación del broker; si el proceso cae entre ambos pasos, el evento se vuelve a publicar. Los consumidores deben ignorar `eventId` ya aplicado y versiones menores o iguales a la almacenada.
- **Orden por paciente.** Un único relay procesa por `seq` y corta el ciclo en el primer fallo, así que no publica una versión posterior antes que la anterior. Las transiciones de un mismo paciente se serializan por el bloqueo de fila del `UPDATE`.
- **Triage no depende de Kafka.** Registro y transiciones confirman al persistir paciente, auditoría y outbox en una transacción; con el broker caído los eventos quedan pendientes y se entregan al recuperarse. La readiness no incluye Kafka.

## Reintentos y dead-letter

- Error reintentable (`RetriableException` o timeout, p. ej. broker caído): no suma intentos; el relay espera con backoff exponencial (`interval`, duplicando hasta `max-backoff`) y reintenta el mismo evento.
- Error no reintentable (p. ej. `RecordTooLargeException`, serialización, autorización): suma `attempts` y guarda `last_error`. Al llegar a `max-attempts` el envelope se publica en `triaje.eventos.dlt`, se marca `dead_lettered_at` y sube el counter `triage.outbox.dead_letter`; el relay sigue con la siguiente fila. Si el envío al DLT falla, la fila queda pendiente.

## Propiedades (`triage.outbox.*`)

| Propiedad | Defecto | Descripción |
|-----------|---------|-------------|
| `enabled` | `true` | Activa el relay |
| `interval` | `1s` | Espera entre ciclos |
| `batch-size` | `100` | Filas leídas por ciclo |
| `max-attempts` | `5` | Fallos no reintentables antes del DLT |
| `max-backoff` | `30s` | Tope del backoff |

Kafka se configura con `KAFKA_BOOTSTRAP_SERVERS` (defecto `localhost:9094`, el listener del host del Compose). Con `docker compose up`, Triage usa `kafka:9092`; desde el host (p. ej. `bootRun`) el broker se publica solo en `127.0.0.1:9094`.

## Métricas

- `triage.outbox.pending` (gauge): filas sin publicar ni en dead-letter, actualizada en cada ciclo.
- `triage.outbox.dead_letter` (counter): eventos enviados al DLT.

## Verificación manual

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic triaje.eventos --from-beginning --property print.key=true
```

## Limitaciones conocidas

- **Una sola réplica del relay.** Solo una réplica de Triage puede ejecutar el relay (usar `replicas=1` o agregar un bloqueo en la base de datos antes de escalar); con varias se duplican eventos y se rompe el orden.
- **Bloqueos por diseño.** Un evento venenoso bloquea el relay durante unos `max-attempts` ciclos (aprox. `max-attempts` × `interval`), y un broker caído detiene toda la publicación para preservar el orden por paciente.
- **Salto de versión tras un dead-letter.** Los consumidores verán un hueco de versión para ese paciente: deben aplicar el siguiente snapshot (estado completo) y contar el hueco como métrica, según el ADR-0005.
- **Reprocesar una fila en dead-letter.** Tras corregir la causa (`last_error` tiene el motivo):
  `UPDATE outbox_event SET dead_lettered_at = NULL, attempts = 0 WHERE id = '...';`
- **Creación de topics.** KafkaAdmin los crea al arrancar; si Kafka estaba caído en ese momento, el broker puede autocrearlos con sus valores por defecto (1 partición). El orden por clave se mantiene.
