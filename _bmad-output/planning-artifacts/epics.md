---
status: final
stepsCompleted: [1, 2, 3, 4]
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

## Epic List

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

NFR1-NFR9 y requisitos adicionales (seguridad, pruebas, observabilidad, Docker Compose, documentación, Terraform, manifests y URL Azure) se incorporan como criterios de aceptación en las historias de cada epic, no como épicas técnicas aisladas. La URL final debe exponer los dos servicios integrados.

### Orden de ejecución

Por decisión del usuario (2026-11-07), todo se construye y valida primero en local (Docker Compose) y con tests (Testcontainers). Las historias de despliegue en Azure (1.6 y 3.4) y la parte Azure de 4.4 se implementan al final, en el orden 1.6 → 3.4 → 4.4, cuando el resto esté terminado y verificado. Ninguna historia local depende de Azure.

## Epic 1: El personal de admisión registra y gestiona pacientes con prioridades y tiempos claros

Enfermería puede registrar pacientes, recibir su clasificación/SLA, consultar su estado, actualizarlo por transiciones válidas y confiar en que los vencimientos se escalen una sola vez con auditoría íntegra. Cubre FR1, FR2 y FR3.

### Story 1.1: Registrar y clasificar un paciente

Como personal de admisión,
quiero registrar un paciente con sus signos vitales y recibir su nivel de triaje y SLA,
para que la prioridad de atención sea consistente y no dependa del criterio manual.

**Acceptance Criteria:**

**Dado** un request válido con `X-API-Key` correcta
**Cuando** se invoca `POST /triage/pacientes` con nombre, edad, queja principal, FC, sistólica, diastólica, SpO2, temperatura, dolor y GCS
**Entonces** responde HTTP 201 con `id` (UUID), `nivel`, `slaMinutos`, estado `REGISTRADO` y `fechaRegistro` ISO 8601 en UTC
**Y** el paciente y su auditoría inicial (timestamp, id de paciente, estado anterior nulo, estado nuevo `REGISTRADO`, tipo de evento) se guardan en PostgreSQL vía R2DBC en una sola transacción, con migraciones versionadas que crean solo `patient` y `audit_event`.

**Dado** los casos E1-E6 del baseline con nombre y edad válidos
**Cuando** se registran
**Entonces** el nivel asignado es exactamente `CRITICO`, `URGENTE`, `URGENTE_2`, `NO_URGENTE`, `NO_URGENTE_2` y `CRITICO` respectivamente
**Y** `slaMinutos` corresponde a RN-02 (5, 10, 30, 60, 120).

**Dado** signos que cumplen condiciones de varios niveles
**Cuando** se clasifica
**Entonces** prevalece el nivel más grave, respetando los comparadores inclusivos/exclusivos de RN-01
**Y** una queja de la lista administrativa (`documentos`, `recolección de resultados`, `renovación de recetario`, `vacunación`, `seguimiento`) solo produce nivel 5 si antes se descartaron los niveles 1-3; con tests unitarios de cada frontera de RN-01.

**Dado** un campo fuera de rango (FC 20-250, sistólica 60-260, diastólica 30-160 y menor que la sistólica, SpO2 70-100, temperatura 34.0-42.0, dolor 0-10, GCS 3-15, edad 0-120, nombre 3-100 caracteres) o ausente
**Cuando** se invoca el registro
**Entonces** responde HTTP 400 con un mensaje que identifica el campo inválido
**Y** no se persiste nada.

**Dado** un request sin `X-API-Key` o con una clave incorrecta
**Cuando** se invoca cualquier endpoint de negocio de Triage
**Entonces** responde HTTP 401 sin revelar datos
**Y** la clave se lee de configuración/variable de entorno, nunca del código.

