package com.vidacruz.triage_service.application;

import com.vidacruz.triage_service.domain.TriageEventEnvelope;
import reactor.core.publisher.Mono;

public interface OutboxPort {
    /** Inserta el evento en el outbox; debe ejecutarse dentro de la transacción del caso de uso. */
    Mono<Void> append(TriageEventEnvelope event);
}
