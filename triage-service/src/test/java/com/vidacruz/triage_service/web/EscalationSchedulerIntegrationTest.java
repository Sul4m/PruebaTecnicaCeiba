package com.vidacruz.triage_service.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.vidacruz.triage_service.PostgresTestSupport;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.annotation.DirtiesContext;

/** Scheduler real con intervalo corto. El contexto se descarta para no dejar el bucle vivo en otros tests. */
@SpringBootTest(properties = {"triage.escalation.enabled=true", "triage.escalation.interval=200ms"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EscalationSchedulerIntegrationTest extends PostgresTestSupport {

    @Autowired DatabaseClient db;

    private final UUID id = UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        db.sql("DELETE FROM outbox_event WHERE aggregate_id = :id").bind("id", id).fetch().rowsUpdated().block();
        db.sql("DELETE FROM audit_event WHERE patient_id = :id").bind("id", id).fetch().rowsUpdated().block();
        db.sql("DELETE FROM patient WHERE id = :id").bind("id", id).fetch().rowsUpdated().block();
    }

    @Test
    void schedulerEscalatesOverduePatientOnItsOwn() {
        OffsetDateTime at = OffsetDateTime.of(2019, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        db.sql("""
                INSERT INTO patient (id, full_name, age, chief_complaint, heart_rate, systolic, diastolic, spo2,
                                     temperature, pain, gcs, level, sla_minutes, status, escalated,
                                     registered_at, status_changed_at, aggregate_version)
                VALUES (:id, 'Paciente Prueba', 40, 'Dolor', 70, 115, 75, 99, 36.4, 0, 15, 'CRITICO', 5,
                        'REGISTRADO', false, :at, :at, 1)
                """).bind("id", id).bind("at", at).fetch().rowsUpdated().block();

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            boolean escalated = db.sql("SELECT escalated FROM patient WHERE id = :id").bind("id", id)
                    .map(r -> r.get("escalated", Boolean.class)).one().block();
            assertThat(escalated).isTrue();
        });
        long audits = db.sql("SELECT count(*) AS c FROM audit_event WHERE patient_id = :id AND event_type = 'paciente_escalado'")
                .bind("id", id).map(r -> r.get("c", Long.class)).one().block();
        assertThat(audits).isEqualTo(1L);
    }
}