**Dado** el servicio en ejecución
**Cuando** se consultan health/readiness y métricas
**Entonces** las probes responden, existe un contador Micrometer de clasificaciones por nivel y los logs son estructurados con correlación sin nombre, signos vitales ni API key
**Y** `docker compose up` levanta Triage con PostgreSQL en un solo comando, y existe al menos una prueba de integración del flujo HTTP contra PostgreSQL (Testcontainers o equivalente).

### Story 1.2: Consultar un paciente

Como personal médico o de admisión,
quiero consultar los datos y el estado actual de un paciente,
para saber cuánto lleva esperando y si fue escalado.

**Acceptance Criteria:**

**Dado** un paciente registrado
**Cuando** se invoca `GET /triage/pacientes/{id}` con API key válida
**Entonces** responde HTTP 200 con los datos completos del registro, nivel, `slaMinutos`, estado actual, flags (incluido `escalado`, inicialmente `false`) y tiempo en el estado actual calculado desde el último cambio de estado.

**Dado** un UUID inexistente
**Cuando** se consulta
**Entonces** responde HTTP 404 con mensaje descriptivo.

**Dado** un `id` con formato inválido
**Cuando** se consulta
**Entonces** responde HTTP 400 identificando el parámetro.

**Dado** el cálculo del tiempo en estado
**Cuando** se prueba
**Entonces** usa un reloj inyectable (`Clock`) para que los tests sean deterministas.

### Story 1.3: Cambiar el estado con transiciones válidas

Como personal médico,
quiero mover a un paciente a consulta o darle alta,
para que el estado y los tiempos reflejen la atención real con trazabilidad.

**Acceptance Criteria:**

**Dado** un paciente `REGISTRADO`
**Cuando** se invoca `PATCH /triage/pacientes/{id}/estado` con `{ "estado": "EN_CONSULTA" }` o `{ "estado": "ALTA" }`
**Entonces** responde HTTP 200 con el estado nuevo
**Y** en la misma transacción se actualiza el paciente y se agrega una auditoría con estado anterior, estado nuevo, timestamp y tipo de evento (`paciente_en_consulta` o `paciente_alta`).

**Dado** un paciente `EN_CONSULTA`
**Cuando** se solicita `ALTA`
**Entonces** la transición se acepta y se audita.

**Dado** cualquier transición no permitida (`EN_CONSULTA`→`REGISTRADO`, `ALTA`→cualquiera, mismo estado actual)
**Cuando** se solicita
**Entonces** responde HTTP 409 con mensaje que indica estado actual y estado pedido
**Y** no cambia el paciente ni se agrega auditoría.

**Dado** un estado desconocido en el cuerpo o un paciente inexistente
**Cuando** se invoca el PATCH
**Entonces** responde HTTP 400 o 404 respectivamente.

**Dado** dos actualizaciones concurrentes sobre el mismo paciente
**Cuando** se procesan
**Entonces** solo una aplica la transición (actualización condicional por estado esperado) y la otra recibe 409
**Y** no existe ningún endpoint para borrar o modificar auditoría; hay tests de todas las transiciones válidas e inválidas.

### Story 1.4: Publicar eventos de triaje de forma durable

Como equipo médico y servicios consumidores,
quiero que cada registro y cambio de estado se publique en `triaje.eventos`,
para que otros servicios reaccionen sin depender de la API de Triage.

**Acceptance Criteria:**

**Dado** un registro o una transición válida
**Cuando** se confirma la transacción
**Entonces** en esa misma transacción R2DBC se insertó una fila en `outbox_event` (nueva migración) con el evento `paciente_registrado`, `paciente_en_consulta` o `paciente_alta`
**Y** el paciente incrementa su `aggregateVersion` en cada cambio publicable.

**Dado** filas pendientes en el outbox
**Cuando** corre el relay
**Entonces** publica en `triaje.eventos` con clave `patientId` y envelope JSON v1 (`eventId`, `eventType`, `schemaVersion`, `occurredAt`, `aggregateVersion` y snapshot `patientId`, `nivel`, `estado`, `escalated`), sin nombre, edad ni signos vitales
**Y** marca la fila como publicada solo tras confirmación del broker (`acks=all`, idempotencia habilitada), preservando el orden de versiones por paciente con un único relay y lotes acotados.

