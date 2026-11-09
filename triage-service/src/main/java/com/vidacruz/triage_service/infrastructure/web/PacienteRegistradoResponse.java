package com.vidacruz.triage_service.infrastructure.web;

import com.vidacruz.triage_service.domain.Patient;
import java.time.Instant;
import java.util.UUID;

public record PacienteRegistradoResponse(
        UUID id,
        String nivel,
        int slaMinutos,
        String estado,
        Instant fechaRegistro) {

    static PacienteRegistradoResponse from(Patient p) {
        return new PacienteRegistradoResponse(p.id(), p.level().name(), p.slaMinutes(),
                p.status().name(), p.registeredAt());
    }
}
