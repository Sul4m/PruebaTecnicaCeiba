- source_spec: `_bmad-output/implementation-artifacts/spec-1-2-consultar-un-paciente.md`
  summary: Leer `sla_minutes` persistido en lugar de derivar el SLA del enum `TriageLevel` al consultar un paciente.
  evidence: Al hacer configurables los SLA en la Story 1.5, el SLA mostrado en `GET /triage/pacientes/{id}` podría diferir del que se asignó al registrar.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-4-publicar-eventos-de-triaje-de-forma-durable.md`
  summary: Garantizar un único relay del outbox al desplegar varias réplicas (fijar `replicas: 1` en los manifests de la Story 1.6 o agregar un lock de base de datos como `pg_try_advisory_xact_lock`).
  evidence: Nada en la base de datos impide que dos instancias publiquen las mismas filas; con réplicas se duplicarían eventos y se rompería el orden por paciente.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-4-publicar-eventos-de-triaje-de-forma-durable.md`
  summary: Purgar o archivar las filas de `outbox_event` ya publicadas o enviadas al DLT.
  evidence: Nada elimina filas con `published_at` o `dead_lettered_at`; la tabla crece sin límite a escala.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-4-publicar-eventos-de-triaje-de-forma-durable.md`
  summary: Hacer configurables las particiones y réplicas de `triaje.eventos` y su DLT, y provisionarlas con Terraform en la Story 1.6 (Event Hubs).
  evidence: `KafkaTopicsConfig` fija 3 particiones y réplica 1, válido solo para el Compose local.
- source_spec: `_bmad-output/implementation-artifacts/spec-4-1-responder-consultas-citando-el-protocolo-del-corpus.md`
  summary: Limitar la concurrencia y el costo de la generación del chat (bulkhead o cuota por clave) junto con el circuit breaker de la Story 4.3.
  evidence: `ChatClient.stream()` corre sin límite de concurrencia; una clave válida puede disparar costo y latencia en Azure OpenAI (OWASP LLM10).
- source_spec: `_bmad-output/implementation-artifacts/spec-4-1-responder-consultas-citando-el-protocolo-del-corpus.md`
  summary: Calibrar el umbral de similitud con `text-embedding-3-small` real usando las tres consultas del baseline antes del despliegue, y exigir `SPRING_PROFILES_ACTIVE=azure` en los manifests.
  evidence: El umbral 0.30 solo está calibrado con el doble de embeddings de desarrollo; el perfil azure usa 0.45 provisional.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-5-escalar-por-sla-una-sola-vez.md`
  summary: Verificar (con test) la anulación por variable de entorno del SLA de niveles con guion bajo (`TRIAGE_SLA_MINUTES_URGENTE_2`) o fijar los SLA en un archivo de propiedades montado desde el ConfigMap de la Story 1.6.
  evidence: `SlaProperties` enlaza `Map<TriageLevel, Integer>`; solo se probaron las formas de archivo de propiedades y YAML, no la variable de entorno, que podría no enlazar la clave con guion bajo.
- source_spec: `_bmad-output/implementation-artifacts/spec-3-3-consultar-la-carga-activa-de-urgencias-por-nivel.md`
  summary: Exigir autenticación y TLS de Redis en el perfil `azure` (Azure Managed Redis) y fijar `REDIS_PASSWORD` y `REDIS_SSL` desde Key Vault en la Story 3.4.
  evidence: El Compose local usa Redis sin contraseña y `REDIS_PASSWORD` y `REDIS_SSL` tienen valores vacíos y falso por defecto; el perfil azure no valida que estén definidos.
- source_spec: `_bmad-output/implementation-artifacts/spec-4-3-responder-con-claridad-cuando-el-proveedor-llm-falla.md`
  summary: Hilo bloqueante del scheduler de embeddings sigue vivo tras el timeout (Mono.timeout no interrumpe la llamada sincrona de Spring AI); acotarlo con timeout del cliente HTTP del proveedor.
  evidence: Con proveedor lento los hilos quedan ocupados hasta que el SDK expira; el bulkhead y la cola acotada limitan el dano pero no lo eliminan.
- source_spec: `_bmad-output/implementation-artifacts/spec-4-3-responder-con-claridad-cuando-el-proveedor-llm-falla.md`
  summary: Validar `assistant.llm.*` (minimum-number-of-calls <= sliding-window-size) y revisar la configuracion half-open (hoy solo permitted-calls).
  evidence: Una combinacion invalida solo falla al crear el circuito, en la primera consulta.
- source_spec: `_bmad-output/implementation-artifacts/spec-4-3-responder-con-claridad-cuando-el-proveedor-llm-falla.md`
  summary: Registrar metricas de estado del circuito (TaggedCircuitBreakerMetrics / TaggedBulkheadMetrics) y exponerlas.
  evidence: Hoy solo hay timer y contadores de fallos/rechazos; no se ve el estado del circuito ni se expone `metrics` por actuator.
- source_spec: `_bmad-output/implementation-artifacts/spec-4-3-responder-con-claridad-cuando-el-proveedor-llm-falla.md`
  summary: Tests de half-open y recuperacion del circuito, e IT HTTP de embeddings caido.
  evidence: Solo se prueba apertura y rechazo rapido; embeddings se cubre a nivel de retriever.
