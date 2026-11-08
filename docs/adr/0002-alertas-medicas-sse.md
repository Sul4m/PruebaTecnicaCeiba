# ADR-0002: Alertas de escalamiento por SSE con recuperación

- Estado: Aceptada
- Fecha: 2026-11-07
- Alcance: Servicio Triage, RF-06

## Contexto

RF-06 exige alertar al equipo médico cuando se escala un paciente, pero deja canal y formato abiertos. El backend no incluye frontend ni cuentas de correo/SMS/webhook ya configuradas. Kafka es obligatorio para integración entre servicios, pero publicar el evento por sí solo no define cómo un consumidor médico recibe una alerta.

## Decisión

Triage expondrá un flujo autenticado de Server-Sent Events, por ejemplo `GET /triage/alertas/stream`, y un endpoint autenticado de consulta de alertas persistidas, por ejemplo `GET /triage/alertas`. Al escalar, persistirá la alerta y publicará `paciente_escalado` en `triaje.eventos`. El evento SSE llevará identificador de alerta, id de paciente, nivel y timestamp, evitando incluir signos vitales o datos personales innecesarios. El endpoint de consulta permitirá recuperar alertas tras desconexiones; los consumidores podrán usar `Last-Event-ID` para reanudar el flujo cuando corresponda.

## Consecuencias

- El canal es implementable y verificable en un backend reactivo sin proveedor de mensajería externa.
- SSE cubre notificación a clientes conectados; la persistencia cubre recuperación, pero no equivale a SMS, push ni aviso fuera de línea.
- La API debe aplicar autenticación y autorización al flujo y a la consulta. El mecanismo concreto de autenticación sigue pendiente.
- La persistencia de alerta y la publicación Kafka deben tratarse de forma que fallos parciales no pierdan silenciosamente la alerta; la estrategia transaccional/reintentos queda para el diseño de eventos.
