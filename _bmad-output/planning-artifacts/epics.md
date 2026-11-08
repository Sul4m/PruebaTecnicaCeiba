---
status: draft
stepsCompleted: [1]
inputDocuments:
  - _bmad-output/planning-artifacts/prds/prd-PruebaTecnicaCeiba-2026-11-07/prd.md
  - _bmad-output/planning-artifacts/architecture/solution-design-PruebaTecnicaCeiba.md
  - _bmad-output/planning-artifacts/briefs/brief-PruebaTecnicaCeiba-2026-11-07/brief.md
  - _bmad-output/planning-artifacts/prfaq-PruebaTecnicaCeiba.md
  - docs/requirements/technical-test-baseline.md
  - docs/adr/0001-arquitectura-hexagonal.md
  - docs/adr/0002-alertas-medicas-sse.md
  - docs/adr/0003-spring-ai-y-modelo-openai.md
  - docs/adr/0004-aks-terraform-y-despliegue.md
  - docs/adr/0005-outbox-y-contrato-kafka.md
---

# PruebaTecnicaCeiba - Epic Breakdown

## Overview

Este documento organiza los requisitos del enunciado para desglosarlos en épicas e historias implementables. El PDF original de ocho páginas es la fuente de verdad y fue cotejado página por página. El [baseline técnico versionado](../../docs/requirements/technical-test-baseline.md) conserva sus umbrales, validaciones, casos E1-E6, corpus y entregables; cualquier diferencia se resuelve a favor del PDF. El PRD y el diseño aportan criterios y decisiones derivadas, no reemplazan el enunciado.

### Trazabilidad de lectura del PDF

- Página 1: contexto, alcance backend sin frontend y comienzo de RF-01.
- Páginas 2-3: RF-01 a RF-06, incluidas las partes A/B de RF-04 y la ambigüedad de RF-06.
- Páginas 3-5: RN-01 a RN-05, rangos de validación y prioridad por gravedad.
- Páginas 5-6: casos E1-E6, lista administrativa, corpus y consultas RAG obligatorias.
- Páginas 6-8: decisiones técnicas libres/obligatorias, entregables, criterios de evaluación y notas de commits/documentación.

## Requirements Inventory

### Functional Requirements

FR1: Registrar un paciente mediante `POST /triage/pacientes`; validar nombre, edad, queja principal y signos vitales dentro de los rangos del PDF; clasificar aplicando RN-01 por orden de gravedad (nivel 5 administrativo solo después de descartar niveles 1-3); responder con `id`, nivel, `slaMinutos`, estado `REGISTRADO` y `fechaRegistro` ISO 8601; publicar `paciente_registrado` en `triaje.eventos`. Los rangos y resultados de E1-E6 deben coincidir exactamente con el PDF.

FR2: Gestionar el estado con `PATCH /triage/pacientes/{id}/estado` y consultar el paciente con `GET /triage/pacientes/{id}`. Permitir solo las transiciones del PDF; ante transición inválida devolver HTTP 409 descriptivo. Toda auditoría incluye timestamp, id de paciente, estado anterior, estado nuevo y tipo de evento; no existe endpoint de eliminación. Las transiciones válidas publican su evento Kafka correspondiente.

FR3: Un proceso periódico evalúa pacientes activos `REGISTRADO`; al superar el SLA configurable sin entrar a consulta, marca el flag persistente `ESCALADO`, publica `paciente_escalado` y registra auditoría. Intervalo del scheduler y SLA deben poder configurarse para pruebas. Nunca escalar pacientes `EN_CONSULTA` o `ALTA`; los reintentos no duplican auditoría ni evento.

FR4: Generar `GET /triage/reportes/operacion?desde=...&hasta=...` para un rango ISO 8601, agrupado por hora, con total registrado, conteos por nivel, promedio y p95 del tiempo en minutos desde registro hasta la primera entrada a consulta. Usar SQL avanzado o equivalente; no iterar en memoria sobre todos los registros. La semántica adicional UTC, rango `[desde,hasta)` y pacientes sin consulta sigue el diseño global.

