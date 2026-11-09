---
title: 'Story 4.3 - Responder con claridad cuando el proveedor LLM falla'
type: 'feature'
created: '2026-11-08'
status: 'done'
context:
  - '_bmad-output/implementation-artifacts/spec-4-1-responder-consultas-citando-el-protocolo-del-corpus.md'
  - '_bmad-output/implementation-artifacts/spec-4-2-abstenerse-cuando-no-existe-protocolo.md'
---

## Intent

**Problem:** Un proveedor de chat/embeddings caido, lento o saturado no debe colgar ni degradar el servicio; el medico debe recibir un 503 claro. Cierra el diferido de 4.1 (limite de concurrencia/costo del chat, OWASP LLM10).

**Approach:** `LlmGuard` (infrastructure/ai) envuelve cada llamada al LLM con timer Micrometer, circuit breaker y bulkhead sin cola (Resilience4j, un par por operacion `chat`/`embeddings`) y traduce todo fallo a `AssistantUnavailableException` (503 via `ApiExceptionHandler`). Timeouts existentes (`assistant.rag.*-timeout`) quedan dentro del circuito. Configuracion en `assistant.llm.*` (el repo usa el prefijo `assistant`, no `asistente`). Doc: `docs/asistente-llm-resiliencia.md`.

## Boundaries

**Always:** 503 sin contenido clinico ni detalle del proveedor; rechazo rapido sin colas; abstencion (200) y validacion (400) sin cambios; logs sin texto de pregunta.
**Never:** tocar triage-service, `.env*`, docker-compose; `RejectedExecutionException` del scheduler local no cuenta como fallo del circuito.

## Tasks

- [x] Dependencias Resilience4j (reactor, circuitbreaker, bulkhead) en `build.gradle`
- [x] `AssistantLlmProperties` + defaults en `application.yaml`
- [x] `LlmGuard` (timer, contadores de fallos y rechazos, CB, bulkhead)
- [x] Integrar en `SpringAiAnswerGenerator` y `SpringAiProtocolRetriever` (busqueda por consulta; la indexacion de arranque conserva su timeout)
- [x] Tests: `LlmGuardTest`, `ConsultaLlmFailureIT` (error, timeout, apertura de circuito, abstencion != fallo, 400, estado-urgencias vivo), retriever con embeddings caidos
- [x] Documentacion de propiedades y metricas
- [x] Suite completa del asistente

## Notas

- Las metricas no se exponen por HTTP (actuator solo expone `health`); quedan en el `MeterRegistry`.
- Pendiente fuera de alcance: cuota por clave de API (solo bulkhead global).

## Review Triage Log

**Patches aplicados (cada uno con test que falla si se revierte):**
- P1: errores 4xx del proveedor (WebClientResponseException / HttpResponseException / NonTransientAiException), salvo 429, no cuentan como fallo del circuito; siguen siendo 503.
- P2: la indexacion pendiente pasa por `guard.protect(EMBEDDINGS)`; con circuito abierto no se contacta al proveedor (ya indexada es no-op y no cuenta en la ventana).
- P3: el armado del prompt y `stream()` van en `Mono.defer` dentro del guard.
- P4: timer con `outcome=cancelled` en cancelacion.
- P5: Mono vacio se etiqueta `outcome=empty` en el timer; en el generador la respuesta vacia se emite como error antes del guard, por lo que cuenta como fallo del circuito y sale 503. El guard por si solo no trata el vacio como fallo.
- P6: tests de saturacion del scheduler (`scheduler_full`, circuito CLOSED) y del bulkhead de embeddings.

**Diferidos (ver deferred-work.md):** hilo bloqueante vivo tras timeout; validacion de propiedades (minimumNumberOfCalls <= window, half-open configurable); metricas de estado del circuito (TaggedCircuitBreakerMetrics); test half-open/recuperacion; IT HTTP con embeddings caido.
