package com.vidacruz.triage_service.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.triage_service.PostgresTestSupport;
import com.vidacruz.triage_service.application.AuditRepositoryPort;
import com.vidacruz.triage_service.application.EscalateOverduePatientsUseCase;
import com.vidacruz.triage_service.application.EscalationMetricsPort;
import com.vidacruz.triage_service.application.OutboxPort;
import com.vidacruz.triage_service.application.PatientRepositoryPort;
import com.vidacruz.triage_service.domain.TriageLevel;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Pacientes sintéticos registrados en 2020 y reloj fijo: no interfieren con los de otros tests. */
@SpringBootTest
@AutoConfigureWebTestClient
class EscalationIntegrationTest extends PostgresTestSupport {

    private static final Instant T0 = Instant.parse("2020-01-01T00:00:00Z");

    @Autowired DatabaseClient db;
    @Autowired WebTestClient client;
    @Autowired PatientRepositoryPort patients;
    @Autowired OutboxPort outbox;
    @Autowired EscalationMetricsPort metrics;
    @Autowired TransactionalOperator tx;
    @Autowired MeterRegistry registry;
    @Autowired ObjectMapper mapper;
    @MockitoSpyBean AuditRepositoryPort audits;

    @BeforeEach
    @AfterEach
    void clean() {
        reset(audits);
        String old = "(SELECT id FROM patient WHERE registered_at < '2021-01-01')";
        db.sql("DELETE FROM outbox_event WHERE aggregate_id IN " + old).fetch().rowsUpdated().block();
        db.sql("DELETE FROM audit_event WHERE patient_id IN " + old).fetch().rowsUpdated().block();
        db.sql("DELETE FROM patient WHERE registered_at < '2021-01-01'").fetch().rowsUpdated().block();
    }

    private EscalateOverduePatientsUseCase useCase(Instant now, int batchSize) {
        return new EscalateOverduePatientsUseCase(patients, audits, outbox, metrics, tx,
                Clock.fixed(now, ZoneOffset.UTC), batchSize);
    }

