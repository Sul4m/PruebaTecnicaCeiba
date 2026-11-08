---
title: "PRD: Prueba Técnica Ceiba — sistema backend VidaCruz"
status: "final"
created: "2026-11-07"
updated: "2026-11-07"
inputs:
  - "Enunciado Prueba Técnica Dev. Webflux, 2026-10-29"
  - "Product Brief: VidaCruz - triaje digital y asistente clínico"
  - "docs/requirements/technical-test-baseline.md"
---

# PRD: Prueba Técnica Ceiba — sistema backend VidaCruz

## 0. Propósito del documento

Este PRD organiza los requisitos del enunciado para planificación, diseño y aceptación de la prueba técnica. Está dirigido a quien implemente y evalúe los dos microservicios. No sustituye el PDF ni duplica sus umbrales, datos E1–E6 o corpus: esos detalles normativos están en el [baseline técnico](../../../../docs/requirements/technical-test-baseline.md). La arquitectura y las decisiones de tecnología se registran en los [ADR globales](../../../../docs/adr/).

## 1. Visión

Entregar una demostración backend verificable que digitalice el flujo de registro y seguimiento de pacientes descrito por VidaCruz. Triage debe aplicar reglas deterministas, conservar trazabilidad, medir SLA y alertar cuando corresponda. El Asistente Clínico debe permitir consultar los protocolos suministrados y abstenerse cuando no encuentre respaldo.

El propósito es demostrar cumplimiento técnico y funcional de la prueba, no validar un producto para atención clínica real. El corpus del enunciado se reproduce para evaluación; no se presenta como guía médica validada.

## 2. Usuarios y trabajos que necesitan resolver

- **Enfermería / admisión:** registrar a una persona y conocer la clasificación y el SLA asignados.
- **Equipo médico:** conocer las alertas de escalamiento, consultar el estado agregado de urgencias y recuperar información de protocolos con fuente visible.
- **Operaciones / auditoría:** reconstruir cambios de estado, escalamiento y tiempos de atención; consultar agregados por hora.
- **Evaluador técnico:** levantar la solución, verificar los flujos mediante APIs y pruebas y acceder a las APIs desplegadas.

### 2.1 Flujos de trabajo que el sistema debe soportar

- **FL-1. Registrar y clasificar:** un cliente autorizado de admisión envía los datos del paciente; Triage valida el request, responde con clasificación y SLA y publica el registro para el otro servicio.
- **FL-2. Seguir y escalar:** el personal consulta un paciente; el scheduler detecta que venció su SLA mientras sigue `REGISTRADO`; Triage registra el escalamiento una sola vez, publica el evento y alerta a consumidores médicos conectados. Tras una desconexión, el consumidor puede recuperar alertas persistidas.
- **FL-3. Consultar protocolo:** un usuario médico pregunta por un protocolo; el Asistente responde con protocolo y sección del corpus, o devuelve únicamente el aviso de que no hay protocolo disponible. Si el proveedor de IA falla, devuelve HTTP 503 claro.
- **FL-4. Consultar operación:** un usuario autorizado solicita un rango ISO 8601; Triage devuelve métricas agrupadas por hora, calculadas en SQL.

## 3. Glosario

- **Paciente:** registro del caso que atraviesa los estados clínicos especificados en el baseline.
- **Nivel de triaje:** una de las cinco categorías RN-01, ordenadas por gravedad y con SLA asignado por RN-02.
- **SLA:** tiempo máximo desde el registro hasta la entrada a consulta; al superarlo puede dispararse el escalamiento.
- **Escalamiento:** cambio persistente que indica que el SLA venció para un paciente aún `REGISTRADO`.
- **Alerta:** notificación asociada a un escalamiento y recuperable después de una desconexión SSE.
- **Corpus:** protocolos clínicos mínimos incluidos en el enunciado y reproducidos en el baseline.
- **Fuente:** identificador del protocolo y sección que respaldan una respuesta del Asistente.
- **Vista de urgencias:** conteo persistido de pacientes activos por nivel, reconstruido por el Asistente a partir de eventos Kafka.

