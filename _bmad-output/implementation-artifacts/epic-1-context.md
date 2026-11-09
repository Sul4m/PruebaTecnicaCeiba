# Epic 1 Context: El personal de admisión registra y gestiona pacientes con prioridades y tiempos claros

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Permitir que enfermería registre pacientes, reciba su nivel de triaje y SLA calculados de forma determinista, consulte y avance su estado con transiciones válidas, y confíe en que los vencimientos de SLA se escalen una sola vez con auditoría íntegra. Es la fuente de pacientes, estados y eventos de los que dependen las demás épicas (reporte, alertas, proyección del Asistente). Incluye además la publicación durable de eventos y, al final del proyecto, el primer despliegue de Triage en Azure.

## Stories

- Story 1.1: Registrar y clasificar un paciente
- Story 1.2: Consultar un paciente
- Story 1.3: Cambiar el estado con transiciones válidas
- Story 1.4: Publicar eventos de triaje de forma durable
- Story 1.5: Escalar por SLA una sola vez
- Story 1.6: Exponer Triage en Azure (diferida al final del proyecto)

## Orden de ejecución

Por decisión del usuario (2026-11-07), todo se construye y valida primero en local (Docker Compose) y con tests (Testcontainers). Las historias de despliegue en Azure (1.6 y 3.4) y la parte Azure de 4.4 se implementan al final, en el orden 1.6 → 3.4 → 4.4, cuando el resto esté terminado y verificado. En esta épica se ejecutan 1.1 → 1.5 en local; ninguna depende de Azure.

## Requirements & Constraints

- Registro con nombre (3-100 caracteres), edad (0-120), queja principal y signos vitales: FC 20-250, sistólica 60-260, diastólica 30-160 y siempre menor que la sistólica, SpO2 70-100, temperatura 34.0-42.0, dolor 0-10, GCS 3-15. Dato fuera de rango o ausente: HTTP 400 nombrando el campo, sin persistir nada.
- La respuesta de registro incluye `id`, `nivel`, `slaMinutos`, estado `REGISTRADO` y `fechaRegistro` ISO 8601.
- Clasificación por gravedad: prevalece el nivel más grave. 1 CRITICO (FC<40 o >120; SpO2<90; sistólica<90; GCS≤8); 2 URGENTE (SpO2 90-93; sistólica 90-100; temp ≥38.5; GCS 9-13); 3 URGENTE_2 (FC 90-120; temp 38.0-<38.5; dolor ≥7); 4 NO_URGENTE (resto); 5 NO_URGENTE_2 solo si cumple nivel 4 y la queja es administrativa (`documentos`, `recolección de resultados`, `renovación de recetario`, `vacunación`, `seguimiento`). Cubrir cada frontera con tests.
- SLA por nivel: 5, 10, 30, 60 y 120 min. Casos E1-E6 deben dar CRITICO, URGENTE, URGENTE_2, NO_URGENTE, NO_URGENTE_2, CRITICO.
- Transiciones permitidas: REGISTRADO→EN_CONSULTA, REGISTRADO→ALTA, EN_CONSULTA→ALTA. Cualquier otra: HTTP 409 descriptivo, sin cambios ni auditoría.
- `GET /triage/pacientes/{id}` devuelve datos completos, estado, flags (`escalado`) y tiempo en el estado actual.
- Auditoría de registro inicial y de todo cambio de estado o flag (timestamp, id paciente, estado anterior/nuevo, tipo de evento); sin endpoint para borrar o modificar auditoría.
- Escalamiento: proceso periódico sobre pacientes `REGISTRADO` que superan el SLA; marca flag persistente, publica `paciente_escalado` y audita. Idempotente (una sola vez, también con instancias en paralelo) y nunca escala `EN_CONSULTA` o `ALTA`. Intervalo y SLA configurables por propiedades para pruebas.
- Eventos en el topic `triaje.eventos`: `paciente_registrado`, `paciente_escalado`, `paciente_en_consulta`, `paciente_alta`. Triage nunca depende de la disponibilidad de Kafka ni del Asistente para confirmar una operación.
- Seguridad: API key propia de Triage en header `X-API-Key` (401 si falta o es incorrecta), leída de configuración/entorno; nunca hardcodeada ni en logs.
- Observabilidad: probes health/readiness, logs estructurados con correlación (sin nombre, signos vitales ni API key), contadores Micrometer de clasificaciones por nivel y de escalamientos.
- Pruebas unitarias e integración (Testcontainers PostgreSQL/Kafka o equivalente); `docker compose up` levanta Triage, PostgreSQL y Kafka con un solo comando.
- Todo código reactivo (`Mono`/`Flux`); sin llamadas bloqueantes en el event loop.