FR5: Alertar al equipo médico cuando un paciente es escalado. El canal está abierto en el PDF; la solución elegida implementa SSE autenticado, alerta persistida consultable, replay con cursor monotónico `deliveryCursor` y deduplicación de cliente por `alertId`, documentado en ADR.

FR6: El servicio de Asistente mantiene y sirve `GET /asistente/estado-urgencias` con la cantidad de pacientes activos (todos los que no estén en `ALTA`) por nivel. Reconstruye la vista consumiendo `triaje.eventos`, sin llamar a la API de Triage, y la persiste en Redis, opción elegida entre las permitidas en el PDF. Snapshots versionados e idempotentes son decisiones del diseño.

FR7: Exponer `POST /asistente/consulta` con `{ "pregunta": "..." }` y respuesta `{ "respuesta": "...", "fuentes": [{ "protocolo": "PROTO-XXX", "seccion": "..." }] }`. Usar RAG sobre el corpus del PDF, citar fuentes válidas y cumplir RN-05. Sin protocolo relevante responder explícitamente que no existe protocolo disponible, sin contenido clínico generado. La elección adicional de HTTP 503 claro aplica cuando el LLM no está disponible.

### NonFunctional Requirements

NFR1: Entregar al menos dos microservicios independientes en Java reactivo: Triage y Asistente Clínico. Stack obligatorio: Spring Boot 3, Spring WebFlux y Project Reactor (`Mono`/`Flux`); llamadas bloqueantes en event loop son penalizadas. El Java 17 y Spring Boot 3.5.16 corresponden al esqueleto y decisión del proyecto.

NFR2: Mantener persistencia reactiva: PostgreSQL/R2DBC para Triage y Redis reactivo para la proyección del Asistente. Aislar APIs síncronas de embedding y búsqueda vectorial en scheduler dedicado con concurrencia/cola acotadas.

NFR3: Integrar por Kafka mediante el topic `triaje.eventos` y los eventos `paciente_registrado`, `paciente_escalado`, `paciente_en_consulta` y `paciente_alta`. El PDF deja libre el formato y exige documentarlo; la elección del proyecto es envelope JSON versionado, clave de paciente y outbox transaccional, con entrega al menos una vez, orden por agregado, reintentos/DLQ y consumidores idempotentes.

NFR4: Proteger las APIs con API key independiente por servicio, validar entradas y mantener credenciales fuera del código, Git, logs y estado Terraform. La API key LLM se configura por variable de entorno localmente; en Azure usar Key Vault y Workload ID.

NFR5: Aplicar Resilience4j Circuit Breaker y timeouts al proveedor LLM y embeddings; un fallo del proveedor produce HTTP 503. Triage no depende del Asistente y una indisponibilidad de Kafka no impide confirmar la persistencia local de Triage.

NFR6: Incluir health/readiness probes, logs estructurados con correlación y métricas Micrometer como mínimo para conteos de clasificación por nivel, escalamientos y latencia del LLM. Trazado distribuido con OpenTelemetry es bonus; métricas adicionales de outbox/consumer lag y conexiones SSE son decisiones del diseño.

NFR7: Entregar pruebas automatizadas unitarias y de integración; el LLM debe poder mockearse. Cubrir como mínimo E1-E6, transiciones válidas e inválidas, idempotencia de escalamiento, citas RAG y sin coincidencia/sin alucinación. Testcontainers o equivalente para PostgreSQL/Kafka es recomendado, no obligatorio. La cobertura ampliada de outbox, Redis y SSE viene del diseño.

NFR8: Proveer ejecución local de un comando con Docker Compose que levante ambos servicios, PostgreSQL, Kafka y Redis; entregar README con arquitectura, decisiones y guía de ejecución.