**Dado** Kafka no disponible
**Cuando** se registra un paciente
**Entonces** el registro responde normalmente y el evento queda pendiente
**Y** el relay reintenta con backoff y publica al recuperarse el broker, sin bloquear el event loop.

**Dado** un evento que falla repetidamente
**Cuando** se agotan los reintentos configurados
**Entonces** se envía a un dead-letter topic y se registra error/métrica operativa, sin descartarlo silenciosamente.

**Dado** el entorno local
**Cuando** se ejecuta `docker compose up`
**Entonces** Kafka se levanta junto a Triage y PostgreSQL
**Y** el formato del evento queda documentado (ADR-0005/README), con pruebas de integración de outbox→Kafka y de publicación tras caída del broker.

### Story 1.5: Escalar por SLA una sola vez

Como equipo médico,
quiero que un paciente que supera su SLA sin entrar a consulta quede escalado,
para no perder de vista a quien lleva demasiado tiempo esperando.

**Acceptance Criteria:**

**Dado** un paciente `REGISTRADO` cuyo tiempo desde registro supera su SLA
**Cuando** corre el proceso periódico
**Entonces** marca persistentemente `escalado = true`, agrega auditoría del cambio de flag y encola `paciente_escalado` en el outbox en la misma transacción
**Y** `GET /triage/pacientes/{id}` muestra el flag escalado.

**Dado** un paciente ya escalado
**Cuando** el proceso vuelve a correr o se ejecutan dos instancias en paralelo
**Entonces** no se publica otro evento ni se duplica la auditoría (actualización condicional `UPDATE ... RETURNING` o equivalente).

**Dado** un paciente `EN_CONSULTA` o `ALTA`
**Cuando** corre el proceso aunque su tiempo supere el SLA
**Entonces** nunca se escala.

**Dado** un paciente cuyo tiempo aún no supera su SLA
**Cuando** corre el proceso
**Entonces** no se escala; las fronteras exactas por nivel se prueban con reloj inyectable.

**Dado** la configuración
**Cuando** se ajustan el intervalo del scheduler y los SLA por nivel por propiedades
**Entonces** las pruebas pueden usar valores cortos sin cambiar código
**Y** el proceso trabaja por lotes con `LIMIT`, concurrencia acotada y sin materializar todos los pacientes, e incrementa una métrica Micrometer de escalamientos.

### Story 1.6: Exponer Triage en Azure

Como evaluador,
quiero acceder a Triage desplegado en Azure por HTTPS,
para verificar que el servicio funciona fuera del entorno local.

**Acceptance Criteria:**

**Dado** `subscription_id` y `location` como variables
**Cuando** se aplica Terraform
**Entonces** se crea el grupo `rg-pruebatecnicaceiba-<sufijo>` con tag `project=PruebaTecnicaCeiba` que contiene AKS, ACR, Key Vault con identidades de workload, Azure Database for PostgreSQL Flexible Server y Event Hubs con endpoint Kafka (compatibilidad validada)
**Y** ningún valor secreto queda en Git ni en el estado Terraform.

**Dado** los manifests de Triage
**Cuando** se aplican en AKS
**Entonces** existen `Deployment`, `Service`, `ServiceAccount`, ConfigMap no secreto, probes startup/readiness/liveness y requests/limits explícitos
**Y** la API key y las credenciales se obtienen de Key Vault mediante Workload ID/CSI.

**Dado** un push a la rama protegida
**Cuando** corre el pipeline
**Entonces** valida, ejecuta tests, construye y publica la imagen en ACR con tag inmutable y aplica los manifests, sin ejecutar Terraform si la infraestructura no cambió, usando OIDC hacia Azure.

