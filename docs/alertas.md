# Alertas de escalamiento (RF-06)

Decisión de diseño: [ADR-0002](adr/0002-alertas-medicas-sse.md). Este documento cubre la persistencia y la consulta
(Story 3.1); el stream SSE llega en la Story 3.2.

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
