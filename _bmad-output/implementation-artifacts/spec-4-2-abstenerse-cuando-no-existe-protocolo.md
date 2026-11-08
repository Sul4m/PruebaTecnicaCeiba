---
title: 'Story 4.2 - Abstenerse cuando no existe protocolo'
type: 'feature'
created: '2026-11-08'
status: 'done'
baseline: 'docs/requirements/technical-test-baseline.md (RF-04A, RN-05)'
context:
  - '_bmad-output/implementation-artifacts/spec-4-1-responder-consultas-citando-el-protocolo-del-corpus.md'
  - 'docs/adr/0003-spring-ai-y-modelo-openai.md'
---

<frozen-after-approval reason="human-owned intent - do not modify unless human renegotiates">

## Intent

**Problem:** Ante una pregunta no cubierta por el corpus, o una respuesta generada que cita un protocolo ajeno al contexto recuperado, el medico podria recibir contenido clinico inventado (RN-05).

**Approach:** 4.1 ya se abstiene sin invocar al modelo cuando la recuperacion (umbral configurable) no devuelve fragmentos. 4.2 agrega solo lo que falta: validacion posterior a la generacion que descarta toda respuesta que mencione un identificador `PROTO-n` no recuperado y devuelve el aviso fijo; y pruebas deterministas del umbral (frontera inclusiva, configurable por entorno) y de la calibracion con las tres consultas del baseline.

## Boundaries & Constraints

**Always:**
- Aviso fijo unico (`ConsultaResultado.AVISO_SIN_PROTOCOLO`), `fuentes: []`, sin contenido clinico; HTTP 200.
- Sin fragmentos sobre el umbral => el modelo NO se invoca.
- Validacion en la capa de aplicacion (`ConsultaProtocoloService`), independiente del proveedor: cualquier `PROTO-n` (sin distinguir mayusculas) en el texto generado que no este entre los protocolos recuperados invalida la respuesta. `fuentes` sigue saliendo solo de la metadata recuperada.
- Umbral y topK configurables (`assistant.rag.similarity-threshold`, `ASISTENTE_RAG_THRESHOLD` en `azure`); el limite es inclusivo (`score >= umbral`).
- Logs sin texto de pregunta ni respuesta.
- Pruebas sin Docker, red ni proveedor real.

**Ask First:** cambiar el contrato `{pregunta}` -> `{respuesta, fuentes}` o el texto del aviso fijo.

**Never:** tocar `triage-service`, `docker-compose.yml`, `docs/adr`; agregar contenido clinico; aceptar identificadores de protocolo provenientes del modelo; añadir Resilience4j (es 4.3).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Pregunta ajena (diabetes) | "¿Cual es el tratamiento de primera linea para la diabetes tipo 2?" | 200; solo aviso fijo; `fuentes: []`; modelo NO invocado | N/A |
| Modelo mockeado devuelve contenido para pregunta ajena | stub con texto clinico | Texto jamas devuelto; modelo no invocado | N/A |
| Respuesta cita protocolo inexistente | texto con `PROTO-999` | 200; aviso fijo; `fuentes: []` | N/A |
| Respuesta cita protocolo existente pero no recuperado | contexto PROTO-001, texto menciona PROTO-003 | aviso fijo; `fuentes: []` | N/A |
| Respuesta sin mencion de protocolo | contexto recuperado | respuesta + `fuentes` desde metadata | N/A |
| Frontera del umbral | umbral == score / `nextUp(score)` | incluido / excluido | N/A |
| Umbral maximo (1.0) por configuracion | pregunta cubierta | aviso fijo, modelo no invocado | N/A |
| Preguntas validas de 4.1 | crisis hipertensiva / sepsis | `fuentes[0]` = PROTO-001 / PROTO-002 | N/A |

</frozen-after-approval>

## Code Map

Paquete base `com.vidacruz.clinical_assistant_service` (`clinical-assistant-service/src/...`):

- `application/ConsultaProtocoloService` - aborda la validacion RN-05 post-generacion (`citaSoloRecuperados`).
- Sin cambios: `domain/ConsultaResultado` (aviso fijo), `infrastructure/ai/SpringAiProtocolRetriever` (umbral inclusivo via `SimpleVectorStore`), `application.yaml` (`similarity-threshold`).
- Tests: `ConsultaProtocoloServiceTest`, `SpringAiProtocolRetrieverTest`, `ConsultaControllerIT`, `ConsultaThresholdIT`.

## Calibracion del umbral

Doble de desarrollo (`DevEmbeddingModel`), mejor puntaje por consulta del baseline: crisis hipertensiva `0.4903`, sepsis `0.3849`, diabetes (maximo, sin cortar) `0.1980`. Umbral `0.30` separa ambas cubiertas (>= 0.38) de la ajena (< 0.20); verificado por `configuredThresholdSeparatesCoveredFromUncoveredBaselineQuestions`.

**Pendiente:** `application-azure.yaml` mantiene `0.45` provisional; debe recalibrarse con `text-embedding-3-small` y las mismas tres consultas (ADR-0003). No se puede hacer sin proveedor real.

## Tasks & Acceptance

**Execution:**
- [x] Identificar lo cubierto por 4.1 (aviso fijo, `fuentes` vacio, modelo no invocado, umbral configurable).
- [x] Validacion post-generacion de citas en `ConsultaProtocoloService`.
- [x] Pruebas unitarias (citas validas/invalidas) y de integracion con `ChatModel` mockeado.
- [x] Pruebas del umbral: frontera inclusiva, configuracion por propiedad, calibracion baseline (dev).
- [ ] Calibrar `ASISTENTE_RAG_THRESHOLD` con embeddings reales (Azure, al final).

**Acceptance Criteria:**
- Given la pregunta de diabetes, then aviso fijo, `fuentes` vacio, modelo no invocado.
- Given una respuesta que cita un protocolo no recuperado, then se descarta y se devuelve el aviso.
- Given el mock devolviendo contenido para una pregunta sin coincidencia, then la API responde solo el aviso.
- Given las dos preguntas validas, then siguen citando PROTO-001/002.

## Verification

**Commands:**
- `cd clinical-assistant-service && ./gradlew test` -- expected: 57 pruebas, 0 fallos, sin Docker ni red.