**Dado** el despliegue completo
**Cuando** se llama a `POST /triage/pacientes` por la URL pública HTTPS con API key
**Entonces** el paciente se registra y su evento llega a Event Hubs
**Y** existe un script local, ignorado por Git, que valida suscripción, nombre y tag del grupo y pide confirmación exacta antes de eliminarlo.

## Epic 2: Operaciones identifica carga y tiempos de espera por hora

Un usuario autorizado puede consultar el reporte de operación para detectar volúmenes y tiempos hasta la primera consulta, con agregados calculados directamente en PostgreSQL. Cubre FR4.

### Story 2.1: Consultar el reporte de operación por hora

Como responsable de operación,
quiero ver por hora cuántos pacientes se registraron, de qué nivel y cuánto esperaron hasta entrar a consulta,
para detectar picos de carga y tiempos de espera fuera de lo esperado.

**Acceptance Criteria:**

**Dado** pacientes registrados en distintas horas, con y sin entrada a consulta
**Cuando** se invoca `GET /triage/reportes/operacion?desde=...&hasta=...` con fechas ISO 8601 y API key válida
**Entonces** responde HTTP 200 con una fila por hora UTC (bucket por `fechaRegistro`) que contiene total registrado, conteo por cada nivel, promedio y p95 en minutos desde el registro hasta la primera entrada `EN_CONSULTA`
**Y** el rango se interpreta en UTC como `[desde, hasta)`.

**Dado** pacientes sin entrada a consulta en un bucket
**Cuando** se calcula el reporte
**Entonces** cuentan en el total y en el conteo por nivel, pero se excluyen del promedio y del p95 (no se imputan como cero ni como tiempo actual)
**Y** si ningún paciente del bucket entró a consulta, promedio y p95 son `null`.

**Dado** un paciente con varias entradas a consulta registradas en auditoría
**Cuando** se calcula su espera
**Entonces** se usa solo la primera entrada `EN_CONSULTA`.

**Dado** la implementación
**Cuando** se revisa
**Entonces** la agregación se resuelve en una consulta SQL (CTE/funciones de ventana o `ROW_NUMBER`, agregados con `FILTER`, `date_trunc('hour', ...)` y `percentile_cont(0.95)`), y la aplicación solo mapea filas ya agrupadas; no itera en memoria sobre pacientes
**Y** existen índices sobre `patient(registered_at, level)` y `audit_event(patient_id, event_type, occurred_at)`.

**Dado** `desde` o `hasta` ausentes, con formato inválido o `desde >= hasta`
**Cuando** se invoca el reporte
**Entonces** responde HTTP 400 identificando el parámetro.

**Dado** datos sintéticos con resultados calculados a mano
**Cuando** corren las pruebas de integración contra PostgreSQL
**Entonces** total, conteos, promedio, p95 y límites del rango coinciden exactamente
**Y** el endpoint responde en el stack local de `docker compose`.

## Epic 3: El equipo médico conoce la carga activa y recibe escalaciones recuperables

El equipo médico puede consultar pacientes activos por nivel y recibir alertas de escalamiento en vivo; si se desconecta, recupera alertas persistidas sin duplicados. Cubre FR5 y FR6.

### Story 3.1: Persistir y consultar alertas de escalamiento

Como equipo médico,
quiero que cada escalamiento genere una alerta consultable,
para no perder ninguna escalación aunque no esté conectado en ese momento.

**Acceptance Criteria:**

**Dado** un paciente que el proceso de SLA escala
**Cuando** se confirma la transacción de escalamiento
**Entonces** en esa misma transacción se inserta una fila en `medical_alert` (nueva migración) con `alertId` UUID y `deliveryCursor` `BIGINT` monotónico asignado mediante contador singleton bajo `SELECT ... FOR UPDATE`
**Y** un reintento del escalamiento no crea una segunda alerta.

