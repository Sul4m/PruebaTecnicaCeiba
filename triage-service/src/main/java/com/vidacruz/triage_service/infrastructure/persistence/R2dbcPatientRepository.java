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
import reactor.core.publisher.Mono;

@Repository
class R2dbcPatientRepository implements PatientRepositoryPort {

    private static final String INSERT = """
            INSERT INTO patient (id, full_name, age, chief_complaint, heart_rate, systolic, diastolic,
                                 spo2, temperature, pain, gcs, level, sla_minutes, status, escalated,
                                 registered_at, status_changed_at)
            VALUES (:id, :fullName, :age, :complaint, :hr, :sys, :dia, :spo2, :temp, :pain, :gcs,
                    :level, :sla, :status, :escalated, :registeredAt, :statusChangedAt)
            """;

    // Los SMALLINT se castean a INT para mapearlos a Integer sin depender del codec int2.
    private static final String SELECT_BY_ID = """
            SELECT id, full_name, age::int AS age, chief_complaint, heart_rate::int AS heart_rate,
                   systolic::int AS systolic, diastolic::int AS diastolic, spo2::int AS spo2, temperature,
                   pain::int AS pain, gcs::int AS gcs, level, status, escalated, registered_at, status_changed_at
            FROM patient
            WHERE id = :id
            """;

    private static final String UPDATE_STATUS = """
            UPDATE patient SET status = :next, status_changed_at = :changedAt
            WHERE id = :id AND status = :expected
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
                .fetch()
                .rowsUpdated()
                .thenReturn(p);
    }

    @Override
    public Mono<Boolean> updateStatus(UUID id, PatientStatus expected, PatientStatus next, Instant changedAt) {
        return client.sql(UPDATE_STATUS)
                .bind("id", id)
                .bind("expected", expected.name())
                .bind("next", next.name())
                .bind("changedAt", OffsetDateTime.ofInstant(changedAt, ZoneOffset.UTC))
                .fetch()
                .rowsUpdated()
                .map(rows -> rows > 0);
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
                        PatientStatus.valueOf(row.get("status", String.class)),
                        Boolean.TRUE.equals(row.get("escalated", Boolean.class)),
                        row.get("registered_at", OffsetDateTime.class).toInstant(),
                        row.get("status_changed_at", OffsetDateTime.class).toInstant()))
                .one();
    }
}
