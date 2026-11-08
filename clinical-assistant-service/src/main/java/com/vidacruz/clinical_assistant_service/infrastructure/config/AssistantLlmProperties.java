package com.vidacruz.clinical_assistant_service.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Resiliencia ante el proveedor LLM (chat y embeddings), ajustable por entorno. Los timeouts por llamada viven en
 * {@code assistant.rag.llm-timeout} y {@code assistant.rag.embedding-timeout}. Cada operacion (chat, embeddings)
 * tiene su propio circuit breaker y bulkhead con esta configuracion.
 */
@Validated
@ConfigurationProperties("assistant.llm")
public record AssistantLlmProperties(
        @NotNull @Valid CircuitBreaker circuitBreaker,
        @NotNull @Valid Bulkhead bulkhead) {

    public record CircuitBreaker(
            @DecimalMin("1") @DecimalMax("100") float failureRateThreshold,
            @Min(1) int slidingWindowSize,
            @Min(1) int minimumNumberOfCalls,
            @NotNull Duration waitDurationInOpenState,
            @Min(1) int permittedCallsInHalfOpenState) {
    }

    /** {@code maxWait} 0 = rechazo inmediato al saturarse (sin cola). */
    public record Bulkhead(
            @Min(1) int chatMaxConcurrentCalls,
            @Min(1) int embeddingMaxConcurrentCalls,
            @NotNull Duration maxWait) {
    }
}
