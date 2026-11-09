-- Story 1.5: barrido de escalamiento por SLA (RF-03). Solo indexa los pacientes aún escalables.
CREATE INDEX idx_patient_escalation_due ON patient (registered_at)
    WHERE status = 'REGISTRADO' AND escalated = false;
