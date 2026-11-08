package com.vidacruz.clinical_assistant_service.domain;

import java.util.Map;

/** Pacientes activos por nivel; contiene siempre todos los niveles (cero incluido). */
public record EstadoUrgencias(Map<NivelTriaje, Long> pacientesActivos) {

    public long totalActivos() {
        return pacientesActivos.values().stream().mapToLong(Long::longValue).sum();
    }
}
