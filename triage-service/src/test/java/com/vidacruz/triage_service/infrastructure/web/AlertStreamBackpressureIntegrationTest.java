package com.vidacruz.triage_service.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage_service.PostgresTestSupport;
import com.vidacruz.triage_service.application.AlertRepositoryPort;
import com.vidacruz.triage_service.application.AuditRepositoryPort;
import com.vidacruz.triage_service.application.EscalateOverduePatientsUseCase;
import com.vidacruz.triage_service.application.EscalationMetricsPort;
import com.vidacruz.triage_service.application.OutboxPort;
import com.vidacruz.triage_service.application.PatientRepositoryPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.test.StepVerifier;

/** Buffer de 2 y lotes de 2: el catch-up debe ser dirigido por demanda y el cliente que no pide se cancela. */
@SpringBootTest(properties = {"triage.alertas.stream.buffer-size=2", "triage.alertas.stream.batch-size=2"})
@AutoConfigureWebTestClient(timeout = "30000")
class AlertStreamBackpressureIntegrationTest extends PostgresTestSupport {

    private static final Instant T0 = Instant.parse("2020-01-01T00:00:00Z");
    private static final String METRIC = "triage.alertas.sse.conexiones";

    @Autowired DatabaseClient db;
    @Autowired WebTestClient client;
    @Autowired PatientRepositoryPort patients;
    @Autowired AuditRepositoryPort audits;
    @Autowired AlertRepositoryPort alerts;
    @Autowired OutboxPort outbox;
    @Autowired EscalationMetricsPort metrics;
    @Autowired TransactionalOperator tx;
    @Autowired MeterRegistry registry;
    @Autowired AlertStreamController controller;

    @BeforeEach
    @AfterEach
    void clean() {
        String old = "(SELECT id FROM patient WHERE registered_at < '2021-01-01')";
        db.sql("DELETE FROM medical_alert WHERE patient_id IN " + old).fetch().rowsUpdated().block();
        db.sql("DELETE FROM outbox_event WHERE aggregate_id IN " + old).fetch().rowsUpdated().block();
        db.sql("DELETE FROM audit_event WHERE patient_id IN " + old).fetch().rowsUpdated().block();
        db.sql("DELETE FROM patient WHERE registered_at < '2021-01-01'").fetch().rowsUpdated().block();
    }

    private void escalate(int count) {
        for (int i = 0; i < count; i++) {
            db.sql("""
                    INSERT INTO patient (id, full_name, age, chief_complaint, heart_rate, systolic, diastolic,
                                         spo2, temperature, pain, gcs, level, sla_minutes, status, escalated,
                                         registered_at, status_changed_at, aggregate_version)
                    VALUES (:id, 'Paciente Prueba', 40, 'Dolor', 70, 115, 75, 99, 36.4, 0, 15, 'CRITICO', 5,
                            'REGISTRADO', false, :at, :at, 1)
                    """)
                    .bind("id", UUID.randomUUID()).bind("at", OffsetDateTime.ofInstant(T0, ZoneOffset.UTC))
                    .fetch().rowsUpdated().block();
        }
        new EscalateOverduePatientsUseCase(patients, audits, outbox, alerts, metrics, tx,
                Clock.fixed(T0.plus(1, ChronoUnit.HOURS), ZoneOffset.UTC), 100).escalateAll().block();
    }

    private long lastCursor() {
        return db.sql("SELECT last_cursor FROM medical_alert_cursor").map(r -> r.get("last_cursor", Long.class))
                .one().block();
    }

    @Test
    void backlogMuchLargerThanTheBufferIsDeliveredInOrderWithoutCancellation() {
        long base = lastCursor();
        escalate(20);

        List<String> ids = client.get().uri("/triage/alertas/stream").accept(MediaType.TEXT_EVENT_STREAM)
                .header("X-API-Key", API_KEY).header("Last-Event-ID", Long.toString(base)).exchange()
                .expectStatus().isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() { })
                .getResponseBody()
                .filter(e -> e.data() != null)
                .take(20) // sin error ni cierre antes de recibir las 20; take cancela la conexión del cliente
                .map(ServerSentEvent::id).collectList().block(Duration.ofSeconds(25));

        assertThat(ids).hasSize(20);
        for (int i = 0; i < 20; i++) {
            assertThat(ids.get(i)).isEqualTo(Long.toString(base + 1 + i));
        }
    }

    @Test
    void clientThatNeverRequestsIsCancelledAndTheStreamCompletesWithoutError() {
        double baseline = registry.get(METRIC).gauge().value();
        escalate(5);

        StepVerifier.create(controller.stream("0"), 0)
                .expectSubscription()
                .expectComplete()
                .verify(Duration.ofSeconds(15));

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> registry.get(METRIC).gauge().value() == baseline);
    }
}