**Dado** alertas persistidas
**Cuando** se invoca `GET /triage/alertas?despuesDe=<deliveryCursor>&limite=<n>` con API key válida
**Entonces** responde las alertas con cursor mayor al indicado, ordenadas por `deliveryCursor` ascendente y limitadas por página
**Y** cada alerta contiene solo `alertId`, `deliveryCursor`, `patientId`, `nivel` y timestamp, sin nombre ni signos vitales.

**Dado** parámetros de paginación inválidos
**Cuando** se consulta
**Entonces** responde HTTP 400 identificando el parámetro.

**Dado** las pruebas de integración
**Cuando** se escalan varios pacientes
**Entonces** los cursores son estrictamente crecientes en el orden de confirmación y no existe endpoint para borrar alertas.

### Story 3.2: Recibir alertas en vivo por SSE con recuperación

Como equipo médico,
quiero recibir las escalaciones en tiempo real y recuperar las perdidas al reconectarme,
para reaccionar rápido sin depender de una conexión perfecta.

**Acceptance Criteria:**

**Dado** un cliente autenticado conectado a `GET /triage/alertas/stream` (`text/event-stream`)
**Cuando** se escala un paciente
**Entonces** el cliente recibe la alerta con `id: <deliveryCursor>` y los datos mínimos de la alerta, dentro de la cadencia de polling configurada.

**Dado** un cliente que se reconecta enviando `Last-Event-ID`
**Cuando** se abre el stream
**Entonces** recibe primero todas las alertas con cursor mayor al indicado y luego las nuevas
**Y** el contrato documenta que puede haber repeticiones y que el cliente deduplica por `alertId`.

**Dado** la implementación
**Cuando** se revisa
**Entonces** el stream lee la tabla persistida con polling reactivo acotado y heartbeat periódico, sin `Sink` local como fuente de verdad, de modo que funciona tras reinicios y con varias réplicas
**Y** sin API key válida responde HTTP 401.

**Dado** las pruebas
**Cuando** se simula desconexión y reconexión con `Last-Event-ID`
**Entonces** no se pierde ninguna alerta escalada en el intervalo
**Y** se expone una métrica de conexiones SSE activas, y la decisión del canal queda en ADR-0002.

### Story 3.3: Consultar la carga activa de urgencias por nivel

Como equipo médico,
quiero saber cuántos pacientes activos hay por nivel de triaje,
para dimensionar la carga sin consultar el sistema de admisión.

**Acceptance Criteria:**

**Dado** el servicio Asistente Clínico consumiendo `triaje.eventos`
**Cuando** recibe eventos v1
**Entonces** actualiza en Redis, de forma atómica (script Lua o equivalente), el último estado por `patientId` y los conteos por nivel solo si `aggregateVersion` es mayor que la materializada
**Y** `paciente_registrado` suma al nivel, `paciente_escalado` y `paciente_en_consulta` no cambian el conteo y `paciente_alta` resta una sola vez.

**Dado** eventos duplicados, versiones viejas o una versión saltada
**Cuando** se procesan
**Entonces** los duplicados y versiones viejas no alteran conteos, la versión mayor se aplica comparando el snapshot completo con el estado previo y los saltos se registran en métrica/log.

**Dado** un tipo de evento o `schemaVersion` no soportado, o un mensaje inválido
**Cuando** se consume
**Entonces** se envía a DLQ con error operativo, sin descartarlo silenciosamente
**Y** el offset se confirma solo después de que Redis aplicó el cambio.

**Dado** la proyección
**Cuando** se invoca `GET /asistente/estado-urgencias` con API key válida del Asistente
**Entonces** responde los pacientes activos (estado distinto de `ALTA`) por cada nivel, incluidos los niveles con cero
**Y** el Asistente nunca llama a la API ni a la base de Triage.