NFR9: Entregar repositorio GitHub con código completo, público o con acceso para el evaluador; README con un comando local, arquitectura/diagrama, tecnologías y decisiones; tests automatizados; URL Azure con APIs accesibles y manifests Kubernetes/IaC usados. El PDF exige Kubernetes con `Deployment`, `Service` y probes; AKS o Azure Container Apps son válidos. La elección de AKS + Terraform, URL HTTPS, grupo dedicado y limpieza local son decisiones del proyecto.

### Additional Requirements

- Del PDF: documentar el formato elegido de Kafka y registrar mínimo dos ADR; README debe incluir instrucciones de ejecución local en un comando, arquitectura con diagrama, tecnologías y decisiones justificadas.
- Del PDF: commit progresivamente; documentar ambigüedades y supuestos, incluida la decisión RF-06.
- Del PDF: manejo serio de errores/excepciones, integración reactiva, Kafka y backpressure, resiliencia, seguridad, calidad de pruebas, despliegue e infraestructura forman parte de la evaluación.
- Del PDF: OpenTelemetry es un bonus de observabilidad; el mínimo obligatorio es Micrometer con las métricas indicadas en NFR6.
- Del PDF: el plazo de referencia es tres días; realizar commits progresivos con versiones funcionales para demostrar avance.
- Del diseño elegido: mantener arquitectura hexagonal liviana en cada microservicio, propiedad de datos separada y sin compartir dominio ni consultar la API/base de Triage desde el Asistente.
- Usar rutas del PDF: `POST /triage/pacientes`, `PATCH /triage/pacientes/{id}/estado`, `GET /triage/pacientes/{id}`, `GET /triage/reportes/operacion`, `GET /triage/alertas/stream`, `GET /triage/alertas`, `POST /asistente/consulta` y `GET /asistente/estado-urgencias`; conservar los contratos de respuesta del baseline.
- En Triage, guardar agregado, auditoría append-only y outbox en una transacción R2DBC. Publicar a Kafka después del commit; reintentar sin bloquear el flujo de registro.
- Versionar eventos por paciente y enviar snapshot mínimo completo (`patientId`, nivel, estado, flag escalado); no incluir nombre, signos vitales ni edad. Mantener orden por paciente, aplicar cambios de proyección Redis atómicamente y alertar ante saltos/DLQ.
- Persistir alertas junto al escalamiento y auditoría; usar `deliveryCursor` monotónico para consulta/SSE y datos mínimos en el stream.
- Implementar el cálculo horario de RF-05 en SQL usando la primera entrada `EN_CONSULTA` por paciente y `percentile_cont(0.95)`; no agregar colecciones de pacientes en memoria.
- Usar Spring AI 1.0.x con Azure OpenAI/Microsoft Foundry, GPT-5.4-mini, embeddings `text-embedding-3-small` y `SimpleVectorStore` en memoria para el corpus fijo. Calibrar el umbral con las tres consultas del baseline; construir las fuentes desde metadata recuperada y no desde texto libre del modelo.
- Mantener generación con `ChatClient.stream()`/Reactor y aislar embedding/búsqueda sincrónicos fuera del event loop; no habilitar herramientas ni acciones del modelo.
- En infraestructura, parametrizar `subscription_id` y `location` en Terraform, usar grupo `rg-pruebatecnicaceiba-<sufijo>` con tag `project=PruebaTecnicaCeiba`, y provisionar AKS, ACR, Key Vault/identidad, PostgreSQL, Event Hubs con compatibilidad Kafka validada y Azure Managed Redis.
- Mantener la arquitectura adicional limitada a los dos microservicios requeridos; no agregar frontend ni convertir la prueba en producto de producción.
- La canalización valida, ejecuta pruebas, construye y publica imágenes con tags inmutables y aplica manifests. Terraform provisiona infraestructura; no debe ejecutarse por cada release de aplicación si no hay cambios de infraestructura.
- No construir frontend ni presentar la demostración como producto clínico listo para producción. No usar datos clínicos reales. El alcance usa el corpus mínimo del PDF; aunque el enunciado permite extenderlo, esta prueba no necesita protocolos adicionales y nunca debe incorporar recomendaciones clínicas inventadas.

