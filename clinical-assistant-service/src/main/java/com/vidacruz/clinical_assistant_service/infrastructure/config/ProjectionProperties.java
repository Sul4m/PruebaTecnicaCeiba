package com.vidacruz.clinical_assistant_service.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Proyeccion de urgencias: consumo de {@code triaje.eventos} hacia Redis. {@code maxAttempts} cuenta el primer
 * intento mas los reintentos antes de enviar el mensaje al DLT.
 */
@Validated
@ConfigurationProperties("assistant.projection")
public record ProjectionProperties(
        boolean enabled,
        @NotBlank String topic,
        @NotBlank String dltTopic,
        @NotBlank String groupId,
        @Min(1) int maxAttempts,
        @NotNull Duration retryBackoff,
        @NotNull Duration retryMaxBackoff) {
}