## Technical Decisions

- Stack del proyecto: Java 17, Spring Boot 3.5.x, WebFlux, R2DBC/PostgreSQL, Gradle. Arquitectura hexagonal liviana: dominio y casos de uso en el centro; HTTP y scheduler como adaptadores de entrada; R2DBC y Kafka detrás de puertos. No compartir dominio ni base con el Asistente.
- Tablas con migraciones versionadas: `patient` y `audit_event` (Story 1.1), `outbox_event` (Story 1.4). Las columnas temporales se guardan en UTC; los IDs son UUID.
- Cada comando que cambia estado o flag escribe paciente, auditoría y outbox en una sola transacción R2DBC. Transiciones concurrentes: actualización condicional por estado esperado (la perdedora recibe 409).
- Escalamiento: `UPDATE ... RETURNING` condicionado a `status='REGISTRADO' AND escalated=false` y SLA vencido; una sola transacción ganadora crea auditoría y outbox. Trabajo por lotes con `LIMIT`, concurrencia acotada, sin materializar todos los pacientes ni `collectList()` sin límite.
- Reloj inyectable (`Clock`) para tiempo en estado, SLA y fronteras de escalamiento deterministas.
- Contrato Kafka (ADR-0005): envelope JSON v1 con `eventId`, `eventType`, `schemaVersion`, `aggregateId`, `aggregateVersion` (monotónica por paciente, +1 por cada cambio publicable), `occurredAt` UTC y snapshot mínimo (`patientId`, `nivel`, `estado`, `escalated`). Sin nombre, edad ni signos vitales. Clave Kafka = `patientId`.
- Relay del outbox: un único relay inicial, lotes acotados, `acks=all` e idempotencia del productor; marca publicado solo tras confirmación del broker; reintentos con backoff; tras agotar reintentos va a dead-letter topic con error/métrica. Entrega al menos una vez.
- Se agregará `medical_alert` en Epic 3; al escalar, la alerta se insertará en la misma transacción (no es alcance de Epic 1).
- Azure (Story 1.6): Terraform con `subscription_id` y `location` como variables; grupo `rg-pruebatecnicaceiba-<sufijo>` con tag `project=PruebaTecnicaCeiba`; AKS, ACR, Key Vault con Workload ID/CSI, PostgreSQL Flexible Server y Event Hubs con endpoint Kafka (compatibilidad a validar). Manifests con Deployment, Service, ServiceAccount, ConfigMap, probes startup/readiness/liveness y requests/limits. CI con OIDC, imágenes con tag inmutable, Terraform solo si cambia la infraestructura; sin secretos en Git ni en el estado.

## Cross-Story Dependencies

- 1.1 es la base de todas: crea `patient`, `audit_event`, clasificación, API key, métricas y el Compose inicial.
- 1.2 y 1.3 dependen de 1.1; 1.3 también depende de la auditoría de 1.1.
- 1.4 se apoya en 1.1 y 1.3 (agrega outbox y `aggregateVersion` a registro y transiciones) y es requisito de 1.5, que encola `paciente_escalado` en el outbox.
- 1.5 depende de 1.1, 1.2 (flag visible en GET), 1.3 y 1.4.
- 1.6 despliega lo anterior y se ejecuta al final (antes de 3.4 y 4.4); requiere Event Hubs con compatibilidad Kafka. Ninguna historia 1.1-1.5 depende de ella.
- Con otras épicas: Epic 2 (reporte) usa el registro y la primera entrada a `EN_CONSULTA` de 1.1/1.3; Epic 3 consume los eventos de 1.4 y extiende el escalamiento de 1.5 con alertas SSE; Epic 4 y la entrega final integran ambos servicios.

## Conflictos detectados con el baseline

- El documento de épicas (NFR9) dice que AKS o Azure Container Apps son válidos; el baseline exige manifests Kubernetes desplegados en AKS. Prevalece el baseline: AKS (coincide con ADR-0004 y la Story 1.6).
- El baseline exige una URL desplegada en Azure como entregable, mientras que la decisión de orden difiere 1.6 al final; no es conflicto de reglas, pero 1.6 sigue siendo obligatoria y no puede omitirse.
- La numeración FR1-FR7 de las épicas no coincide con RF-01 a RF-06 del baseline (ej. FR4 = RF-05, FR5 = RF-06). Es solo de nomenclatura; las reglas del baseline son las que rigen.
- Las historias fijan HTTP 201 en el registro y HTTP 409 al repetir el mismo estado; el baseline no los especifica. Son decisiones del proyecto compatibles con el baseline.
