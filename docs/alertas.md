# Alertas de escalamiento (RF-06)

Decisión de diseño: [ADR-0002](adr/0002-alertas-medicas-sse.md). Este documento cubre la persistencia y la consulta
(Story 3.1) y el stream SSE (Story 3.2).

## Creación

Al escalar un paciente por SLA (Story 1.5) se inserta una fila en `medical_alert` en la misma transacción que el flag,
la auditoría y el outbox. Si falla cualquiera, se revierte todo. `UNIQUE (patient_id)` impide una segunda alerta por
paciente.

## Cursor de entrega (`deliveryCursor`)

BIGINT monotónico. Se asigna incrementando la fila singleton `medical_alert_cursor` con `SELECT ... FOR UPDATE`, que
se mantiene hasta el commit: no se confirma un cursor posterior antes que uno anterior y un rollback no deja huecos.
Un lote de k pacientes recibe k cursores consecutivos.

## `GET /triage/alertas`

Requiere `X-API-Key` (401 sin ella, cuerpo vacío).

| Parámetro  | Tipo   | Default | Regla       |
|------------|--------|---------|-------------|
| `despuesDe`| entero | 0       | >= 0        |
| `limite`   | entero | 50      | 1 a 200     |

Devuelve las alertas con `deliveryCursor > despuesDe`, en orden ascendente, hasta `limite`. Sin resultados: `[]` con 200.
Parámetro inválido: 400 ProblemDetail con la propiedad `campo`.

```json
[
  {
    "alertId": "8f14e45f-ceea-467a-9575-1a1c9c7d6b9e",
    "deliveryCursor": 7,
    "patientId": "0b6a0c0e-5b0e-4a41-9d83-1d1f2f4f6a10",
    "nivel": "CRITICO",
    "timestamp": "2026-11-08T10:15:30.123456Z"
  }
]
```

Solo esas cinco claves: sin nombre, edad, queja ni signos vitales. `timestamp` es el instante del escalamiento (UTC).

## Recuperación y deduplicación

Para recuperar alertas tras una desconexión, el cliente consulta con `despuesDe` = último cursor procesado. La entrega
puede repetirse, por lo que los clientes deduplican por `alertId`. No hay endpoints para modificar o borrar alertas.

## `GET /triage/alertas/stream` (SSE)

`text/event-stream`, autenticado con `X-API-Key` (401 con cuerpo vacío sin ella). La tabla `medical_alert` es la única
fuente: cada conexión mantiene su propio cursor y consulta `findAfter(cursor, batch-size)` cada `poll-interval`
(un lote lleno se repite sin esperar). No hay `Sink` ni estado local, así que funciona tras reinicios y con varias réplicas.

Cada alerta es un evento con `id: <deliveryCursor>`, `event: alerta` y `data` con el mismo JSON de cinco claves de
`GET /triage/alertas` (`alertId`, `deliveryCursor`, `patientId`, `nivel`, `timestamp`).

```
id: 7
event: alerta
data: {"alertId":"...","deliveryCursor":7,"patientId":"...","nivel":"CRITICO","timestamp":"..."}
```

### `Last-Event-ID`

- Sin el header: solo alertas nuevas (parte del cursor máximo actual).
- Con un entero >= 0: entrega primero las alertas con cursor mayor, en orden, y luego las nuevas. Los navegadores
  (`EventSource`) lo envían solos al reconectar.
- Un cursor mayor al máximo existente se recorta al máximo (el cliente no queda mudo).
- No numérico, con signo (`+5`), negativo o de más de 18 dígitos: 400 ProblemDetail con `campo: Last-Event-ID`, sin abrir el stream.

### Heartbeat

Cada `heartbeat-interval` se envía el comentario `:heartbeat` (sin `id`, por lo que no avanza el cursor del cliente).

### Garantías

- Entrega al menos una vez: puede haber repeticiones; el cliente deduplica por `alertId`.
- Cliente lento: el catch-up es dirigido por demanda (el siguiente lote se consulta solo cuando el cliente consumió el
  anterior), así que un backlog grande no desborda nada. El buffer por conexión es acotado (`buffer-size`) y cubre
  los heartbeats: un cliente que deja de leer se cancela (con un log) y debe reconectar con `Last-Event-ID`.
- Un error de la base termina el stream; el cliente reconecta con `Last-Event-ID` y no pierde alertas.
- Métrica `triage.alertas.sse.conexiones` (gauge de conexiones activas).

### Propiedades `triage.alertas.stream.*`

| Propiedad            | Default | Regla    |
|----------------------|---------|----------|
| `poll-interval`      | 1s      | positiva |
| `heartbeat-interval` | 15s     | positiva |
| `batch-size`         | 100     | positiva |
| `buffer-size`        | 256     | positiva, `batch-size` <= `buffer-size` |

### Detrás de un proxy inverso

Desactivar el buffering de la respuesta y no cachear: la respuesta ya envía `Cache-Control: no-cache`; en nginx
agregar `proxy_buffering off;` (o enviar `X-Accel-Buffering: no`) y un `proxy_read_timeout` mayor al
`heartbeat-interval`.

### Ejemplo de cliente (reconexión y deduplicación)

```js
const seen = new Set();
// EventSource reenvía Last-Event-ID solo al reconectar; para enviar X-API-Key usar fetch/SSE con headers.
async function connect(lastId) {
  const res = await fetch('/triage/alertas/stream', {
    headers: { 'X-API-Key': KEY, Accept: 'text/event-stream', ...(lastId != null && { 'Last-Event-ID': lastId }) },
  });
  // ... parsear eventos; por cada evento 'alerta':
  //   const a = JSON.parse(data); if (seen.has(a.alertId)) return; seen.add(a.alertId); lastId = id; mostrar(a);
  // Si la conexión se cierra o falla (incluso por ser un cliente lento), reconectar con connect(lastId).
}
```
