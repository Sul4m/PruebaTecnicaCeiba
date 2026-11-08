package com.vidacruz.clinical_assistant_service.infrastructure.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vidacruz.clinical_assistant_service.application.ConsultaProtocoloService;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/asistente")
public class ConsultaController {

    private final ConsultaProtocoloService service;

    public ConsultaController(ConsultaProtocoloService service) {
        this.service = service;
    }

    @PostMapping(path = "/consulta", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ConsultaResponse> consultar(@Valid @RequestBody ConsultaRequest request) {
        return service.consultar(request.pregunta().strip()).map(ConsultaResponse::from);
    }
}
