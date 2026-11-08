package com.vidacruz.triage_service.domain;

import java.time.Instant;
import java.util.UUID;

/** Alerta persistida por un escalamiento. Solo datos mínimos: sin nombre, edad, queja ni signos vitales. */
public record MedicalAlert(UUID alertId, long deliveryCursor, UUID patientId, TriageLevel level,
        Instant createdAt) {
}
