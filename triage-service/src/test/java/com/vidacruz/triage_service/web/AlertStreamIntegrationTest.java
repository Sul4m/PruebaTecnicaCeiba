package com.vidacruz.triage_service.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.triage_service.PostgresTestSupport;
import com.vidacruz.triage_service.application.AuditRepositoryPort;
import com.vidacruz.triage_service.application.AlertRepositoryPort;
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
import java.util.ArrayList;
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
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Pacientes sintéticos de 2020 y reloj fijo (igual que AlertsIntegrationTest). */
@SpringBootTest
@AutoConfigureWebTestClient(timeout = "20000")
class AlertStreamIntegrationTest extends PostgresTestSupport {

    private static final Instant T0 = Instant.parse("2020-01-01T00:00:00Z");
    private static final String METRIC = "triage.alertas.sse.conexiones";
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE =
            new ParameterizedTypeReference<>() { };

    @Autowired DatabaseClient db;
    @Autowired WebTestClient client;
    @Autowired PatientRepositoryPort patients;
    @Autowired AuditRepositoryPort audits;
    @Autowired AlertRepositoryPort alerts;
    @Autowired OutboxPort outbox;
    @Autowired EscalationMetricsPort metrics;
    @Autowired TransactionalOperator tx;
    @Autowired ObjectMapper mapper;
    @Autowired MeterRegistry registry;

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
            UUID id = UUID.randomUUID();
            db.sql("""
                    INSERT INTO patient (id, full_name, age, chief_complaint, heart_rate, systolic, diastolic,
                                         spo2, temperature, pain, gcs, level, sla_minutes, status, escalated,
                                         registered_at, status_changed_at, aggregate_version)
                    VALUES (:id, 'Paciente Prueba', 40, 'Dolor', 70, 115, 75, 99, 36.4, 0, 15, 'CRITICO', 5,
                            'REGISTRADO', false, :at, :at, 1)
                    """)
                    .bind("id", id).bind("at", OffsetDateTime.ofInstant(T0, ZoneOffset.UTC))
                    .fetch().rowsUpdated().block();
        }
        new EscalateOverduePatientsUseCase(patients, audits, outbox, alerts, metrics, tx,
                Clock.fixed(T0.plus(1, ChronoUnit.HOURS), ZoneOffset.UTC), 100).escalateAll().block();
    }

    private long maxCursor() {
        return db.sql("SELECT last_cursor FROM medical_alert_cursor").map(r -> r.get("last_cursor", Long.class))
                .one().block();
    }

    private Flux<ServerSentEvent<String>> open(Long lastEventId) {
        WebTestClient.RequestHeadersSpec<?> req = client.get().uri("/triage/alertas/stream")
                .accept(MediaType.TEXT_EVENT_STREAM).header("X-API-Key", API_KEY);
        if (lastEventId != null) {
            req = req.header("Last-Event-ID", lastEventId.toString());
        }
        FluxExchangeResult<ServerSentEvent<String>> result = req.exchange().expectStatus().isOk()
                .returnResult(SSE);
        return result.getResponseBody();
    }

    private static Flux<ServerSentEvent<String>> alertsOnly(Flux<ServerSentEvent<String>> f) {
        return f.filter(e -> e.data() != null);
    }

    @Test
    void liveAlertHasIdEventAndExactlyTheFiveContractKeys() throws Exception {
        long base = maxCursor();
        Flux<ServerSentEvent<String>> flux = alertsOnly(open(null));
        StepVerifier.create(flux)
                .then(() -> escalate(1))
                .assertNext(e -> {
                    assertThat(e.id()).isEqualTo(Long.toString(base + 1));
                    assertThat(e.event()).isEqualTo("alerta");
                    JsonNode json = parse(e.data());
                    List<String> keys = new ArrayList<>();
                    json.fieldNames().forEachRemaining(keys::add);
                    assertThat(keys).containsExactlyInAnyOrder("alertId", "deliveryCursor", "patientId", "nivel",
                            "timestamp");
                    assertThat(json.get("deliveryCursor").asLong()).isEqualTo(base + 1);
                    assertThat(json.get("nivel").asText()).isEqualTo("CRITICO");
                })
                .thenCancel()
                .verify(Duration.ofSeconds(15));
    }

    private JsonNode parse(String data) {
        try {
            return mapper.readTree(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void withoutHeaderPreviousAlertsAreNotDelivered() {
        escalate(2);
        long base = maxCursor();
        StepVerifier.create(alertsOnly(open(null)))
                .then(() -> escalate(1))
                .assertNext(e -> assertThat(e.id()).isEqualTo(Long.toString(base + 1)))
                .thenCancel()
                .verify(Duration.ofSeconds(15));
    }

    @Test
    void reconnectWithLastEventIdDeliversMissedAlertsInOrderThenNewOnes() {
        long base = maxCursor();
        escalate(3);
        StepVerifier.create(alertsOnly(open(base)).map(ServerSentEvent::id))
                .expectNext(Long.toString(base + 1), Long.toString(base + 2), Long.toString(base + 3))
                .then(() -> escalate(1))
                .expectNext(Long.toString(base + 4))
                .thenCancel()
                .verify(Duration.ofSeconds(15));
    }

    @Test
    void disconnectedClientRecoversEverythingWithoutGaps() {
        long base = maxCursor();
        escalate(1);
        List<String> first = alertsOnly(open(base)).take(1).map(ServerSentEvent::id).collectList()
                .block(Duration.ofSeconds(15));
        assertThat(first).containsExactly(Long.toString(base + 1));

        escalate(3); // el cliente está desconectado

        List<String> second = alertsOnly(open(base + 1)).take(3).map(ServerSentEvent::id).collectList()
                .block(Duration.ofSeconds(15));
        assertThat(second).containsExactly(Long.toString(base + 2), Long.toString(base + 3),
                Long.toString(base + 4));
    }

    @Test
    void twoClientsReceiveTheSameAlert() {
        long base = maxCursor();
        double gauge0 = registry.get(METRIC).gauge().value();
        Flux<String> a = alertsOnly(open(null)).map(ServerSentEvent::id).take(1);
        Flux<String> b = alertsOnly(open(null)).map(ServerSentEvent::id).take(1);
        List<String> got = new ArrayList<>();
        Disposable da = a.subscribe(got::add);
        Disposable db2 = b.subscribe(got::add);
        try {
            Awaitility.await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5))
                    .until(() -> registry.get(METRIC).gauge().value() >= gauge0 + 2);
            escalate(1);
            Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> got.size() == 2);
            assertThat(got).containsOnly(Long.toString(base + 1));
        } finally {
            da.dispose();
            db2.dispose();
        }
    }

    @Test
    void heartbeatIsACommentWithoutId() {
        StepVerifier.create(open(null))
                .assertNext(e -> {
                    assertThat(e.comment()).isEqualTo("heartbeat");
                    assertThat(e.id()).isNull();
                    assertThat(e.data()).isNull();
                })
                .thenCancel()
                .verify(Duration.ofSeconds(15));
    }

    @Test
    void invalidLastEventIdReturns400WithField() {
        for (String v : new String[] {"abc", "-1", "+5", "99999999999999999999"}) {
            client.get().uri("/triage/alertas/stream").accept(MediaType.TEXT_EVENT_STREAM)
                    .header("X-API-Key", API_KEY).header("Last-Event-ID", v).exchange()
                    .expectStatus().isBadRequest()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody().jsonPath("$.campo").isEqualTo("Last-Event-ID");
        }
    }

    @Test
    void missingApiKeyReturns401WithEmptyBody() {
        client.get().uri("/triage/alertas/stream").accept(MediaType.TEXT_EVENT_STREAM).exchange()
                .expectStatus().isUnauthorized().expectBody().isEmpty();
    }

    @Test
    void staleCursorBeyondTheTableStillReceivesTheNextAlert() {
        escalate(1);
        long stale = maxCursor() + 1000;
        StepVerifier.create(alertsOnly(open(stale)).map(ServerSentEvent::id))
                .then(() -> escalate(1))
                .expectNext(Long.toString(maxCursor() + 1))
                .thenCancel()
                .verify(Duration.ofSeconds(15));
    }

    @Test
    void connectionsGaugeGoesUpAndDown() {
        double before = registry.get(METRIC).gauge().value();
        Disposable d = open(null).subscribe();
        Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> registry.get(METRIC).gauge().value() == before + 1);
        d.dispose();
        Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> registry.get(METRIC).gauge().value() == before);
    }
}
