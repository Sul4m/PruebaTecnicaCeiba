package com.vidacruz.triage_service.infrastructure.web;

import com.vidacruz.triage_service.domain.MedicalAlert;
import java.time.Instant;
import java.util.UUID;

/** Contrato público de la alerta: exactamente estas cinco claves, sin datos personales ni clínicos. */
record AlertaResponse(UUID alertId, long deliveryCursor, UUID patientId, String nivel, Instant timestamp) {

    static AlertaResponse from(MedicalAlert a) {
        return new AlertaResponse(a.alertId(), a.deliveryCursor(), a.patientId(), a.level().name(),
                a.createdAt());
    }
}
