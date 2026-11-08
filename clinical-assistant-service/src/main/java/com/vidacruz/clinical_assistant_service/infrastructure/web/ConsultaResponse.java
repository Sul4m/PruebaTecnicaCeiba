package com.vidacruz.clinical_assistant_service.infrastructure.web;

import java.util.List;

import com.vidacruz.clinical_assistant_service.domain.ConsultaResultado;
import com.vidacruz.clinical_assistant_service.domain.FuenteProtocolo;

public record ConsultaResponse(String respuesta, List<FuenteProtocolo> fuentes) {

    static ConsultaResponse from(ConsultaResultado r) {
        return new ConsultaResponse(r.respuesta(), r.fuentes());
    }
}
