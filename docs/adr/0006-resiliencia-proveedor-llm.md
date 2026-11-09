# ADR-0006: Resiliencia del proveedor LLM con circuit breaker y bulkhead

- Estado: Aceptada
- Fecha: 2026-11-08
- Alcance: Asistente Clínico, `POST /asistente/consulta`

## Contexto

El enunciado exige Resilience4j Circuit Breaker alrededor del proveedor LLM y un fallback explícito HTTP 503. Una consulta depende de dos operaciones con perfiles distintos: embeddings (búsqueda) y chat (generación). Un proveedor lento o caído no debe degradar el resto del servicio (`estado-urgencias`) ni afectar a Triage.

## Decisión

`LlmGuard` envuelve cada llamada con timeout, timer Micrometer, circuit breaker y bulkhead de Resilience4j, con una instancia independiente por operación (`chat`, `embeddings`). El bulkhead no tiene cola (`max-wait: 0`): circuito abierto o saturación rechazan de inmediato. Todo fallo se traduce a `AssistantUnavailableException` y se responde 503 ProblemDetail sin contenido clínico ni detalle del proveedor. La abstención (200) y la validación (400) no cuentan como fallo. Los errores 4xx del proveedor, salvo 429, no abren el circuito. Parámetros configurables en `assistant.llm.*`.

## Consecuencias

- El médico recibe un 503 claro y rápido; no se encolan consultas ni crece la latencia.
- El bulkhead acota costo y concurrencia (OWASP LLM10). No hay cuota por clave de API.
- Métricas `assistant.llm.calls`, `assistant.llm.failures`, `assistant.llm.rejections`. Pendiente exponer el estado del circuito y las métricas por HTTP (ver `deferred-work.md`).
- El hilo bloqueante de embeddings puede seguir ocupado tras el timeout hasta que expire el cliente del SDK; el bulkhead y la cola acotada limitan el daño.

## Alternativas descartadas

- Un único circuito para chat y embeddings: un fallo en una operación bloquearía la otra sin necesidad.
- Reintentos automáticos o colas: ocultan la indisponibilidad y aumentan la latencia y el costo.

Detalle operativo y propiedades: [`docs/asistente-llm-resiliencia.md`](../asistente-llm-resiliencia.md).
