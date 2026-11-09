package com.vidacruz.triage_service.infrastructure.messaging;

import com.vidacruz.triage_service.application.OutboxRelayStore;
import com.vidacruz.triage_service.application.PendingOutboxEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.common.errors.RetriableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Único relay del outbox: lee pendientes en orden de {@code seq}, publica uno por uno y corta el ciclo en
 * el primer fallo para no adelantar una versión posterior de un paciente.
 */
@Component
@ConditionalOnProperty(prefix = "triage.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 500;

    /** Resultado de procesar un evento o un ciclo. */
    public enum Outcome {
        NONE, PUBLISHED, DEAD_LETTERED, RETRIABLE_FAILURE, FAILURE;

        public boolean stopsCycle() {
            return this == RETRIABLE_FAILURE || this == FAILURE;
        }
    }

    private final OutboxRelayStore store;
    private final EventPublisherPort publisher;
    private final OutboxProperties props;
    private final Clock clock;
    private final Counter deadLetterCounter;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicInteger backoffStreak = new AtomicInteger();

    private volatile Disposable loop;
    private volatile Scheduler scheduler;

    public OutboxRelay(OutboxRelayStore store, EventPublisherPort publisher, OutboxProperties props,
            MeterRegistry registry, Clock clock) {
        this.store = store;
        this.publisher = publisher;
        this.props = props;
        this.clock = clock;
        Gauge.builder("triage.outbox.pending", pending, AtomicLong::get).register(registry);
        this.deadLetterCounter = Counter.builder("triage.outbox.dead_letter").register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (loop != null) {
            return;
        }
        scheduler = Schedulers.newSingle("outbox-relay", true);
        loop = Mono.defer(this::runCycle)
                .onErrorResume(e -> {
                    log.warn("Ciclo del outbox falló: {}", describe(e));
                    return Mono.just(Outcome.RETRIABLE_FAILURE);
                })
                .flatMap(outcome -> Mono.delay(nextDelay(outcome), scheduler))
                .repeat()
                .subscribeOn(scheduler)
                .subscribe();
    }

    @jakarta.annotation.PreDestroy
    public synchronized void stop() {
        if (loop != null) {
            loop.dispose();
            loop = null;
        }
        if (scheduler != null) {
            scheduler.dispose();
            scheduler = null;
        }
    }

    /** Un ciclo: procesa un lote en orden y se detiene en el primer fallo. */
    public Mono<Outcome> runCycle() {
        return store.findPending(props.batchSize())
                .concatMap(this::process, 1)
                .takeUntil(Outcome::stopsCycle)
                .last(Outcome.NONE)
                .flatMap(outcome -> refreshPending().thenReturn(outcome));
    }

    public Duration nextDelay(Outcome outcome) {
        if (outcome != Outcome.RETRIABLE_FAILURE) {
            backoffStreak.set(0);
            return props.interval();
        }
        int streak = Math.min(backoffStreak.getAndIncrement(), 20);
        Duration delay = props.interval().multipliedBy(1L << streak);
        return delay.compareTo(props.maxBackoff()) > 0 ? props.maxBackoff() : delay;
    }

    private Mono<Void> refreshPending() {
        return store.countPending()
                .doOnNext(pending::set)
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    private Mono<Outcome> process(PendingOutboxEvent event) {
        return Mono.defer(() -> publisher.publish(KafkaTopicsConfig.EVENTS_TOPIC,
                        event.aggregateId().toString(), event.payload()))
                .thenReturn(Outcome.PUBLISHED)
                .onErrorResume(e -> handleFailure(event, e))
                // Fuera del onErrorResume: un fallo de BD al marcar no cuenta como fallo de publicación.
                .flatMap(outcome -> outcome == Outcome.PUBLISHED
                        ? store.markPublished(event.id(), now()).thenReturn(outcome)
                        : Mono.just(outcome));
    }

    private Mono<Outcome> handleFailure(PendingOutboxEvent event, Throwable error) {
        if (isRetriable(error)) {
            log.warn("Broker no disponible para el evento {} (seq {}): {}", event.id(), event.seq(),
                    describe(error));
            return Mono.just(Outcome.RETRIABLE_FAILURE);
        }
        return store.recordFailure(event.id(), describe(error))
                .flatMap(attempts -> {
                    if (attempts < props.maxAttempts()) {
                        log.warn("Fallo no reintentable del evento {} (intento {}/{}): {}", event.id(), attempts,
                                props.maxAttempts(), describe(error));
                        return Mono.just(Outcome.FAILURE);
                    }
                    return deadLetter(event);
                });
    }

    private Mono<Outcome> deadLetter(PendingOutboxEvent event) {
        return publisher.publish(KafkaTopicsConfig.DEAD_LETTER_TOPIC, event.aggregateId().toString(),
                        event.payload())
                .then(Mono.defer(() -> store.markDeadLettered(event.id(), now())))
                .then(Mono.fromSupplier(() -> {
                    deadLetterCounter.increment();
                    log.error("Evento {} enviado a {} tras {} intentos", event.id(),
                            KafkaTopicsConfig.DEAD_LETTER_TOPIC, props.maxAttempts());
                    return Outcome.DEAD_LETTERED;
                }))
                .onErrorResume(e -> {
                    log.error("No se pudo enviar el evento {} al DLT: {}", event.id(), describe(e));
                    return Mono.just(Outcome.FAILURE);
                });
    }

    static boolean isRetriable(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof RetriableException || t instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Throwable e) {
        String text = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
