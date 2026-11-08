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
}
