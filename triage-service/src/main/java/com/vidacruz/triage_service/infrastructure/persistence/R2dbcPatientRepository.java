package com.vidacruz.triage_service.infrastructure.persistence;

import com.vidacruz.triage_service.application.PatientRepositoryPort;
import com.vidacruz.triage_service.domain.Patient;
import com.vidacruz.triage_service.domain.PatientStatus;
import com.vidacruz.triage_service.domain.TriageLevel;
import com.vidacruz.triage_service.domain.VitalSigns;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
class R2dbcPatientRepository implements PatientRepositoryPort {

    private static final String INSERT = """
            INSERT INTO patient (id, full_name, age, chief_complaint, heart_rate, systolic, diastolic,
                                 spo2, temperature, pain, gcs, level, sla_minutes, status, escalated,
                                 registered_at, status_changed_at, aggregate_version)
            VALUES (:id, :fullName, :age, :complaint, :hr, :sys, :dia, :spo2, :temp, :pain, :gcs,
                    :level, :sla, :status, :escalated, :registeredAt, :statusChangedAt, :version)
            """;

    // Los SMALLINT se castean a INT para mapearlos a Integer sin depender del codec int2.
    private static final String SELECT_BY_ID = """
            SELECT id, full_name, age::int AS age, chief_complaint, heart_rate::int AS heart_rate,
                   systolic::int AS systolic, diastolic::int AS diastolic, spo2::int AS spo2, temperature,
                   pain::int AS pain, gcs::int AS gcs, level, sla_minutes::int AS sla_minutes, status, escalated, registered_at,
                   status_changed_at, aggregate_version
            FROM patient
            WHERE id = :id
            """;

    private static final String UPDATE_STATUS = """
            UPDATE patient SET status = :next, status_changed_at = :changedAt,
                               aggregate_version = aggregate_version + 1
            WHERE id = :id AND status = :expected
            RETURNING aggregate_version, escalated
            """;

    private static final String ESCALATE_OVERDUE = """
            WITH due AS (
                SELECT id FROM patient
                WHERE status = 'REGISTRADO' AND escalated = false
                  AND registered_at + make_interval(mins => sla_minutes) < :now
                ORDER BY registered_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            UPDATE patient p SET escalated = true, aggregate_version = p.aggregate_version + 1
            FROM due WHERE p.id = due.id
            RETURNING p.id, p.level, p.status, p.aggregate_version
            """;

    private final DatabaseClient client;

    R2dbcPatientRepository(DatabaseClient client) {
        this.client = client;
    }

    @Override
    public Mono<Patient> save(Patient p) {
        VitalSigns v = p.vitalSigns();
        return client.sql(INSERT)
                .bind("id", p.id())
                .bind("fullName", p.fullName())
                .bind("age", p.age())
                .bind("complaint", p.chiefComplaint())
                .bind("hr", v.heartRate())
                .bind("sys", v.systolic())
                .bind("dia", v.diastolic())
                .bind("spo2", v.spo2())
                .bind("temp", v.temperature())
                .bind("pain", v.pain())
                .bind("gcs", v.gcs())
                .bind("level", p.level().name())
                .bind("sla", p.slaMinutes())
                .bind("status", p.status().name())
                .bind("escalated", p.escalated())
                .bind("registeredAt", OffsetDateTime.ofInstant(p.registeredAt(), ZoneOffset.UTC))
                .bind("statusChangedAt", OffsetDateTime.ofInstant(p.statusChangedAt(), ZoneOffset.UTC))
                .bind("version", p.aggregateVersion())
                .fetch()
                .rowsUpdated()
                .thenReturn(p);
    }

    @Override
    public Mono<StatusUpdate> updateStatus(UUID id, PatientStatus expected, PatientStatus next, Instant changedAt) {
        return client.sql(UPDATE_STATUS)
                .bind("id", id)
                .bind("expected", expected.name())
                .bind("next", next.name())
                .bind("changedAt", OffsetDateTime.ofInstant(changedAt, ZoneOffset.UTC))
                .map((row, meta) -> new StatusUpdate(
                        row.get("aggregate_version", Long.class),
                        Boolean.TRUE.equals(row.get("escalated", Boolean.class))))
                .one();
    }

    @Override
    public Flux<EscalatedPatient> escalateOverdue(Instant now, int limit) {
        return client.sql(ESCALATE_OVERDUE)
                .bind("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .bind("limit", limit)
                .map((row, meta) -> new EscalatedPatient(
                        row.get("id", UUID.class),
                        TriageLevel.valueOf(row.get("level", String.class)),
                        PatientStatus.valueOf(row.get("status", String.class)),
                        row.get("aggregate_version", Long.class)))
                .all();
    }

    @Override
    public Mono<Patient> findById(UUID id) {
        return client.sql(SELECT_BY_ID)
                .bind("id", id)
                .map((row, meta) -> new Patient(
                        row.get("id", UUID.class),
                        row.get("full_name", String.class),
                        row.get("age", Integer.class),
                        row.get("chief_complaint", String.class),
                        new VitalSigns(
                                row.get("heart_rate", Integer.class),
                                row.get("systolic", Integer.class),
                                row.get("diastolic", Integer.class),
                                row.get("spo2", Integer.class),
                                row.get("temperature", BigDecimal.class),
                                row.get("pain", Integer.class),
                                row.get("gcs", Integer.class)),
                        TriageLevel.valueOf(row.get("level", String.class)),
                        row.get("sla_minutes", Integer.class),
                        PatientStatus.valueOf(row.get("status", String.class)),
                        Boolean.TRUE.equals(row.get("escalated", Boolean.class)),
                        row.get("registered_at", OffsetDateTime.class).toInstant(),
                        row.get("status_changed_at", OffsetDateTime.class).toInstant(),
                        row.get("aggregate_version", Long.class)))
                .one();
    }
}
