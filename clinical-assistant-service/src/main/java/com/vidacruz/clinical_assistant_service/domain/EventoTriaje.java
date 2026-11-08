package com.vidacruz.clinical_assistant_service.domain;

/**
 * Evento de triaje v1 ya validado: snapshot completo y minimo del nuevo estado del paciente. Sin datos personales.
 */
public record EventoTriaje(String eventId, String eventType, String patientId, long aggregateVersion,
        NivelTriaje nivel, EstadoPaciente estado, boolean escalated) {
}
