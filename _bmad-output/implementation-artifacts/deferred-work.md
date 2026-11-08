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