## 4. Capacidades y requisitos funcionales

Los requisitos usan IDs propios del PRD y remiten a los RF del enunciado. Los criterios específicos, límites inclusivos/exclusivos, datos de referencia y ejemplos completos están en el baseline.

### 4.1 Registro, clasificación y ciclo de atención

#### PRD-FR-01: Registrar y clasificar (RF-01, `POST /triage/pacientes`)

Un cliente autorizado puede registrar a un paciente con los datos obligatorios. Triage valida todos los campos, clasifica según RN-01 y asigna el SLA de RN-02.

**Criterios de aceptación:**

- Un request válido devuelve identificador, nivel, SLA, estado `REGISTRADO` y `fechaRegistro` ISO 8601, y publica `paciente_registrado` en `triaje.eventos`.
- Un valor inválido devuelve HTTP 400 e identifica el campo inválido.
- Los seis casos E1–E6 producen exactamente los niveles esperados que constan en el baseline.

#### PRD-FR-02: Cambiar estado y consultar paciente (RF-02, `PATCH /triage/pacientes/{id}/estado`, `GET /triage/pacientes/{id}`)

Un cliente autorizado puede consultar al paciente y solicitar una transición permitida por el enunciado. Triage mantiene su estado actual y el tiempo transcurrido en ese estado.

**Criterios de aceptación:**

- Se aceptan únicamente las transiciones definidas para `REGISTRADO`, `EN_CONSULTA` y `ALTA`.
- Una transición inválida devuelve HTTP 409 con explicación.
- Una transición válida escribe auditoría y publica el evento correspondiente.
- La consulta devuelve datos, estado, flags como escalamiento y tiempo en el estado actual.

#### PRD-FR-03: Vigilar SLA y escalar idempotentemente (RF-03, RN-03)

Un proceso configurable evalúa periódicamente pacientes activos. Si un paciente aún `REGISTRADO` supera su SLA, Triage persiste el escalamiento.

**Criterios de aceptación:**

- Los SLA e intervalo de evaluación pueden acortarse por configuración para pruebas.
- Un paciente se escala como máximo una vez: el segundo intento no duplica ni evento ni auditoría.
- Pacientes `EN_CONSULTA` o `ALTA` nunca se escalan.
- Un escalamiento publica `paciente_escalado` y produce el registro de auditoría requerido.

#### PRD-FR-04: Consultar reporte operativo (RF-05, `GET /triage/reportes/operacion`)

Un cliente autorizado puede pedir el reporte operacional para un rango ISO 8601. La respuesta se agrupa por hora e incluye total registrado, conteos por nivel, espera promedio y p95 hasta la primera entrada a consulta.

**Criterios de aceptación:**

- La agregación y el percentil se calculan en SQL avanzado o equivalente, sin cargar todos los registros a memoria de la aplicación.
- Se usa el rango `[desde,hasta)`, bucket por hora UTC de registro y `percentile_cont(0.95)` en PostgreSQL. Quienes aún no tienen entrada a consulta se excluyen del promedio/p95, no de totales/conteos; ver el [diseño](../../architecture/solution-design-PruebaTecnicaCeiba.md).

#### PRD-FR-05: Alertar al equipo médico (RF-06, `GET /triage/alertas/stream` y `/triage/alertas`)

Ante cada escalamiento, Triage conserva una alerta y la emite por SSE a consumidores médicos autenticados. La decisión de canal está en [ADR-0002](../../../../docs/adr/0002-alertas-medicas-sse.md).

**Criterios de aceptación:**

- La alerta acompaña al escalamiento persistido y al evento Kafka requerido.
- El cliente puede consultar alertas persistidas para recuperarse tras una desconexión; el flujo usa `Last-Event-ID` monotónico (`deliveryCursor`) para replay y `alertId` UUID para deduplicación.
- La notificación expone solo los datos necesarios para identificar la alerta; no difunde signos vitales ni información personal innecesaria.

### 4.2 Vista agregada de urgencias

#### PRD-FR-06: Mantener estado activo a partir de eventos (RF-04B, `GET /asistente/estado-urgencias`)

