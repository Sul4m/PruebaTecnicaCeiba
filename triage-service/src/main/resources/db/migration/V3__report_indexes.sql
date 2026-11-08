-- Story 2.1: índices del reporte de operación por hora (RF-05).
-- Redundante: el nuevo índice compuesto de audit_event ya cubre las búsquedas por patient_id y evita un índice extra en cada inserción.
DROP INDEX IF EXISTS idx_audit_event_patient;
CREATE INDEX idx_patient_registered_at_level ON patient (registered_at, level);
CREATE INDEX idx_audit_event_patient_type_occurred ON audit_event (patient_id, event_type, occurred_at);
