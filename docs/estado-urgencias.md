# Estado de urgencias (`GET /asistente/estado-urgencias`)

El Asistente Clinico consume `triaje.eventos` ([contrato](eventos-triaje.md)) y mantiene en Redis la cantidad de pacientes activos (estado distinto de `ALTA`) por nivel. Nunca llama a la API ni a la base de Triage.

## Contrato

`GET /asistente/estado-urgencias` con cabecera `X-API-Key` (clave del Asistente, `ASISTENTE_API_KEY`).

```json
{"niveles":{"CRITICO":1,"URGENTE":0,"URGENTE_2":0,"NO_URGENTE":0,"NO_URGENTE_2":0},"totalActivos":1}
```

- Siempre contiene los cinco niveles, con cero cuando no hay pacientes.
- `401` (cuerpo vacio): sin clave o clave incorrecta.
- `503` ProblemDetail ("Servicio no disponible"): Redis no disponible. El cuerpo no incluye la causa.

## Variables de entorno

| Variable | Defecto | Descripcion |
|----------|---------|-------------|
| `ASISTENTE_API_KEY` | (obligatoria) | Clave propia del Asistente; sin ella el servicio no arranca |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | Broker Kafka |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis de la proyeccion |
| `REDIS_PASSWORD` | vacio | Contrasena de Redis |
| `REDIS_SSL` | `false` | TLS hacia Redis |
| `ASISTENTE_KAFKA_GROUP_ID` | `clinical-assistant-urgencias` | Grupo de consumo |
| `ASISTENTE_PROJECTION_ENABLED` | `true` | `false` desactiva consumidor y topics; la readiness de Kafka queda UP ("deshabilitado") |

Readiness: incluye Kafka y Redis; no depende del proveedor LLM.

## Dead-letter (`triaje.eventos.dlt`)

Reciben el mensaje original (misma clave) los mensajes invalidos, con `eventType` o `schemaVersion` no soportados, y los que fallan de forma deterministica tras `max-attempts`. Un fallo transitorio de Redis NO va al DLT: el offset no se confirma y el mensaje se reentrega con backoff.

| Cabecera | Contenido |
|----------|-----------|
| `x-dlt-reason` | `invalido`, `tipo_no_soportado`, `schema_no_soportado` o `proyeccion_fallida` |
| `x-dlt-exception` | Clase de la excepcion |
| `x-dlt-original-topic` / `-partition` / `-offset` | Origen del mensaje |
| `x-dlt-null-value` | `true` si el mensaje no tenia valor (tombstone) |

## Metricas

- `assistant.projection.events{result}`: `aplicado`, `aplicado_con_salto`, `duplicado`, `version_antigua`.
- `assistant.projection.version_gaps`: versiones saltadas (snapshot completo aplicado).
- `assistant.projection.dead_letter{reason}`: mensajes enviados al DLT.

## Runbook

**Reconstruir Redis.** La proyeccion es reconstruible desde el topic.
1. Detener el Asistente (o el consumidor).
2. Borrar las claves de la proyeccion: `{urgencias}:counts`, `{urgencias}:patient:*` y `{urgencias}:events:*`.
3. Arrancar con un grupo de consumo NUEVO (`ASISTENTE_KAFKA_GROUP_ID`) que lee desde el offset mas antiguo.

Reproducir sobre un Redis NO vacio solo choca con la guarda de version y no corrige nada. El alcance depende de la retencion del topic: no se puede reconstruir mas atras de lo retenido.

**Reprocesar el DLT.** Corregida la causa, republicar los mensajes de `triaje.eventos.dlt` en `triaje.eventos` con la misma clave (`patientId`). La guarda de version y el `eventId` hacen idempotente el reproceso.

## Supuestos

- Todas las claves comparten el hash tag `{urgencias}` para que el script Lua sea valido en Redis Cluster; eso las deja en un unico slot, por lo que se asume un Redis de un solo nodo (sin escalado horizontal de la proyeccion).
- `aggregateVersion` se compara en Lua (doubles): se rechazan versiones mayores a 2^53.
