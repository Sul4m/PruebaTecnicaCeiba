package com.vidacruz.clinical_assistant_service.domain;

/** El proveedor de embeddings/chat o el corpus no esta disponible (HTTP 503). */
public class AssistantUnavailableException extends RuntimeException {

    public AssistantUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
