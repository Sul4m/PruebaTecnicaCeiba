package com.vidacruz.clinical_assistant_service.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Configuracion del RAG (todo ajustable por entorno; sin secretos). */
@Validated
@ConfigurationProperties("assistant.rag")
public record AssistantRagProperties(
        @NotBlank String corpusVersion,
        @NotBlank String corpusLocation,
        @Min(1) @Max(10) int topK,
        @DecimalMin("0.0") @DecimalMax("1.0") double similarityThreshold,
        @Min(1) int maxOutputTokens,
        @Min(1) int maxAnswerChars,
        @NotNull Duration llmTimeout,
        @NotNull Duration embeddingTimeout,
        @NotNull @Valid Scheduler scheduler) {

    /** Scheduler dedicado y acotado para embeddings/busqueda vectorial (nunca el event loop). */
    public record Scheduler(@Min(1) int threads, @Min(1) int queueCapacity) {
    }
}