    private UUID insert(TriageLevel level, int sla, String status, boolean escalated, Instant registeredAt) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO patient (id, full_name, age, chief_complaint, heart_rate, systolic, diastolic, spo2,
                                     temperature, pain, gcs, level, sla_minutes, status, escalated,
                                     registered_at, status_changed_at, aggregate_version)
                VALUES (:id, 'Paciente Prueba', 40, 'Dolor', 70, 115, 75, 99, 36.4, 0, 15, :level, :sla,
                        :status, :escalated, :at, :at, 1)
                """)
                .bind("id", id).bind("level", level.name()).bind("sla", sla).bind("status", status)
                .bind("escalated", escalated).bind("at", OffsetDateTime.ofInstant(registeredAt, ZoneOffset.UTC))
                .fetch().rowsUpdated().block();
        return id;
    }

    private UUID insert(TriageLevel level) {
        return insert(level, level.slaMinutes(), "REGISTRADO", false, T0);
    }

    private boolean escalated(UUID id) {
        return db.sql("SELECT escalated FROM patient WHERE id = :id").bind("id", id)
                .map(r -> r.get("escalated", Boolean.class)).one().block();
    }

    private long version(UUID id) {
        return db.sql("SELECT aggregate_version FROM patient WHERE id = :id").bind("id", id)
                .map(r -> r.get("aggregate_version", Long.class)).one().block();
    }

    private long audits(UUID id) {
        return db.sql("SELECT count(*) AS c FROM audit_event WHERE patient_id = :id AND event_type = 'paciente_escalado'")
                .bind("id", id).map(r -> r.get("c", Long.class)).one().block();
    }

    private List<String> outboxPayloads(UUID id) {
        return new ArrayList<>(db.sql("SELECT payload FROM outbox_event WHERE aggregate_id = :id AND event_type = 'paciente_escalado'")
                .bind("id", id).map(r -> r.get("payload", String.class)).all().collectList().block());
    }

    @Test
    void boundaryPerLevelExactDoesNotEscalatePlusOneMicroDoes() {
        for (TriageLevel level : TriageLevel.values()) {
            UUID id = insert(level);
            Instant exact = T0.plus(level.slaMinutes(), ChronoUnit.MINUTES);

            assertThat(useCase(exact, 100).escalateAll().block()).isZero();
            assertThat(escalated(id)).as("exacto %s", level).isFalse();
            assertThat(version(id)).isEqualTo(1L);

            assertThat(useCase(exact.plus(1, ChronoUnit.MICROS), 100).escalateAll().block()).isEqualTo(1L);
            assertThat(escalated(id)).as("+1us %s", level).isTrue();
        }
    }

    @Test
    void escalationAuditsPublishesAndCountsWithoutTouchingStatus() throws Exception {
        UUID id = insert(TriageLevel.CRITICO);
        Instant now = T0.plus(6, ChronoUnit.MINUTES);
        double before = registry.counter("triage.escalamientos", "nivel", "CRITICO").count();

        assertThat(useCase(now, 100).escalateAll().block()).isEqualTo(1L);

        assertThat(escalated(id)).isTrue();
        assertThat(version(id)).isEqualTo(2L);
        assertThat(audits(id)).isEqualTo(1L);
        var row = db.sql("SELECT previous_status, new_status, occurred_at FROM audit_event WHERE patient_id = :id")
                .bind("id", id).map(r -> r.get("previous_status", String.class) + "/" + r.get("new_status", String.class)
                        + "/" + r.get("occurred_at", OffsetDateTime.class).toInstant()).one().block();
        assertThat(row).isEqualTo("REGISTRADO/REGISTRADO/" + now);
        var patientRow = db.sql("SELECT status, level, status_changed_at FROM patient WHERE id = :id").bind("id", id)
                .map(r -> r.get("status", String.class) + "/" + r.get("level", String.class) + "/"
                        + r.get("status_changed_at", OffsetDateTime.class).toInstant()).one().block();
        assertThat(patientRow).isEqualTo("REGISTRADO/CRITICO/" + T0);

        List<String> payloads = outboxPayloads(id);
        assertThat(payloads).hasSize(1);
        JsonNode json = mapper.readTree(payloads.get(0));
        assertThat(json.get("eventType").asText()).isEqualTo("paciente_escalado");
        assertThat(json.get("aggregateVersion").asLong()).isEqualTo(2L);
        assertThat(json.get("payload").get("escalated").asBoolean()).isTrue();
        assertThat(json.get("payload").get("estado").asText()).isEqualTo("REGISTRADO");
        assertThat(json.get("payload").get("nivel").asText()).isEqualTo("CRITICO");
        assertThat(registry.counter("triage.escalamientos", "nivel", "CRITICO").count()).isEqualTo(before + 1);

        client.get().uri("/triage/pacientes/" + id).header("X-API-Key", API_KEY).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.flags.escalado").isEqualTo(true)
                .jsonPath("$.slaMinutos").isEqualTo(5);
    }

    @Test
    void alreadyEscalatedIsNotEscalatedAgain() {
        UUID id = insert(TriageLevel.CRITICO);
        Instant now = T0.plus(1, ChronoUnit.HOURS);
        assertThat(useCase(now, 100).escalateAll().block()).isEqualTo(1L);
        assertThat(useCase(now.plusSeconds(60), 100).escalateAll().block()).isZero();

        assertThat(version(id)).isEqualTo(2L);
        assertThat(audits(id)).isEqualTo(1L);
        assertThat(outboxPayloads(id)).hasSize(1);
    }

    @Test
    void activePatientsAreNeverEscalated() {
        UUID inConsultation = insert(TriageLevel.CRITICO, 5, "EN_CONSULTA", false, T0);
        UUID discharged = insert(TriageLevel.CRITICO, 5, "ALTA", false, T0);

        assertThat(useCase(T0.plus(1, ChronoUnit.DAYS), 100).escalateAll().block()).isZero();

        for (UUID id : List.of(inConsultation, discharged)) {
            assertThat(escalated(id)).isFalse();
            assertThat(version(id)).isEqualTo(1L);
            assertThat(audits(id)).isZero();
            assertThat(outboxPayloads(id)).isEmpty();
        }
    }

    @Test
    void moreOverdueThanBatchSizeAreAllEscalatedInSequentialBatches() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            ids.add(insert(TriageLevel.CRITICO));
        }
        assertThat(useCase(T0.plus(1, ChronoUnit.HOURS), 10).escalateAll().block()).isEqualTo(25L);
        ids.forEach(id -> {
            assertThat(escalated(id)).isTrue();
            assertThat(audits(id)).isEqualTo(1L);
        });
    }

    @Test
    void concurrentCyclesEscalateEachPatientExactlyOnce() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            ids.add(insert(TriageLevel.URGENTE));
        }
        EscalateOverduePatientsUseCase a = useCase(T0.plus(1, ChronoUnit.HOURS), 7);
        EscalateOverduePatientsUseCase b = useCase(T0.plus(1, ChronoUnit.HOURS), 7);

        Long total = Mono.zip(a.escalateAll().subscribeOn(Schedulers.parallel()),
                        b.escalateAll().subscribeOn(Schedulers.parallel()))
                .map(t -> t.getT1() + t.getT2()).block(Duration.ofSeconds(60));

        assertThat(total).isEqualTo(60L);
        ids.forEach(id -> {
            assertThat(version(id)).isEqualTo(2L);
            assertThat(audits(id)).isEqualTo(1L);
            assertThat(outboxPayloads(id)).hasSize(1);
        });
    }

    @Test
    void auditFailureRollsBackFlagAndOutbox() {
        UUID id = insert(TriageLevel.CRITICO);
        doReturn(Mono.error(new IllegalStateException("audit down"))).when(audits).save(any());

        try {
            useCase(T0.plus(1, ChronoUnit.HOURS), 100).escalateAll().block();
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("audit down");
        }

        assertThat(escalated(id)).isFalse();
        assertThat(version(id)).isEqualTo(1L);
        assertThat(outboxPayloads(id)).isEmpty();
    }

    @Test
    void persistedSlaIsWhatEscalationAndGetUse() {
        // SLA persistido distinto del default del nivel: manda la columna.
        UUID id = insert(TriageLevel.NO_URGENTE_2, 3, "REGISTRADO", false, T0);
        assertThat(useCase(T0.plus(3, ChronoUnit.MINUTES), 100).escalateAll().block()).isZero();
        assertThat(useCase(T0.plus(3, ChronoUnit.MINUTES).plus(1, ChronoUnit.MICROS), 100).escalateAll().block())
                .isEqualTo(1L);
        assertThat(escalated(id)).isTrue();
        client.get().uri("/triage/pacientes/" + id).header("X-API-Key", API_KEY).exchange()
                .expectBody().jsonPath("$.slaMinutos").isEqualTo(3);
    }
}
