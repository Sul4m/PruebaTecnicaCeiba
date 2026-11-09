package com.vidacruz.clinical_assistant_service.domain;

/** Fragmento recuperado con su puntaje de similitud. */
public record RetrievedChunk(ProtocolChunk chunk, double score) {
}
