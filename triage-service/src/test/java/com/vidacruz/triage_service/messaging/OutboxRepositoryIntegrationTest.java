package com.vidacruz.triage_service.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage_service.PostgresTestSupport;
import com.vidacruz.triage_service.application.OutboxPort;
import com.vidacruz.triage_service.application.OutboxRelayStore;
import com.vidacruz.triage_service.application.PendingOutboxEvent;
import com.vidacruz.triage_service.domain.PatientStatus;
import com.vidacruz.triage_service.domain.TriageEventEnvelope;
import com.vidacruz.triage_service.domain.TriageLevel;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;

/** Relay apagado (application.properties de test): se ejercita solo el repositorio. */
@SpringBootTest
class OutboxRepositoryIntegrationTest extends PostgresTestSupport {

    @Autowired OutboxPort outbox;
    @Autowired OutboxRelayStore store;
    @Autowired DatabaseClient db;

    private TriageEventEnvelope append() {
        UUID patientId = UUID.randomUUID();
        TriageEventEnvelope e = TriageEventEnvelope.of("paciente_registrado", patientId, 1, TriageLevel.CRITICO,
                PatientStatus.REGISTRADO, false, Instant.now().truncatedTo(ChronoUnit.MICROS));
        outbox.append(e).block();
        return e;
    }

    private boolean pendingContains(UUID eventId) {
        return store.findPending(100_000).map(PendingOutboxEvent::id).collectList().block().contains(eventId);
    }

    @Test
    void failureTrackingAndDeadLetterFilters() {
        long before = store.countPending().block();
        TriageEventEnvelope normal = append();
        TriageEventEnvelope poison = append();
        assertThat(store.countPending().block()).isEqualTo(before + 2);

        assertThat(store.recordFailure(poison.eventId(), "primero").block()).isEqualTo(1);
        assertThat(store.recordFailure(poison.eventId(), "segundo").block()).isEqualTo(2);
        String lastError = db.sql("SELECT last_error FROM outbox_event WHERE id = :id").bind("id", poison.eventId())
                .map(r -> r.get("last_error", String.class)).one().block();
        assertThat(lastError).isEqualTo("segundo");

        store.markDeadLettered(poison.eventId(), Instant.now().truncatedTo(ChronoUnit.MICROS)).block();

        assertThat(pendingContains(poison.eventId())).isFalse();
        assertThat(pendingContains(normal.eventId())).isTrue();
        assertThat(store.countPending().block()).isEqualTo(before + 1);

        store.markPublished(normal.eventId(), Instant.now().truncatedTo(ChronoUnit.MICROS)).block();
        assertThat(pendingContains(normal.eventId())).isFalse();
        assertThat(store.countPending().block()).isEqualTo(before);
    }
}
