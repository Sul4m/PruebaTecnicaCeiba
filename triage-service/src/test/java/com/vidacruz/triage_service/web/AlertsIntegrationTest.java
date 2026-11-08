package com.vidacruz.triage_service.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.triage_service.PostgresTestSupport;
import com.vidacruz.triage_service.application.AlertRepositoryPort;
import com.vidacruz.triage_service.application.AuditRepositoryPort;
import com.vidacruz.triage_service.application.EscalateOverduePatientsUseCase;
import com.vidacruz.triage_service.application.EscalationMetricsPort;
import com.vidacruz.triage_service.application.OutboxPort;
import com.vidacruz.triage_service.application.PatientRepositoryPort;
import com.vidacruz.triage_service.domain.PatientStatus;
import com.vidacruz.triage_service.domain.TriageLevel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Pacientes sintéticos de 2020 y reloj fijo; el contador es global, así que todo se mide contra una base. */
@SpringBootTest
@AutoConfigureWebTestClient
class AlertsIntegrationTest extends PostgresTestSupport {

    private static final Instant T0 = Instant.parse("2020-01-01T00:00:00Z");

    @Autowired DatabaseClient db;
    @Autowired WebTestClient client;
    @Autowired PatientRepositoryPort patients;
    @Autowired OutboxPort outbox;
    @Autowired EscalationMetricsPort metrics;
    @Autowired TransactionalOperator tx;
    @Autowired ObjectMapper mapper;
    @MockitoSpyBean AuditRepositoryPort audits;
    @MockitoSpyBean AlertRepositoryPort alerts;

    @BeforeEach
    @AfterEach
    void clean() {
        reset(alerts, audits);
        String old = "(SELECT id FROM patient WHERE registered_at < '2021-01-01')";
        db.sql("DELETE FROM medical_alert WHERE patient_id IN " + old).fetch().rowsUpdated().block();
        db.sql("DELETE FROM outbox_event WHERE aggregate_id IN " + old).fetch().rowsUpdated().block();
        db.sql("DELETE FROM audit_event WHERE patient_id IN " + old).fetch().rowsUpdated().block();
        db.sql("DELETE FROM patient WHERE registered_at < '2021-01-01'").fetch().rowsUpdated().block();
    }

    private EscalateOverduePatientsUseCase useCase(int batchSize) {
        return new EscalateOverduePatientsUseCase(patients, audits, outbox, alerts, metrics, tx,
                Clock.fixed(T0.plus(1, ChronoUnit.HOURS), ZoneOffset.UTC), batchSize);
    }

