package com.vidacruz.triage_service.infrastructure.persistence;

import com.vidacruz.triage_service.application.AlertRepositoryPort;
import com.vidacruz.triage_service.application.PatientRepositoryPort.EscalatedPatient;
import com.vidacruz.triage_service.domain.MedicalAlert;
import com.vidacruz.triage_service.domain.TriageLevel;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
class R2dbcAlertRepository implements AlertRepositoryPort {

    private static final String LOCK_COUNTER =
            "SELECT last_cursor FROM medical_alert_cursor WHERE id = 1 FOR UPDATE";

    private static final String ADVANCE_COUNTER =
            "UPDATE medical_alert_cursor SET last_cursor = :last WHERE id = 1";

    private static final String INSERT = """
            INSERT INTO medical_alert (alert_id, delivery_cursor, patient_id, level, created_at)
            VALUES (:alertId, :cursor, :patientId, :level, :createdAt)
            """;

    private static final String SELECT_AFTER = """
            SELECT alert_id, delivery_cursor, patient_id, level, created_at
            FROM medical_alert
            WHERE delivery_cursor > :cursor
            ORDER BY delivery_cursor
            LIMIT :limit
            """;

    private static final String SELECT_MAX = "SELECT COALESCE(MAX(delivery_cursor), 0) AS max_cursor FROM medical_alert";

    private final DatabaseClient client;

    R2dbcAlertRepository(DatabaseClient client) {
        this.client = client;
    }

    @Override
    public Flux<MedicalAlert> appendAll(List<EscalatedPatient> escalated, Instant createdAt) {
        if (escalated.isEmpty()) {
            return Flux.empty();
        }
        return Flux.defer(() -> client.sql(LOCK_COUNTER)
                .map((row, meta) -> row.get("last_cursor", Long.class)).one()
                .switchIfEmpty(Mono.error(new IllegalStateException("medical_alert_cursor sin fila")))
                .flatMapMany(last -> {
                    List<MedicalAlert> alerts = new ArrayList<>(escalated.size());
                    long cursor = last;
                    for (EscalatedPatient p : escalated) {
                        alerts.add(new MedicalAlert(UUID.randomUUID(), ++cursor, p.id(), p.level(), createdAt));
                    }
                    return client.sql(ADVANCE_COUNTER).bind("last", cursor).fetch().rowsUpdated()
                            .thenMany(Flux.fromIterable(alerts).concatMap(this::insert));
                }));
    }

    private Mono<MedicalAlert> insert(MedicalAlert a) {
        return client.sql(INSERT)
                .bind("alertId", a.alertId())
                .bind("cursor", a.deliveryCursor())
                .bind("patientId", a.patientId())
                .bind("level", a.level().name())
                .bind("createdAt", OffsetDateTime.ofInstant(a.createdAt(), ZoneOffset.UTC))
                .fetch().rowsUpdated()
                .thenReturn(a);
    }

    @Override
    public Flux<MedicalAlert> findAfter(long cursor, int limit) {
        return client.sql(SELECT_AFTER)
                .bind("cursor", cursor)
                .bind("limit", limit)
                .map((row, meta) -> new MedicalAlert(
                        row.get("alert_id", UUID.class),
                        row.get("delivery_cursor", Long.class),
                        row.get("patient_id", UUID.class),
                        TriageLevel.valueOf(row.get("level", String.class)),
                        row.get("created_at", OffsetDateTime.class).toInstant()))
                .all();
    }

    @Override
    public Mono<Long> findMaxCursor() {
        return client.sql(SELECT_MAX).map((row, meta) -> row.get("max_cursor", Long.class)).one();
    }
}