El Asistente consume `triaje.eventos` y persiste en Redis la proyección de pacientes activos por nivel. Responde mediante `GET /asistente/estado-urgencias`.

**Criterios de aceptación:**

- Se considera activo todo paciente cuyo estado no sea `ALTA`.
- La vista se reconstruye mediante eventos Kafka; el Asistente no llama a la API de Triage para calcularla.
- La actualización de conteos es coherente con registro, entrada a consulta y alta.

### 4.3 Consulta de protocolos

#### PRD-FR-07: Consultar el corpus con fuentes y abstención (RF-04A, RN-05, `POST /asistente/consulta`)

El Asistente permite consultar el corpus en lenguaje natural y responde con texto y fuentes, usando RAG. El framework y modelo elegidos están registrados en [ADR-0003](../../../../docs/adr/0003-spring-ai-y-modelo-openai.md); la selección no sustituye validación de fuentes ni abstención.

**Criterios de aceptación:**

- La consulta sobre crisis hipertensiva cita PROTO-001 y la sección que respalda la respuesta.
- La consulta sobre antibiótico en sepsis cita PROTO-002 y la sección que respalda la respuesta.
- La pregunta sobre diabetes tipo 2 devuelve exclusivamente el aviso de que no existe protocolo disponible, sin contenido clínico generado.
- La respuesta respeta el contrato JSON `{ "respuesta": "...", "fuentes": [{ "protocolo": "...", "seccion": "..." }] }` definido en el baseline.
- Una fuente citada debe existir en el corpus; las pruebas pueden sustituir el proveedor LLM por un mock.
- Si el proveedor LLM no está disponible, el endpoint devuelve HTTP 503 y un mensaje claro.

## 5. Requisitos no funcionales transversales

- **Reactividad:** ambos servicios usan Spring Boot 3, Spring WebFlux y Reactor; Triage usa R2DBC para PostgreSQL. No se bloquea el event loop; se conserva composición reactiva también en las llamadas a IA.
- **Integración:** Kafka usa el topic `triaje.eventos` y los cuatro eventos requeridos. El [diseño global](../../architecture/solution-design-PruebaTecnicaCeiba.md) y el [ADR-0005](../../../../docs/adr/0005-outbox-y-contrato-kafka.md) especifican formato/versionado, outbox y tolerancia a fallas.
- **Independencia:** Triage no depende de la disponibilidad del Asistente para registrar, clasificar o gestionar pacientes.
- **Resiliencia IA:** Resilience4j Circuit Breaker sobre llamadas al proveedor, con modo degradado HTTP 503.
- **Auditoría:** todo registro, cambio de estado y flag relevante queda con timestamp, id de paciente, estado anterior/nuevo y tipo de evento; no existe API para borrar auditoría.
- **Seguridad:** autenticación de endpoints mediante una API key independiente por servicio; validar entradas; credenciales fuera del código y Git. La decisión y almacenamiento de secretos se detallan en el diseño global.
- **Observabilidad:** probes de health/readiness, logs estructurados con correlación y métricas Micrometer para clasificaciones, escalamientos y latencia LLM. OpenTelemetry es un bonus.
- **Pruebas:** pruebas unitarias y de integración para casos enumerados en el baseline; PostgreSQL, Kafka y LLM deben poder probarse con infraestructura de test o dobles apropiados.
- **Ejecución y entrega:** un comando Docker Compose levanta PostgreSQL, Kafka o equivalente, Redis y ambos servicios. El repositorio incluye README, manifests Kubernetes, Terraform y una URL Azure con APIs accesibles.

## 6. Guardrails de seguridad y alcance clínico

- El alcance es una prueba técnica backend; no se construye frontend ni se habilita diagnóstico o decisión clínica autónoma.
- El corpus provisto no demuestra suficiencia, vigencia ni aprobación clínica. No se amplía con recomendaciones clínicas inventadas.
- No se deben incluir credenciales en código ni usar datos clínicos reales para demostrar los flujos.
- El escalamiento notifica al equipo; no sustituye la valoración humana ni establece conducta médica nueva.

## 7. No objetivos