    private UUID insert(TriageLevel level, String status, boolean escalated) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO patient (id, full_name, age, chief_complaint, heart_rate, systolic, diastolic, spo2,
                                     temperature, pain, gcs, level, sla_minutes, status, escalated,
                                     registered_at, status_changed_at, aggregate_version)
                VALUES (:id, 'Paciente Prueba', 40, 'Dolor', 70, 115, 75, 99, 36.4, 0, 15, :level, :sla,
                        :status, :escalated, :at, :at, 1)
                """)
                .bind("id", id).bind("level", level.name()).bind("sla", level.slaMinutes())
                .bind("status", status).bind("escalated", escalated)
                .bind("at", OffsetDateTime.ofInstant(T0, ZoneOffset.UTC))
                .fetch().rowsUpdated().block();
        return id;
    }

    private UUID insert(TriageLevel level) {
        return insert(level, "REGISTRADO", false);
    }

    private long lastCursor() {
        return db.sql("SELECT last_cursor FROM medical_alert_cursor").map(r -> r.get("last_cursor", Long.class))
                .one().block();
    }

    private long alertCount(UUID patientId) {
        return db.sql("SELECT count(*) AS c FROM medical_alert WHERE patient_id = :id").bind("id", patientId)
                .map(r -> r.get("c", Long.class)).one().block();
    }

    private long cursorOf(UUID patientId) {
        return db.sql("SELECT delivery_cursor FROM medical_alert WHERE patient_id = :id").bind("id", patientId)
                .map(r -> r.get("delivery_cursor", Long.class)).one().block();
    }

    private JsonNode get(String query) throws Exception {
        byte[] body = client.get().uri("/triage/alertas" + query).header("X-API-Key", API_KEY).exchange()
                .expectStatus().isOk().expectBody().returnResult().getResponseBody();
        return mapper.readTree(body);
    }

    @Test
    void escalationCreatesOneAlertPerPatientWithConsecutiveCursorsInABatch() {
        long base = lastCursor();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(insert(TriageLevel.CRITICO));
        }

        assertThat(useCase(100).escalateAll().block()).isEqualTo(5L);

        Set<Long> cursors = new HashSet<>();
        ids.forEach(id -> {
            assertThat(alertCount(id)).isEqualTo(1L);
            cursors.add(cursorOf(id));
        });
        assertThat(cursors).containsExactlyInAnyOrder(base + 1, base + 2, base + 3, base + 4, base + 5);
        assertThat(lastCursor()).isEqualTo(base + 5);
    }

    @Test
    void secondRunDoesNotCreateAnotherAlert() {
        UUID id = insert(TriageLevel.CRITICO);
        useCase(100).escalateAll().block();
        long after = lastCursor();

        assertThat(useCase(100).escalateAll().block()).isZero();

        assertThat(alertCount(id)).isEqualTo(1L);
        assertThat(lastCursor()).isEqualTo(after);
    }

    @Test
    void nonEscalatablePatientsGetNoAlert() {
        UUID consult = insert(TriageLevel.CRITICO, "EN_CONSULTA", false);
        useCase(100).escalateAll().block();
        assertThat(alertCount(consult)).isZero();
    }

    @Test
    void concurrentCyclesProduceUniqueGaplessIncreasingCursors() {
        long base = lastCursor();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ids.add(insert(TriageLevel.URGENTE));
        }

        Long total = Mono.zip(useCase(7).escalateAll().subscribeOn(Schedulers.parallel()),
                        useCase(7).escalateAll().subscribeOn(Schedulers.parallel()))
                .map(t -> t.getT1() + t.getT2()).block(Duration.ofSeconds(60));

        assertThat(total).isEqualTo(40L);
        List<Long> cursors = ids.stream().map(this::cursorOf).sorted().toList();
        assertThat(cursors).doesNotHaveDuplicates();
        for (int i = 0; i < cursors.size(); i++) {
            assertThat(cursors.get(i)).isEqualTo(base + 1 + i);
        }
        assertThat(lastCursor()).isEqualTo(base + 40);
    }

    @Test
    void alertFailureRollsBackEverythingAndCounterDoesNotAdvance() {
        long base = lastCursor();
        UUID id = insert(TriageLevel.CRITICO);
        // Ejecuta el lock, el UPDATE del contador y el INSERT reales y luego falla: todo debe revertirse.
        doAnswer(inv -> ((Flux<?>) inv.callRealMethod()).thenMany(Flux.error(new IllegalStateException("alert down"))))
                .when(alerts).appendAll(any(), any());

        assertThatThrownBy(() -> useCase(100).escalateAll().block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("alert down");

        assertThat(db.sql("SELECT escalated FROM patient WHERE id = :id").bind("id", id)
                .map(r -> r.get("escalated", Boolean.class)).one().block()).isFalse();
        assertThat(db.sql("SELECT count(*) AS c FROM audit_event WHERE patient_id = :id").bind("id", id)
                .map(r -> r.get("c", Long.class)).one().block()).isZero();
        assertThat(db.sql("SELECT count(*) AS c FROM outbox_event "
                + "WHERE aggregate_id = :id AND event_type = 'paciente_escalado'")
                .bind("id", id).map(r -> r.get("c", Long.class)).one().block()).isZero();
        assertThat(alertCount(id)).isZero();
        assertThat(lastCursor()).isEqualTo(base);
    }

    @Test
    void queryPaginatesAscendingAndExposesOnlyTheFiveContractKeys() throws Exception {
        long base = lastCursor();
        for (int i = 0; i < 4; i++) {
            insert(TriageLevel.CRITICO);
        }
        useCase(100).escalateAll().block();

        JsonNode page = get("?despuesDe=" + (base + 1) + "&limite=2");
        assertThat(page.isArray()).isTrue();
        assertThat(page).hasSize(2);
        assertThat(page.get(0).get("deliveryCursor").asLong()).isEqualTo(base + 2);
        assertThat(page.get(1).get("deliveryCursor").asLong()).isEqualTo(base + 3);
        List<String> keys = new ArrayList<>();
        page.get(0).fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("alertId", "deliveryCursor", "patientId", "nivel", "timestamp");
        assertThat(page.get(0).get("nivel").asText()).isEqualTo("CRITICO");
        assertThat(Instant.parse(page.get(0).get("timestamp").asText()))
                .isEqualTo(T0.plus(1, ChronoUnit.HOURS));

        assertThat(get("?despuesDe=" + (base + 4))).isEmpty();
        assertThat(get("?despuesDe=" + (base + 100))).isEmpty();
    }

    @Test
    void invalidParametersReturn400WithFieldAndSkipTheDatabase() {
        for (String[] c : new String[][] {{"despuesDe=-1", "despuesDe"}, {"limite=0", "limite"},
                {"limite=201", "limite"}, {"limite=-5", "limite"}, {"limite=abc", "limite"}, {"despuesDe=abc", "despuesDe"}}) {
            client.get().uri("/triage/alertas?" + c[0]).header("X-API-Key", API_KEY).exchange()
                    .expectStatus().isBadRequest().expectBody().jsonPath("$.campo").isEqualTo(c[1]);
        }
        verify(alerts, never()).findAfter(anyLong(), anyInt());
    }

    @Test
    void missingApiKeyReturns401WithEmptyBody() {
        client.get().uri("/triage/alertas").exchange().expectStatus().isUnauthorized().expectBody().isEmpty();
    }

    @Test
    void noParametersUseDefaults() throws Exception {
        get("");
        verify(alerts).findAfter(0L, 50);
    }

    @Test
    void upperBoundLimitIsAccepted() throws Exception {
        get("?limite=200");
        verify(alerts).findAfter(0L, 200);
    }

    @Test
    void missingCounterRowFailsAndRollsBack() {
        var patient = new PatientRepositoryPort.EscalatedPatient(UUID.randomUUID(), TriageLevel.CRITICO,
                PatientStatus.REGISTRADO, 2L);
        Flux<?> flow = db.sql("DELETE FROM medical_alert_cursor").fetch().rowsUpdated()
                .thenMany(alerts.appendAll(List.of(patient), T0));

        assertThatThrownBy(() -> flow.as(tx::transactional).blockLast())
                .hasMessageContaining("medical_alert_cursor sin fila");

        assertThat(db.sql("SELECT count(*) AS c FROM medical_alert_cursor")
                .map(r -> r.get("c", Long.class)).one().block()).isEqualTo(1L);
    }

    @Test
    void thereAreNoWriteRoutes() {
        for (String path : new String[] {"/triage/alertas", "/triage/alertas/" + UUID.randomUUID()}) {
            int expected = path.equals("/triage/alertas") ? 405 : 404;
            for (var method : List.of(HttpMethod.DELETE, HttpMethod.PUT, HttpMethod.POST, HttpMethod.PATCH)) {
                client.method(method).uri(path).header("X-API-Key", API_KEY).exchange()
                        .expectStatus().isEqualTo(expected);
            }
        }
    }
}
