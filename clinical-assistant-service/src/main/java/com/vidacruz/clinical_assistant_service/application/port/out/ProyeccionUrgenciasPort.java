package com.vidacruz.clinical_assistant_service.application.port.out;

import java.util.Map;

import com.vidacruz.clinical_assistant_service.domain.EventoTriaje;
import com.vidacruz.clinical_assistant_service.domain.NivelTriaje;
import com.vidacruz.clinical_assistant_service.domain.ResultadoProyeccion;
import reactor.core.publisher.Mono;

/** Proyeccion materializada de pacientes activos. Implementada sobre Redis; reconstruible desde el topic. */
public interface ProyeccionUrgenciasPort {

    /** Aplica el evento de forma atomica (guarda de version + idempotencia por eventId). */
    Mono<ResultadoProyeccion> aplicar(EventoTriaje evento);

    /** Conteos materializados; los niveles sin datos pueden faltar. */
    Mono<Map<NivelTriaje, Long>> conteos();
}
