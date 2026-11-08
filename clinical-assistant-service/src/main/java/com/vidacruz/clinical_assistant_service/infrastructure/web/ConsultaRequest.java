package com.vidacruz.clinical_assistant_service.infrastructure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConsultaRequest(
        @NotBlank(message = "La pregunta es obligatoria")
        @Size(max = ConsultaRequest.MAX_PREGUNTA, message = "La pregunta supera el maximo de caracteres permitido")
        String pregunta) {

    public static final int MAX_PREGUNTA = 500;
}
