package com.vidacruz.triage_service.domain;

import java.time.Instant;
import java.util.UUID;

public record AuditEvent(
        UUID id,
        UUID patientId,
        PatientStatus previousStatus,
        PatientStatus newStatus,
        String eventType,
        Instant occurredAt) {

    public static final String PATIENT_REGISTERED = "paciente_registrado";
    public static final String PATIENT_IN_CONSULTATION = "paciente_en_consulta";
    public static final String PATIENT_DISCHARGED = "paciente_alta";
}
