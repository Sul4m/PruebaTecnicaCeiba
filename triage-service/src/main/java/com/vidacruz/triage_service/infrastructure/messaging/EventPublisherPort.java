package com.vidacruz.triage_service.infrastructure.messaging;

import reactor.core.publisher.Mono;

public interface EventPublisherPort {
    /** Completa solo cuando el broker confirmó la escritura. */
    Mono<Void> publish(String topic, String key, String json);
}
