# ADR-0007: Proyección de urgencias en Redis reconstruida desde Kafka

- Estado: Aceptada
- Fecha: 2026-11-08
- Alcance: Asistente Clínico, `GET /asistente/estado-urgencias`

## Contexto

El enunciado pide el número de pacientes activos (estado distinto de `ALTA`) por nivel, reconstruido consumiendo `triaje.eventos` y persistido en Redis u otra base NoSQL, sin llamar a la API de Triage. Kafka entrega al menos una vez y puede haber duplicados, reordenamientos de reintentos o versiones saltadas ([ADR-0005](0005-outbox-y-contrato-kafka.md)).

## Decisión

El Asistente consume el topic y aplica cada evento a Redis con un script Lua atómico. Guarda por paciente el último snapshot y su `aggregateVersion`; ignora `eventId` ya aplicado y versiones menores o iguales, y deriva los deltas de conteo comparando con el snapshot previo. Una versión saltada se aplica con el snapshot completo y se cuenta en una métrica. El offset se confirma solo tras escribir en Redis; un fallo transitorio de Redis reentrega con backoff, uno determinista va a `triaje.eventos.dlt` con cabeceras `x-dlt-*`. El endpoint siempre devuelve los cinco niveles y responde 503 si Redis no está disponible. Las claves comparten el hash tag `{urgencias}`.

## Consecuencias

- La proyección es idempotente y reconstruible por replay con un grupo de consumo nuevo, dentro de la retención del topic.
- Redis se asume de un solo nodo: el hash tag deja todas las claves en un slot (sin escalado horizontal de la proyección).
- Las versiones se comparan en Lua (doubles): se rechazan versiones mayores a 2^53.
- En AKS se debe exigir autenticación y TLS de Redis (`REDIS_PASSWORD`, `REDIS_SSL`); pendiente en el perfil `azure` (ver `deferred-work.md`).

## Alternativas descartadas

- Consultar la API o la base de Triage: viola el desacoplamiento exigido y haría al Asistente dependiente de Triage.
- Contadores incrementales sin snapshot por paciente: no toleran duplicados ni versiones saltadas.

Detalle, métricas y runbook: [`docs/estado-urgencias.md`](../estado-urgencias.md).
