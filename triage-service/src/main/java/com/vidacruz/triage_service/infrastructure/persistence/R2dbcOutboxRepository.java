package com.vidacruz.triage_service.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.triage_service.application.OutboxPort;
import com.vidacruz.triage_service.application.OutboxRelayStore;
import com.vidacruz.triage_service.application.PendingOutboxEvent;
import com.vidacruz.triage_service.domain.TriageEventEnvelope;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
class R2dbcOutboxRepository implements OutboxPort, OutboxRelayStore {

    private static final String INSERT = """
            INSERT INTO outbox_event (id, aggregate_id, aggregate_version, event_type, payload, created_at)
            VALUES (:id, :aggregateId, :version, :type, :payload, :createdAt)
            """;

    private static final String SELECT_PENDING = """
            SELECT id, seq, aggregate_id, aggregate_version, event_type, payload, attempts
            FROM outbox_event
            WHERE published_at IS NULL AND dead_lettered_at IS NULL
            ORDER BY seq
            LIMIT :limit
            """;

    private static final String MARK_PUBLISHED = "UPDATE outbox_event SET published_at = :at WHERE id = :id";

    private static final String MARK_DEAD_LETTERED =
            "UPDATE outbox_event SET dead_lettered_at = :at WHERE id = :id";

    private static final String RECORD_FAILURE = """
            UPDATE outbox_event SET attempts = attempts + 1, last_error = :error
            WHERE id = :id
            RETURNING attempts
            """;

    private static final String COUNT_PENDING = """
            SELECT count(*) AS c FROM outbox_event
            WHERE published_at IS NULL AND dead_lettered_at IS NULL
            """;

    private final DatabaseClient client;
    private final ObjectMapper mapper;

    R2dbcOutboxRepository(DatabaseClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> append(TriageEventEnvelope e) {
        return Mono.fromCallable(() -> serialize(e))
                .flatMap(json -> client.sql(INSERT)
                        .bind("id", e.eventId())
                        .bind("aggregateId", e.aggregateId())
                        .bind("version", e.aggregateVersion())
                        .bind("type", e.eventType())
                        .bind("payload", json)
                        .bind("createdAt", OffsetDateTime.ofInstant(e.occurredAt(), ZoneOffset.UTC))
                        .fetch().rowsUpdated())
                .then();
    }

    private String serialize(TriageEventEnvelope e) {
        try {
            return mapper.writeValueAsString(e);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo serializar el evento " + e.eventType(), ex);
        }
    }

    @Override
    public Flux<PendingOutboxEvent> findPending(int limit) {
        return client.sql(SELECT_PENDING)
                .bind("limit", limit)
                .map((row, meta) -> new PendingOutboxEvent(
                        row.get("id", UUID.class),
                        row.get("seq", Long.class),
                        row.get("aggregate_id", UUID.class),
                        row.get("aggregate_version", Long.class),
                        row.get("event_type", String.class),
                        row.get("payload", String.class),
                        row.get("attempts", Integer.class)))
                .all();
    }

    @Override
    public Mono<Void> markPublished(UUID id, Instant at) {
        return client.sql(MARK_PUBLISHED).bind("id", id)
                .bind("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).fetch().rowsUpdated().then();
    }

    @Override
    public Mono<Integer> recordFailure(UUID id, String error) {
        return client.sql(RECORD_FAILURE).bind("id", id).bind("error", error)
                .map((row, meta) -> row.get("attempts", Integer.class)).one();
    }

    @Override
    public Mono<Void> markDeadLettered(UUID id, Instant at) {
        return client.sql(MARK_DEAD_LETTERED).bind("id", id)
                .bind("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).fetch().rowsUpdated().then();
    }

    @Override
    public Mono<Long> countPending() {
        return client.sql(COUNT_PENDING).map((row, meta) -> row.get("c", Long.class)).one();
    }
}
