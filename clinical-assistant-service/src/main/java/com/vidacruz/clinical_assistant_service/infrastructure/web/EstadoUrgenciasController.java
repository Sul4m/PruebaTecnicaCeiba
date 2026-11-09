package com.vidacruz.clinical_assistant_service.infrastructure.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vidacruz.clinical_assistant_service.application.ProyeccionUrgenciasService;
import reactor.core.publisher.Mono;

/** Lee solo la proyeccion Redis; nunca llama a la API ni a la base de Triage. */
@RestController
@RequestMapping("/asistente")
public class EstadoUrgenciasController {

    private final ProyeccionUrgenciasService service;

    public EstadoUrgenciasController(ProyeccionUrgenciasService service) {
        this.service = service;
    }

    @GetMapping(path = "/estado-urgencias", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<EstadoUrgenciasResponse> estado() {
        return service.estado().map(EstadoUrgenciasResponse::from);
    }
}
