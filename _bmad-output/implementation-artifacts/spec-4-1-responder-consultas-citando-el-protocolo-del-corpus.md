---
title: 'Story 4.1 - Responder consultas citando el protocolo del corpus'
type: 'feature'
created: '2026-11-08'
status: 'done'
baseline: 'docs/requirements/technical-test-baseline.md (RF-04A, RN-05)'
context:
  - 'docs/adr/0001-arquitectura-hexagonal.md'
  - 'docs/adr/0003-spring-ai-y-modelo-openai.md'
  - '_bmad-output/planning-artifacts/architecture/solution-design-PruebaTecnicaCeiba.md'
---

<frozen-after-approval reason="human-owned intent - do not modify unless human renegotiates">

## Intent

**Problem:** El equipo medico no puede consultar los protocolos del corpus en lenguaje natural con una respuesta sustentada y trazable.

**Approach:** `POST /asistente/consulta` recupera fragmentos del corpus versionado (Spring AI `SimpleVectorStore`), redacta con `ChatClient.stream()` solo con ese contexto y devuelve `fuentes` construidas unicamente desde la metadata de los fragmentos recuperados. Sin coincidencia sobre el umbral configurable responde el aviso fijo sin invocar al modelo (la abstencion detallada y la resiliencia completa son 4.2/4.3).

## Boundaries & Constraints

**Always:**
- Corpus = PROTO-001/002/003 tal como figuran en el baseline, partido por protocolo y seccion, con metadata inmutable `protocolId`, `sectionId`, `corpusVersion` (+ titulos). Sin contenido clinico adicional.
- `fuentes` sale de la metadata recuperada; nunca se parsea el texto del modelo.
- Embedding y busqueda vectorial (sincronos en Spring AI 1.0) corren en un scheduler `boundedElastic` dedicado con hilos y cola acotados; saturacion, timeout o fallo => `AssistantUnavailableException` => HTTP 503. Nunca en el event loop.
- El corpus se indexa en un `ApplicationRunner` antes de readiness UP; si el proveedor falla el servicio arranca igual y la consulta responde 503 (y reintenta indexar).
- Chat por `ChatClient` en streaming compuesto con Reactor, limite de tokens por configuracion del modelo, limite de caracteres como defensa en profundidad, sin tools.
- Seguridad: `X-API-Key` propia (`ASISTENTE_API_KEY`, sin valor por defecto, falla al arrancar si falta), comparacion en tiempo constante, 401 con cuerpo vacio; solo `/actuator/health/**` publico.
- Credenciales de proveedor solo por variables de entorno (perfil `azure`). Nada de texto de pregunta/respuesta en logs.
- Errores como `ProblemDetail`; 400 incluye `campo`.
- Tests y arranque por defecto sin proveedor real, red ni Docker.

**Ask First:** cambiar de `SimpleVectorStore`, de version de Spring AI, o del contrato `{pregunta}` -> `{respuesta, fuentes}`.

**Never:** llamar al proveedor real en tests; hardcodear secretos; aceptar identificadores de protocolo provenientes del modelo; habilitar tools; tocar `triage-service`, `docker-compose.yml` ni `docs/adr`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Crisis hipertensiva | "¿Cuales son las medidas iniciales ante una crisis hipertensiva?" | 200; `fuentes[0]` = `{PROTO-001, "Medidas iniciales"}` | N/A |
| Sepsis | "¿Cuando se administra el primer antibiotico en sepsis?" | 200; `fuentes[0]` = `{PROTO-002, "Acciones iniciales"}` | N/A |
| Sin coincidencia | "¿Cual es el tratamiento de primera linea para la diabetes tipo 2?" | 200; aviso fijo "No existe un protocolo disponible..."; `fuentes: []`; modelo NO invocado | N/A |
| Modelo cita PROTO-999 | Texto del modelo menciona otro protocolo | `fuentes` solo con chunks recuperados | N/A |
| Pregunta vacia/blanca/ausente | `{"pregunta":" "}` / `{}` | 400 ProblemDetail con `campo: "pregunta"` | No se repite el texto |
| Pregunta > 500 caracteres | 501 caracteres | 400 con `campo: "pregunta"` | No se repite el texto |
| JSON malformado | `{no-json` | 400 | N/A |
| Sin/errada API key | sin cabecera / clave incorrecta | 401 cuerpo vacio | N/A |
| Proveedor caido / vacio / timeout | chat o embeddings fallan | 503 ProblemDetail generico | Log sin texto de la pregunta |
| Scheduler saturado | hilos y cola llenos | 503 inmediato | Rechazo, sin cola sin limite |