**Dado** el entorno local
**Cuando** se ejecuta `docker compose up`
**Entonces** se levantan ambos servicios, PostgreSQL, Kafka y Redis en un solo comando
**Y** pruebas de integración cubren registro → consulta → alta, duplicados y replay desde el inicio del topic.

### Story 3.4: Exponer el Asistente Clínico en Azure

Como evaluador,
quiero acceder al Asistente desplegado en Azure por HTTPS,
para verificar la integración entre servicios por eventos en la nube.

**Acceptance Criteria:**

**Dado** la infraestructura de la Story 1.6
**Cuando** se aplica Terraform con los cambios de esta historia
**Entonces** se agrega Azure Managed Redis en el mismo grupo y el secreto de API key del Asistente en Key Vault, sin secretos en Git ni en el estado.

**Dado** los manifests del Asistente
**Cuando** se aplican en AKS
**Entonces** existen `Deployment`, `Service`, `ServiceAccount`, ConfigMap, probes y requests/limits propios, con credenciales vía Workload ID/CSI
**Y** el pipeline construye y publica las dos imágenes con tags inmutables.

**Dado** un paciente registrado por la URL HTTPS de Triage
**Cuando** se consulta `GET /asistente/estado-urgencias` por la URL HTTPS del Asistente
**Entonces** el conteo refleja al paciente a partir de los eventos de Event Hubs
**Y** `GET /triage/alertas/stream` es accesible por HTTPS sin que el ingress corte el stream antes del heartbeat.

## Epic 4: El equipo médico consulta protocolos con fuentes y abstención explícita

Un usuario médico pregunta en lenguaje natural y recibe una respuesta sustentada con protocolo/sección o el aviso exacto de que no existe protocolo disponible, sin contenido clínico generado. Cubre FR7.

### Story 4.1: Responder consultas citando el protocolo del corpus

Como médico,
quiero preguntar en lenguaje natural sobre un protocolo y recibir la respuesta con su fuente,
para aplicar el protocolo correcto y poder verificarlo.

**Acceptance Criteria:**

**Dado** el corpus mínimo versionado (PROTO-001, PROTO-002, PROTO-003) sin contenido agregado
**Cuando** arranca el Asistente
**Entonces** se divide por protocolo y sección con metadata (`protocolId`, `sectionId`, `corpusVersion`), se calculan embeddings `text-embedding-3-small` y se carga `SimpleVectorStore` antes de que readiness responda OK.

**Dado** la pregunta "¿Cuáles son las medidas iniciales ante una crisis hipertensiva?"
**Cuando** se invoca `POST /asistente/consulta` con `{ "pregunta": "..." }` y API key válida
**Entonces** responde `{ "respuesta": "...", "fuentes": [{ "protocolo": "PROTO-001", "seccion": "..." }] }` con contenido de PROTO-001
**Y** la pregunta "¿Cuándo se administra el primer antibiótico en sepsis?" responde con contenido y cita de PROTO-002.

**Dado** la generación
**Cuando** se construye la respuesta
**Entonces** el modelo `gpt-5.4-mini` (Spring AI 1.0.x, Azure OpenAI/Foundry) redacta solo con los fragmentos recuperados, con `ChatClient.stream()` compuesto con Reactor, límite de tokens y sin herramientas ni acciones habilitadas
**Y** `fuentes` se construye desde la metadata de los fragmentos usados, nunca desde texto del modelo.

**Dado** el embedding de la pregunta y la búsqueda vectorial (APIs síncronas)
**Cuando** se ejecutan
**Entonces** corren en un scheduler dedicado con hilos y cola acotados, nunca en el event loop.

**Dado** una pregunta vacía o mayor al límite configurado
**Cuando** se invoca la consulta
**Entonces** responde HTTP 400 identificando el campo.

**Dado** las pruebas
**Cuando** se ejecutan con el modelo mockeado
**Entonces** las dos consultas citan el protocolo esperado sin llamar al proveedor real
**Y** no se registran en logs la pregunta ni la respuesta clínica completa.