- Aplicación web o móvil para paciente o personal.
- Integración con historia clínica electrónica, identidad hospitalaria o sistemas existentes no incluidos en la prueba.
- Uso asistencial real, validación clínica o regulatoria, certificación o garantía de resultados sanitarios.
- Nuevos protocolos clínicos fuera del corpus mínimo.
- Clasificación o tratamiento autónomo por IA.

## 8. Alcance de la primera entrega

### Incluye

- Los dos microservicios, sus APIs y responsabilidades del enunciado.
- Reglas RN-01–RN-05, datos E1–E6 y corpus mínimo del baseline.
- Eventos Kafka, proyección Redis del Asistente y reportes SQL.
- Alertas SSE persistidas según ADR-0002.
- RAG con Spring AI y OpenAI GPT-5.4-mini servido en Azure según ADR-0003.
- Docker Compose de un comando, pruebas, manifests Kubernetes y Terraform.
- Despliegue real en Azure con APIs accesibles y URL documentada.

### Fuera de alcance de esta entrega

- Frontend, producto clínico listo para producción y operación hospitalaria real.
- Entrega de SMS, correo o push externo para alertas; el canal escogido es SSE y recuperación por API.
- Portabilidad entre nubes; el destino elegido es AKS.

## 9. Métricas de éxito de la prueba

- **SM-1 — Clasificación correcta:** 6 de 6 casos E1–E6 devuelven el nivel esperado. Valida PRD-FR-01.
- **SM-2 — Flujos del paciente:** pasan pruebas de validación, transiciones, SLA, auditoría e idempotencia exigidas. Valida PRD-FR-01 a PRD-FR-03.
- **SM-3 — Integridad de reportes:** el reporte horario contiene métricas requeridas calculadas en la base de datos y comprobadas contra datos de prueba. Valida PRD-FR-04.
- **SM-4 — Respuestas sustentadas:** las dos preguntas con coincidencia citan la fuente esperada; la pregunta sin coincidencia se abstiene sin añadir contenido clínico. Valida PRD-FR-07.
- **SM-5 — Aislamiento de fallos:** el LLM simulado como no disponible produce HTTP 503 en Asistente, mientras Triage conserva sus flujos propios. Valida PRD-FR-07 y los NFR de resiliencia.
- **SM-6 — Entrega reproducible:** el stack local se levanta con un comando y las APIs desplegadas responden desde la URL entregada en Azure. Valida requisitos de ejecución y despliegue.

No se definen objetivos de latencia ni mejoras clínicas cuantitativas porque el enunciado no los especifica.

**Contramétrica:** cero contenido clínico generado en la consulta del caso sin coincidencia; no se debe mejorar la tasa de respuesta a costa de romper la abstención de RN-05.

## 10. Resoluciones de diseño y parámetros del despliegue temporal

Las decisiones funcionales y técnicas que podían fijarse con el enunciado están resueltas en el [diseño global](../../architecture/solution-design-PruebaTecnicaCeiba.md) y los ADR enlazados desde allí: esquema/versionado Kafka y outbox (ADR-0005), API key, RAG/vector store/embeddings (ADR-0003), semántica RF-05, recuperación SSE (ADR-0002) y replay de Redis. No se consideran preguntas abiertas para implementación.

El despliegue se realiza en la suscripción Azure del usuario. Terraform recibe `subscription_id` y `location` como variables del entorno; no son preguntas abiertas de producto ni requisitos para producción. La URL pública y configuración TLS se implementan con el endpoint elegido para la evaluación. El grupo de recursos es temporal y se elimina después de la revisión técnica mediante el script local ignorado por Git. La compatibilidad de Event Hubs Kafka con la configuración elegida se valida al implementar la integración.

## 11. Supuestos

- La solución se evalúa como demostración de software backend y no como servicio clínico en producción.
- Un consumidor médico autorizado mantiene SSE para recibir alertas y consulta la API de recuperación si estuvo desconectado.
- Las reglas y protocolos del baseline son la fuente exacta de aceptación para los casos de esta prueba; no se reinterpretan como estándares médicos generales.