### UX Design Requirements

No aplica: el PDF define una solución backend de APIs/eventos y declara explícitamente que no requiere frontend.

### FR Coverage Map

FR1: Epic 1 - registrar, validar y clasificar al paciente según el PDF.
FR2: Epic 1 - consultar paciente y gestionar estados con auditoría.
FR3: Epic 1 - vigilar SLA y escalar idempotentemente.
FR4: Epic 2 - consultar métricas horarias para operación.
FR5: Epic 3 - recibir y recuperar alertas de escalamiento.
FR6: Epic 3 - consultar la carga activa por nivel desde la proyección Kafka/Redis.
FR7: Epic 4 - consultar protocolos con citas y abstención segura.

## Epic List (propuesta para aprobación)

### Epic 1: El personal de admisión registra y gestiona pacientes con prioridades y tiempos claros

Enfermería puede registrar pacientes, recibir su clasificación/SLA, consultar su estado, actualizarlo por transiciones válidas y confiar en que los vencimientos se escalen una sola vez con auditoría íntegra.
**FRs covered:** FR1, FR2, FR3.
**Consideraciones:** primera por ser fuente de pacientes, estados y eventos que necesitan los demás flujos. Incluye verticalmente validación, dominio, R2DBC, API, auditoría, scheduler y publicación durable; no es una épica de base de datos o API genérica.

### Epic 2: Operaciones identifica carga y tiempos de espera por hora

Un usuario autorizado puede consultar el reporte de operación para detectar volúmenes y tiempos hasta la primera consulta, con agregados calculados directamente en PostgreSQL.
**FRs covered:** FR4.
**Consideraciones:** se apoya en el registro de Epic 1; conserva semántica UTC, rango y p95 ya definida. Se puede implementar como flujo vertical independiente dentro de Triage.

### Epic 3: El equipo médico conoce la carga activa y recibe escalaciones recuperables

El equipo médico puede consultar pacientes activos por nivel y recibir alertas de escalamiento en vivo; si se desconecta, recupera alertas persistidas sin duplicados.
**FRs covered:** FR5, FR6.
**Consideraciones:** el conteo se reconstruye desde eventos versionados de Epic 1 y nunca llama a su API. La alerta SSE es emitida por Triage; ambas capacidades se agrupan por el resultado de conciencia operacional del equipo médico.

### Epic 4: El equipo médico consulta protocolos con fuentes y abstención explícita

Un usuario médico pregunta en lenguaje natural y recibe una respuesta sustentada con protocolo/sección o el aviso exacto de que no existe protocolo disponible, sin contenido clínico generado.
**FRs covered:** FR7.
**Consideraciones:** integra el corpus mínimo, Spring AI y el proveedor elegido; incluye fallo del proveedor HTTP 503 y pruebas con mock.

### Requisitos transversales

NFR1-NFR9 y requisitos adicionales (seguridad, pruebas, observabilidad, Docker Compose, documentación, Terraform, manifests y URL Azure) se incorporan como criterios de aceptación en las historias de cada epic, no como épicas técnicas aisladas. El despliegue progresivo acompaña cada corte vertical; la URL final debe exponer los dos servicios integrados.

<!-- Repeat for each epic in epic_list (N = 1, 2, 3...) -->

## Epic {{N}}: {{epic_title_N}}

{{epic_goal_N}}

<!-- Repeat for each story (M = 1, 2, 3...) within epic N -->

### Story {{N}}.{{M}}: {{story_title_N_M}}

As a {{user_type}},
I want {{capability}},
So that {{value_benefit}}.

**Acceptance Criteria:**

<!-- for each AC on this story -->

**Given** {{precondition}}
**When** {{action}}
**Then** {{expected_outcome}}
**And** {{additional_criteria}}

<!-- End story repeat -->
