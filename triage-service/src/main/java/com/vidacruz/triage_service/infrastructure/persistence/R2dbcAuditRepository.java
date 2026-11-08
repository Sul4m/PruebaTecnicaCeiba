package com.vidacruz.triage_service.infrastructure.persistence;

import com.vidacruz.triage_service.application.AuditRepositoryPort;
import com.vidacruz.triage_service.domain.AuditEvent;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
class R2dbcAuditRepository implements AuditRepositoryPort {

    private static final String INSERT = """
            INSERT INTO audit_event (id, patient_id, previous_status, new_status, event_type, occurred_at)
            VALUES (:id, :patientId, :previous, :next, :type, :occurredAt)
            """;

    private final DatabaseClient client;

    R2dbcAuditRepository(DatabaseClient client) {
        this.client = client;
    }

    @Override
    public Mono<AuditEvent> save(AuditEvent e) {
        DatabaseClient.GenericExecuteSpec spec = client.sql(INSERT)
                .bind("id", e.id())
                .bind("patientId", e.patientId())
                .bind("next", e.newStatus().name())
                .bind("type", e.eventType())
                .bind("occurredAt", OffsetDateTime.ofInstant(e.occurredAt(), ZoneOffset.UTC));
        spec = e.previousStatus() == null
                ? spec.bindNull("previous", String.class)
                : spec.bind("previous", e.previousStatus().name());
        return spec.fetch().rowsUpdated().thenReturn(e);
    }
}
