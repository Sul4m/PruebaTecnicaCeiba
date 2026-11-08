package com.vidacruz.triage_service.infrastructure.web;

import com.vidacruz.triage_service.domain.Patient;
import com.vidacruz.triage_service.domain.VitalSigns;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record PacienteDetalleResponse(
        UUID id,
        String nombreCompleto,
        int edad,
        String quejaPrincipal,
        SignosVitales signosVitales,
        String nivel,
        int slaMinutos,
        String estado,
        Instant fechaRegistro,
        Instant fechaCambioEstado,
        long minutosEnEstadoActual,
        Flags flags) {

    public record SignosVitales(int frecuenciaCardiaca, int presionSistolica, int presionDiastolica, int spo2,
            BigDecimal temperatura, int dolor, int gcs) {
    }

    public record Flags(boolean escalado) {
    }

    static PacienteDetalleResponse from(Patient p, Instant now) {
        VitalSigns v = p.vitalSigns();
        // Minutos completos; nunca negativo aunque haya desfase de reloj.
        long minutes = Math.max(0, Duration.between(p.statusChangedAt(), now).toMinutes());
        return new PacienteDetalleResponse(p.id(), p.fullName(), p.age(), p.chiefComplaint(),
                new SignosVitales(v.heartRate(), v.systolic(), v.diastolic(), v.spo2(), v.temperature(),
                        v.pain(), v.gcs()),
                p.level().name(), p.slaMinutes(), p.status().name(), p.registeredAt(), p.statusChangedAt(),
                minutes, new Flags(p.escalated()));
    }
}
