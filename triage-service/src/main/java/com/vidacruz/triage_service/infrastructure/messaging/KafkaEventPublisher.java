package com.vidacruz.triage_service.infrastructure.messaging;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
class KafkaEventPublisher implements EventPublisherPort {

    private final KafkaTemplate<String, String> template;

    KafkaEventPublisher(KafkaTemplate<String, String> template) {
        this.template = template;
    }

    @Override
    public Mono<Void> publish(String topic, String key, String json) {
        // send() puede bloquear (metadata, max.block.ms): se suscribe en boundedElastic, nunca en el event loop.
        return Mono.defer(() -> Mono.fromFuture(template.send(topic, key, json)))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
