package com.vidacruz.clinical_assistant_service.infrastructure.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.vidacruz.clinical_assistant_service.domain.EstadoUrgencias;

/** {@code niveles}: pacientes activos (estado distinto de ALTA) por cada nivel, en orden de severidad. */
public record EstadoUrgenciasResponse(Map<String, Long> niveles, long totalActivos) {

    static EstadoUrgenciasResponse from(EstadoUrgencias estado) {
        Map<String, Long> niveles = new LinkedHashMap<>();
        estado.pacientesActivos().forEach((nivel, n) -> niveles.put(nivel.name(), n));
        return new EstadoUrgenciasResponse(niveles, estado.totalActivos());
    }
}
