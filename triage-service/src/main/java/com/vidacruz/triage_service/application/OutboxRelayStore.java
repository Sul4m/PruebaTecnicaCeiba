package com.vidacruz.triage_service.application;

import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Operaciones del outbox que usa el relay. */
public interface OutboxRelayStore {

    /** Pendientes (ni publicados ni en dead-letter) en orden de seq, hasta {@code limit}. */
    Flux<PendingOutboxEvent> findPending(int limit);

    Mono<Void> markPublished(UUID id, Instant at);

    /** Suma un intento, guarda el último error y devuelve el total de intentos. */
    Mono<Integer> recordFailure(UUID id, String error);

    Mono<Void> markDeadLettered(UUID id, Instant at);

    Mono<Long> countPending();
}
