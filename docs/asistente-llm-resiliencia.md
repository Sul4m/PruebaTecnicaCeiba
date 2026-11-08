# Resiliencia del proveedor LLM (Asistente Clinico)

`POST /asistente/consulta` depende de embeddings y chat. Si fallan, responde **HTTP 503** (ProblemDetail, sin contenido clinico ni detalle del proveedor). La abstencion (aviso fijo, HTTP 200) y los errores de validacion (400) no cuentan como fallo del proveedor. `GET /asistente/estado-urgencias` no usa el LLM y sigue respondiendo.

Cada operacion (`chat`, `embeddings`) tiene su propio circuit breaker y bulkhead (Resilience4j). Al abrirse el circuito o saturarse el bulkhead el rechazo es inmediato (`max-wait: 0`): no hay colas.

| Propiedad | Defecto | Env |
|---|---|---|
| `assistant.rag.llm-timeout` / `embedding-timeout` | 30s / 10s | - |
| `assistant.llm.circuit-breaker.failure-rate-threshold` | 50 | `ASISTENTE_LLM_CB_FAILURE_RATE` |
| `assistant.llm.circuit-breaker.sliding-window-size` | 10 | `ASISTENTE_LLM_CB_WINDOW` |
| `assistant.llm.circuit-breaker.minimum-number-of-calls` | 5 | `ASISTENTE_LLM_CB_MIN_CALLS` |
| `assistant.llm.circuit-breaker.wait-duration-in-open-state` | 30s | `ASISTENTE_LLM_CB_WAIT_OPEN` |
| `assistant.llm.circuit-breaker.permitted-calls-in-half-open-state` | 3 | - |
| `assistant.llm.bulkhead.chat-max-concurrent-calls` | 8 | `ASISTENTE_LLM_BULKHEAD_CHAT` |
| `assistant.llm.bulkhead.embedding-max-concurrent-calls` | 8 | `ASISTENTE_LLM_BULKHEAD_EMBEDDINGS` |
| `assistant.llm.bulkhead.max-wait` | 0ms | - |

Metricas Micrometer (tag `operation` = `chat`|`embeddings`):
- `assistant.llm.calls` (timer, tag `outcome` = success|error): latencia de llamadas al LLM.
- `assistant.llm.failures` (counter, tag `reason` = timeout|error).
- `assistant.llm.rejections` (counter, tag `reason` = circuit_open|bulkhead_full|scheduler_full).

## Contrato del token `SIN_PROTOCOLO` (RN-05)

El prompt del sistema ordena al modelo responder solo con los fragmentos y solo si responden directamente a la pregunta (mismo tema clínico y misma población; un protocolo pediátrico no responde sobre adultos). En cualquier otro caso debe emitir exactamente `SIN_PROTOCOLO`.

`ConsultaProtocoloService` trata esa salida como abstención (comparación sin distinguir mayúsculas y tolerante a espacios y puntuación en los extremos), igual que una salida vacía o una cita a un protocolo no recuperado: devuelve `ConsultaResultado.AVISO_SIN_PROTOCOLO` con `fuentes: []`. El umbral de similitud sigue siendo el primer filtro; el token cubre los casos en que el recuperador devuelve fragmentos del tema equivocado. El contrato JSON no cambia.

**Validación de sustento (RN-05, determinista).** Además de lo anterior, `RespuestaSustentada` comprueba sin depender del modelo que la respuesta esté sustentada léxicamente por los fragmentos recuperados: normaliza (minúsculas, sin tildes ni markdown), descarta stopwords, palabras de menos de 4 letras y citas `PROTO-n`, y exige que al menos el 60% de los tokens de contenido aparezca en los fragmentos y que todo número de la respuesta (dosis, porcentajes) exista en ellos; si no, abstiene con el aviso fijo. Las `fuentes` se reducen a las secciones que comparten tokens con la respuesta (al menos 2 y la mitad de lo que comparte la mejor; siempre se conserva la mejor). Limitación: la comprobación es léxica, no semántica; una respuesta que recombine palabras reales con otro sentido, o que niegue un hecho usando el mismo vocabulario, podría pasar.