</frozen-after-approval>

## Code Map

Paquete base `com.vidacruz.clinical_assistant_service` (`clinical-assistant-service/src/main/java/...`):

- `domain/` - `ProtocolChunk`, `RetrievedChunk`, `FuenteProtocolo`, `ConsultaResultado` (+ aviso fijo), `AssistantUnavailableException`.
- `application/ConsultaProtocoloService` - caso de uso; `application/port/out/{ProtocolRetrievalPort,AnswerGenerationPort}`.
- `infrastructure/ai/` - `ProtocolCorpusLoader` (corpus -> chunks), `SpringAiProtocolRetriever` (SimpleVectorStore + scheduler), `SpringAiAnswerGenerator` (ChatClient stream), `CorpusIndexRunner`; `ai/dev/` - `DevEmbeddingModel`, `DevChatModel`, `DevAiConfig` (dobles etiquetados, solo perfil `dev`).
- `infrastructure/config/` - `RagConfig`, `AssistantRagProperties`, `AssistantSecurityProperties`.
- `infrastructure/security/` - `ApiKeyAuthenticationFilter`, `SecurityConfig`.
- `infrastructure/web/` - `ConsultaController`, `ConsultaRequest/Response`, `ApiExceptionHandler`.
- `src/main/resources/corpus/v1/PROTO-00{1,2,3}.md`; `application.yaml`, `application-dev.yaml` (default), `application-azure.yaml`.
- `build.gradle` - BOM `org.springframework.ai:spring-ai-bom:1.0.9`, `spring-ai-starter-model-azure-openai`, `spring-ai-vector-store`.

Espacio reservado: 4.2 (abstencion/validacion de citas reforzada, Resilience4j sobre chat/embeddings, 503 por circuito) y 4.3 (metricas Micrometer de latencia LLM, observabilidad). Los puertos ya son los puntos de decoracion.

## Tasks & Acceptance

**Execution:**
- [x] Corpus versionado dividido por protocolo/seccion con metadata.
- [x] BOM y dependencias Spring AI 1.0.9 verificadas en Maven Central.
- [x] Retriever con `SimpleVectorStore`, scheduler dedicado acotado, umbral y topK configurables.
- [x] Generador con `ChatClient.stream()`, sin tools, timeout y tope de caracteres.
- [x] Endpoint, validacion, ProblemDetail con `campo`, 503.
- [x] Filtro `X-API-Key` propio, 401 vacio, health publico.
- [x] Dobles `dev` etiquetados y perfil `azure` con variables de entorno.
- [x] Pruebas unitarias e integracion con modelo simulado.

**Acceptance Criteria:**
- Given el corpus v1, when arranca, then los 7 fragmentos quedan indexados con `protocolId/sectionId/corpusVersion` antes de readiness UP.
- Given las dos preguntas del baseline, when se consulta con API key valida, then la primera fuente es PROTO-001 y PROTO-002 respectivamente.
- Given una pregunta sin protocolo, when se consulta, then aviso fijo, `fuentes` vacio y el modelo no se invoca.
- Given pregunta vacia o > 500 caracteres, then 400 con `campo`.
- Given falta o error de API key, then 401 sin cuerpo.

## Verification

**Commands:**
- `cd clinical-assistant-service && ./gradlew test` -- expected: 46 pruebas, 0 fallos, sin Docker ni red.

**Manual:** `SPRING_PROFILES_ACTIVE=azure AZURE_OPENAI_API_KEY=... AZURE_OPENAI_ENDPOINT=... ASISTENTE_API_KEY=... ./gradlew bootRun`; con endpoint inalcanzable el servicio queda `readiness UP` y la consulta responde 503.

**Pendiente de calibracion:** el umbral `0.30` esta calibrado para el doble de desarrollo; `application-azure.yaml` usa `0.45` provisional (variable `ASISTENTE_RAG_THRESHOLD`) y debe calibrarse con `text-embedding-3-small` y las tres consultas del baseline (ADR-0003).
