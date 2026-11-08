- source_spec: `_bmad-output/implementation-artifacts/spec-1-2-consultar-un-paciente.md`
  summary: Leer `sla_minutes` persistido en lugar de derivar el SLA del enum `TriageLevel` al consultar un paciente.
  evidence: Al hacer configurables los SLA en la Story 1.5, el SLA mostrado en `GET /triage/pacientes/{id}` podría diferir del que se asignó al registrar.
- source_spec: `_bmad-output/implementation-artifacts/spec-4-1-responder-consultas-citando-el-protocolo-del-corpus.md`
  summary: Limitar la concurrencia y el costo de la generación del chat (bulkhead o cuota por clave) junto con el circuit breaker de la Story 4.3.
  evidence: `ChatClient.stream()` corre sin límite de concurrencia; una clave válida puede disparar costo y latencia en Azure OpenAI (OWASP LLM10).
- source_spec: `_bmad-output/implementation-artifacts/spec-4-1-responder-consultas-citando-el-protocolo-del-corpus.md`
  summary: Calibrar el umbral de similitud con `text-embedding-3-small` real usando las tres consultas del baseline antes del despliegue, y exigir `SPRING_PROFILES_ACTIVE=azure` en los manifests.
  evidence: El umbral 0.30 solo está calibrado con el doble de embeddings de desarrollo; el perfil azure usa 0.45 provisional.
