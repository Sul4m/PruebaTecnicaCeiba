package com.vidacruz.triage_service.application;

import java.util.UUID;

/** Fila pendiente del outbox, con el envelope ya serializado. */
public record PendingOutboxEvent(
        UUID id,
        long seq,
        UUID aggregateId,
        long aggregateVersion,
        String eventType,
        String payload,
        int attempts) {
}
