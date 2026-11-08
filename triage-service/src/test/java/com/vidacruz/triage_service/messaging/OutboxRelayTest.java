package com.vidacruz.triage_service.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage_service.application.OutboxRelayStore;
import com.vidacruz.triage_service.application.PendingOutboxEvent;
import com.vidacruz.triage_service.infrastructure.messaging.EventPublisherPort;
import com.vidacruz.triage_service.infrastructure.messaging.OutboxProperties;
import com.vidacruz.triage_service.infrastructure.messaging.OutboxRelay;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-11-08T10:15:30.123456Z");
    private static final String TOPIC = "triaje.eventos";
    private static final String DLT = "triaje.eventos.dlt";

    /** Store en memoria con la misma semántica de pendientes que la tabla. */
    static class FakeStore implements OutboxRelayStore {
        final Map<UUID, PendingOutboxEvent> rows = new LinkedHashMap<>();
        final Map<UUID, Instant> published = new LinkedHashMap<>();
        final Map<UUID, Instant> dead = new LinkedHashMap<>();
        final Map<UUID, String> lastError = new LinkedHashMap<>();

        PendingOutboxEvent add(long seq) {
            PendingOutboxEvent e = new PendingOutboxEvent(UUID.randomUUID(), seq, UUID.randomUUID(), 1,
                    "paciente_registrado", "{\"seq\":" + seq + "}", 0);
            rows.put(e.id(), e);
            return e;
        }

        int attempts(PendingOutboxEvent e) {
            return rows.get(e.id()).attempts();
        }

        boolean isPending(PendingOutboxEvent e) {
            return !published.containsKey(e.id()) && !dead.containsKey(e.id());
        }

        @Override
        public Flux<PendingOutboxEvent> findPending(int limit) {
            return Flux.defer(() -> Flux.fromIterable(rows.values().stream()
                    .filter(this::isPending)
                    .sorted((a, b) -> Long.compare(a.seq(), b.seq()))
                    .limit(limit).toList()));
        }

        @Override
        public Mono<Void> markPublished(UUID id, Instant at) {
            return Mono.fromRunnable(() -> published.put(id, at));
        }

        @Override
        public Mono<Integer> recordFailure(UUID id, String error) {
            return Mono.fromSupplier(() -> {
                PendingOutboxEvent e = rows.get(id);
                PendingOutboxEvent next = new PendingOutboxEvent(e.id(), e.seq(), e.aggregateId(),
                        e.aggregateVersion(), e.eventType(), e.payload(), e.attempts() + 1);
                rows.put(id, next);
                lastError.put(id, error);
                return next.attempts();
            });
        }

        @Override
        public Mono<Void> markDeadLettered(UUID id, Instant at) {
            return Mono.fromRunnable(() -> dead.put(id, at));
        }

        @Override
        public Mono<Long> countPending() {
            return Mono.fromSupplier(() -> rows.values().stream().filter(this::isPending).count());
        }
    }

    record Call(String topic, String key, String json) {
    }

    static class FakePublisher implements EventPublisherPort {
        final List<Call> calls = new ArrayList<>();
        BiFunction<String, String, Throwable> failure = (topic, json) -> null;

        @Override
        public Mono<Void> publish(String topic, String key, String json) {
            return Mono.defer(() -> {
                calls.add(new Call(topic, key, json));
                Throwable t = failure.apply(topic, json);
                return t == null ? Mono.empty() : Mono.error(t);
            });
        }

        List<String> payloadsOn(String topic) {
            return calls.stream().filter(c -> c.topic().equals(topic)).map(Call::json).toList();
        }
    }

    private FakeStore store;
    private FakePublisher publisher;
    private SimpleMeterRegistry registry;
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        publisher = new FakePublisher();
        registry = new SimpleMeterRegistry();
        OutboxProperties props = new OutboxProperties(true, Duration.ofSeconds(1), 100, 3, Duration.ofSeconds(5));
        relay = new OutboxRelay(store, publisher, props, registry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void publishesPendingInSeqOrderAndMarksPublished() {
        PendingOutboxEvent e2 = store.add(2);
        PendingOutboxEvent e1 = store.add(1);

        relay.runCycle().block();

        assertThat(publisher.calls).extracting(Call::json).containsExactly(e1.payload(), e2.payload());
        assertThat(publisher.calls).allSatisfy(c -> assertThat(c.topic()).isEqualTo(TOPIC));
        assertThat(publisher.calls.get(0).key()).isEqualTo(e1.aggregateId().toString());
        assertThat(store.published).containsOnlyKeys(e1.id(), e2.id());
        assertThat(store.published.get(e1.id())).isEqualTo(NOW);
        assertThat(registry.get("triage.outbox.pending").gauge().value()).isZero();
    }

    @Test
    void stopsCycleAtFirstFailureSoLaterEventsAreNotPublished() {
        PendingOutboxEvent e1 = store.add(1);
        PendingOutboxEvent e2 = store.add(2);
        PendingOutboxEvent e3 = store.add(3);
        publisher.failure = (topic, json) -> json.equals(e2.payload()) ? new RecordTooLargeException("grande") : null;

        relay.runCycle().block();

        assertThat(store.published).containsOnlyKeys(e1.id());
        assertThat(publisher.payloadsOn(TOPIC)).containsExactly(e1.payload(), e2.payload());
        assertThat(store.attempts(e2)).isEqualTo(1);
        assertThat(store.lastError.get(e2.id())).contains("RecordTooLargeException");
        assertThat(store.isPending(e3)).isTrue();
        assertThat(registry.get("triage.outbox.pending").gauge().value()).isEqualTo(2.0);
    }

    @Test
    void retriableFailureDoesNotCountAttemptsAndBacksOffExponentially() {
        PendingOutboxEvent e1 = store.add(1);
        publisher.failure = (topic, json) -> new NetworkException("broker caído");

        for (int i = 0; i < 10; i++) {
            relay.runCycle().block();
        }

        assertThat(store.attempts(e1)).isZero();
        assertThat(store.isPending(e1)).isTrue();
        assertThat(publisher.payloadsOn(DLT)).isEmpty();
        assertThat(publisher.payloadsOn(TOPIC)).hasSize(10);

        List<Duration> delays = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            delays.add(relay.nextDelay(OutboxRelay.Outcome.RETRIABLE_FAILURE));
        }
        assertThat(delays).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4),
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5));
        assertThat(relay.nextDelay(OutboxRelay.Outcome.PUBLISHED)).isEqualTo(Duration.ofSeconds(1));
        assertThat(relay.nextDelay(OutboxRelay.Outcome.RETRIABLE_FAILURE)).isEqualTo(Duration.ofSeconds(1));

        publisher.failure = (topic, json) -> null;
        relay.runCycle().block();
        assertThat(store.published).containsKey(e1.id());
    }

    @Test
    void wrappedRetriableFailuresDoNotCountAttemptsNorReachDlt() {
        PendingOutboxEvent e1 = store.add(1);
        var record = new org.apache.kafka.clients.producer.ProducerRecord<>("t", "k", "v");
        publisher.failure = (topic, json) -> new org.springframework.kafka.core.KafkaProducerException(record, "x",
                new org.apache.kafka.common.errors.TimeoutException());
        for (int i = 0; i < 5; i++) {
            relay.runCycle().block();
        }
        publisher.failure = (topic, json) -> new java.util.concurrent.CompletionException(
                new org.apache.kafka.common.errors.TimeoutException());
        for (int i = 0; i < 5; i++) {
            relay.runCycle().block();
        }

        assertThat(store.attempts(e1)).isZero();
        assertThat(store.isPending(e1)).isTrue();
        assertThat(publisher.payloadsOn(DLT)).isEmpty();
        assertThat(registry.get("triage.outbox.dead_letter").counter().count()).isZero();
    }

    @Test
    void nonRetriableFailureGoesToDltAfterMaxAttemptsAndRelayContinues() {
        PendingOutboxEvent poison = store.add(1);
        PendingOutboxEvent healthy = store.add(2);
        publisher.failure = (topic, json) ->
                topic.equals(TOPIC) && json.equals(poison.payload()) ? new RecordTooLargeException("x") : null;

        relay.runCycle().block();
        relay.runCycle().block();
        assertThat(store.attempts(poison)).isEqualTo(2);
        assertThat(publisher.payloadsOn(DLT)).isEmpty();
        assertThat(store.isPending(healthy)).isTrue();

        relay.runCycle().block();

        assertThat(publisher.payloadsOn(DLT)).containsExactly(poison.payload());
        assertThat(publisher.calls.stream().filter(c -> c.topic().equals(DLT)).findFirst().orElseThrow().key())
                .isEqualTo(poison.aggregateId().toString());
        assertThat(store.dead).containsOnlyKeys(poison.id());
        assertThat(store.dead.get(poison.id())).isEqualTo(NOW);
        assertThat(registry.get("triage.outbox.dead_letter").counter().count()).isEqualTo(1.0);
        // El mismo ciclo sigue con la siguiente fila.
        assertThat(store.published).containsOnlyKeys(healthy.id());
    }

    @Test
    void failedDltPublishLeavesRowPending() {
        PendingOutboxEvent poison = store.add(1);
        publisher.failure = (topic, json) -> new RecordTooLargeException("x");

        for (int i = 0; i < 3; i++) {
            relay.runCycle().block();
        }

        assertThat(store.attempts(poison)).isEqualTo(3);
        assertThat(store.dead).isEmpty();
        assertThat(store.isPending(poison)).isTrue();
        assertThat(registry.get("triage.outbox.dead_letter").counter().count()).isZero();

        // Al recuperarse el DLT (el topic principal sigue fallando) la fila termina en dead-letter.
        publisher.failure = (topic, json) -> topic.equals(TOPIC) ? new RecordTooLargeException("x") : null;
        relay.runCycle().block();
        assertThat(store.dead).containsOnlyKeys(poison.id());
    }
}
