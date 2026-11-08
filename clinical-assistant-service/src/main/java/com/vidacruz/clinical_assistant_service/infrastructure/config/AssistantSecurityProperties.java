package com.vidacruz.clinical_assistant_service.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * API key propia del Asistente. Solo por entorno ({@code ASISTENTE_API_KEY}); sin valor por defecto, de modo que
 * el servicio falla al arrancar en vez de quedar sin proteccion. {@code toString} no expone el secreto.
 */
@Validated
@ConfigurationProperties("assistant.security")
public record AssistantSecurityProperties(@NotBlank String apiKey) {

    @Override
    public String toString() {
        return "AssistantSecurityProperties[apiKey=***]";
    }
}
