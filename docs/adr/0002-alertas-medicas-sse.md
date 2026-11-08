# ADR-0002: Alertas de escalamiento por SSE con recuperación

- Estado: Aceptada
- Fecha: 2026-11-07
- Alcance: Servicio Triage, RF-06

## Contexto

RF-06 exige alertar al equipo médico cuando se escala un paciente, pero deja canal y formato abiertos. El backend no incluye frontend ni cuentas de correo/SMS/webhook ya configuradas. Kafka es obligatorio para integración entre servicios, pero publicar el evento por sí solo no define cómo un consumidor médico recibe una alerta.

## Decisión

Triage expondrá un flujo autenticado de Server-Sent Events, por ejemplo `GET /triage/alertas/stream`, y un endpoint autenticado de consulta de alertas persistidas, por ejemplo `GET /triage/alertas`. Al escalar, persistirá la alerta en la misma transacción del cambio/auditoría/outbox y publicará `paciente_escalado` a través del outbox. Cada alerta conserva `alertId` UUID y `deliveryCursor` monotónico. El cursor se asigna incrementando una fila contadora singleton bajo lock transaccional que se mantiene hasta commit; esto serializa brevemente la inserción de alertas para que no se confirme un cursor posterior antes que uno anterior. SSE usa `deliveryCursor` como campo `id` y valor de `Last-Event-ID`; la consulta histórica pagina con ese cursor. El payload incluye solo `alertId`, id de paciente, nivel y timestamp, omitiendo signos vitales y datos personales innecesarios. Los clientes deduplican por `alertId`, porque la entrega puede repetirse. El flujo vivo puede leer incrementalmente de la tabla persistida en lugar de depender de memoria local.

## Consecuencias

- El canal es implementable y verificable en un backend reactivo sin proveedor de mensajería externa.
- SSE cubre notificación a clientes conectados; la persistencia cubre recuperación, pero no equivale a SMS, push ni aviso fuera de línea.
- La API debe autenticar el flujo y la consulta; el diseño global fija API key independiente por servicio.
- La consistencia entre alerta/auditoría y Kafka queda cubierta por [ADR-0005](0005-outbox-y-contrato-kafka.md).
- SSE no promete entrega exactly-once ni avisa a clientes desconectados; el historial permite recuperar alertas después de reconectar.
