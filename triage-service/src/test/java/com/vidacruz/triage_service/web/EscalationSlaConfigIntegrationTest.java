package com.vidacruz.triage_service.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage_service.PostgresTestSupport;
import com.vidacruz.triage_service.application.AlertRepositoryPort;
import com.vidacruz.triage_service.application.AuditRepositoryPort;
import com.vidacruz.triage_service.application.EscalateOverduePatientsUseCase;
import com.vidacruz.triage_service.application.EscalationMetricsPort;
import com.vidacruz.triage_service.application.OutboxPort;
import com.vidacruz.triage_service.application.PatientRepositoryPort;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.reactive.TransactionalOperator;

@SpringBootTest(properties = "triage.sla.minutes.CRITICO=1")
@AutoConfigureWebTestClient
class EscalationSlaConfigIntegrationTest extends PostgresTestSupport {

    private static final String CRITICAL = """
            {"nombreCompleto":"Ana Pérez","edad":54,"quejaPrincipal":"Dolor torácico",
             "signosVitales":{"frecuenciaCardiaca":130,"presionSistolica":180,"presionDiastolica":110,
             "spo2":97,"temperatura":36.8,"dolor":8,"gcs":15}}
            """;

    @Autowired WebTestClient client;
    @Autowired DatabaseClient db;
    @Autowired PatientRepositoryPort patients;
    @Autowired AuditRepositoryPort audits;
    @Autowired OutboxPort outbox;
    @Autowired AlertRepositoryPort alerts;
    @Autowired EscalationMetricsPort metrics;
    @Autowired TransactionalOperator tx;

    private UUID created;

    @AfterEach
    void cleanUp() {
        if (created != null) {
            db.sql("DELETE FROM medical_alert WHERE patient_id = :id").bind("id", created).fetch().rowsUpdated().block();
            db.sql("DELETE FROM outbox_event WHERE aggregate_id = :id").bind("id", created).fetch().rowsUpdated().block();
            db.sql("DELETE FROM audit_event WHERE patient_id = :id").bind("id", created).fetch().rowsUpdated().block();
            db.sql("DELETE FROM patient WHERE id = :id").bind("id", created).fetch().rowsUpdated().block();
        }
    }

    @Test
    void configuredSlaIsPersistedReturnedAndUsedToEscalate() {
        Map<String, Object> body = client.post().uri("/triage/pacientes")
                .contentType(MediaType.APPLICATION_JSON).header("X-API-Key", API_KEY).bodyValue(CRITICAL)
                .exchange().expectStatus().isCreated()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .returnResult().getResponseBody();
        UUID id = UUID.fromString((String) body.get("id"));
        created = id;
        assertThat(body.get("slaMinutos")).isEqualTo(1);

        Integer persisted = db.sql("SELECT sla_minutes FROM patient WHERE id = :id").bind("id", id)
                .map(r -> r.get("sla_minutes", Integer.class)).one().block();
        assertThat(persisted).isEqualTo(1);
        client.get().uri("/triage/pacientes/" + id).header("X-API-Key", API_KEY).exchange()
                .expectBody().jsonPath("$.slaMinutos").isEqualTo(1);

        Instant registeredAt = db.sql("SELECT registered_at FROM patient WHERE id = :id").bind("id", id)
                .map(r -> r.get("registered_at", OffsetDateTime.class).toInstant()).one().block();
        Instant exact = registeredAt.plus(1, ChronoUnit.MINUTES);
        useCase(exact).escalateAll().block();
        client.get().uri("/triage/pacientes/" + id).header("X-API-Key", API_KEY).exchange()
                .expectBody().jsonPath("$.flags.escalado").isEqualTo(false);
        useCase(exact.plus(1, ChronoUnit.MICROS)).escalateAll().block();
        client.get().uri("/triage/pacientes/" + id).header("X-API-Key", API_KEY).exchange()
                .expectBody().jsonPath("$.flags.escalado").isEqualTo(true);
    }

    private EscalateOverduePatientsUseCase useCase(Instant now) {
        return new EscalateOverduePatientsUseCase(patients, audits, outbox, alerts, metrics, tx,
                Clock.fixed(now, ZoneOffset.UTC), 100);
    }
}