### Story 4.2: Abstenerse cuando no existe protocolo

Como médico,
quiero que el asistente diga explícitamente que no hay protocolo cuando la pregunta no está cubierta,
para no recibir contenido clínico inventado.

**Acceptance Criteria:**

**Dado** la pregunta "¿Cuál es el tratamiento de primera línea para la diabetes tipo 2?"
**Cuando** se invoca `POST /asistente/consulta`
**Entonces** responde únicamente el aviso fijo de que no existe protocolo disponible, con `fuentes` vacío y sin contenido clínico
**Y** el LLM no se invoca.

**Dado** el umbral mínimo de similitud configurable
**Cuando** se calibra con las tres consultas del baseline
**Entonces** las dos consultas cubiertas lo superan y la de diabetes no; el valor elegido y su calibración quedan documentados.

**Dado** una respuesta generada cuyas fuentes no corresponden a un protocolo existente en el corpus
**Cuando** se valida antes de responder
**Entonces** se descarta y se devuelve el aviso de abstención (RN-05).

**Dado** las pruebas con modelo mockeado
**Cuando** el mock intenta devolver contenido para una pregunta sin coincidencia o citar un protocolo inexistente
**Entonces** la API responde solo el aviso de abstención.

### Story 4.3: Responder con claridad cuando el proveedor LLM falla

Como médico,
quiero un error claro cuando el proveedor del modelo no está disponible,
para saber que debo consultar el protocolo por otra vía.

**Acceptance Criteria:**

**Dado** el proveedor de chat o embeddings caído, lento o con errores
**Cuando** se invoca `POST /asistente/consulta`
**Entonces** responde HTTP 503 con un mensaje claro y sin contenido clínico
**Y** las llamadas tienen timeout, Resilience4j Circuit Breaker y bulkhead configurables.

**Dado** el circuito abierto o el scheduler de embeddings saturado
**Cuando** llegan nuevas consultas
**Entonces** se rechazan rápido con HTTP 503 sin crear colas sin límite.

**Dado** la observabilidad
**Cuando** se invoca el proveedor
**Entonces** se registra una métrica Micrometer de latencia del LLM, y métricas de fallos y saturación
**Y** el endpoint de urgencias sigue respondiendo con el proveedor caído, y Triage no se ve afectado.

**Dado** las pruebas
**Cuando** el mock simula timeout y error
**Entonces** se verifican el 503 y la apertura del circuito.

### Story 4.4: Entregar la solución integrada y documentada

Como evaluador,
quiero probar ambos servicios en Azure y entender la arquitectura desde el README,
para evaluar la prueba completa sin pasos manuales ocultos.

**Acceptance Criteria:**

**Dado** la infraestructura existente
**Cuando** se aplica Terraform y el pipeline
**Entonces** los deployments `gpt-5.4-mini` y `text-embedding-3-small` y su clave quedan configurados por entorno desde Key Vault
**Y** las tres consultas del baseline responden por la URL HTTPS del Asistente según 4.1 y 4.2.

**Dado** el repositorio
**Cuando** se lee el README
**Entonces** incluye el comando único de ejecución local, el diagrama de arquitectura, las tecnologías, las decisiones justificadas, el formato de eventos Kafka, la decisión RF-06, los supuestos y ambigüedades documentados, las URLs de Azure y cómo ejecutar los tests
**Y** existen al menos dos ADR en `docs/adr/`.

**Dado** `./gradlew test` en cada servicio
**Cuando** se ejecuta
**Entonces** pasan las pruebas de E1-E6, transiciones válidas e inválidas, escalamiento idempotente, citas RAG y abstención, sin proveedor LLM real.

**Dado** el flujo completo en Azure
**Cuando** se registra un paciente con SLA corto configurado para la demo
**Entonces** se observa en orden: registro, conteo activo en el Asistente, escalamiento, alerta por SSE y alta que decrementa el conteo.
