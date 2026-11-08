- source_spec: `_bmad-output/implementation-artifacts/spec-1-2-consultar-un-paciente.md`
  summary: Leer `sla_minutes` persistido en lugar de derivar el SLA del enum `TriageLevel` al consultar un paciente.
  evidence: Al hacer configurables los SLA en la Story 1.5, el SLA mostrado en `GET /triage/pacientes/{id}` podría diferir del que se asignó al registrar.
