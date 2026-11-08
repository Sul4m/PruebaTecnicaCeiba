package com.vidacruz.triage_service.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegistrarPacienteRequest(
        @NotBlank @Size(min = 3, max = 100) @Pattern(regexp = NO_NUL, message = NUL_MSG) String nombreCompleto,
        @NotNull @Min(0) @Max(120) Integer edad,
        @NotBlank @Size(max = 200) @Pattern(regexp = NO_NUL, message = NUL_MSG) String quejaPrincipal,
        @NotNull @Valid SignosVitalesRequest signosVitales) {

    /** PostgreSQL no admite NUL en texto. */
    static final String NO_NUL = "[^\\u0000]*";
    static final String NUL_MSG = "no puede contener caracteres nulos";
}
