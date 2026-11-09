package com.vidacruz.triage_service.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

import com.vidacruz.triage_service.PostgresTestSupport;
import com.vidacruz.triage_service.application.AuditRepositoryPort;
import com.vidacruz.triage_service.application.PatientRepositoryPort;
import com.vidacruz.triage_service.domain.PatientStatus;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@SpringBootTest
@AutoConfigureWebTestClient
class PatientStatusChangeIntegrationTest extends PostgresTestSupport {

    private static final String E5 = """
            {"nombreCompleto":"Luis Gómez","edad":40,"quejaPrincipal":"Renovación de recetario",
             "signosVitales":{"frecuenciaCardiaca":70,"presionSistolica":115,"presionDiastolica":75,
             "spo2":99,"temperatura":36.4,"dolor":0,"gcs":15}}
            """;

    @Autowired WebTestClient client;
    @Autowired DatabaseClient db;
    @MockitoSpyBean AuditRepositoryPort auditPort;

    @AfterEach
    void resetSpy() {
        reset(auditPort);
    }

    private UUID register() {
        Map<String, Object> created = client.post().uri("/triage/pacientes")
                .contentType(MediaType.APPLICATION_JSON).header("X-API-Key", API_KEY).bodyValue(E5)
                .exchange().expectStatus().isCreated()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .returnResult().getResponseBody();
        return UUID.fromString((String) created.get("id"));
    }

    private WebTestClient.ResponseSpec patch(String id, String body, String apiKey) {
        WebTestClient.RequestBodySpec spec = client.patch().uri("/triage/pacientes/" + id + "/estado")
                .contentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) {
            spec.header("X-API-Key", apiKey);
        }
        return spec.bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec patch(UUID id, String estado) {
        return patch(id.toString(), "{\"estado\":\"" + estado + "\"}", API_KEY);
    }

    private String status(UUID id) {
        return db.sql("SELECT status FROM patient WHERE id = :id").bind("id", id)
                .map(r -> r.get("status", String.class)).one().block();
    }

    /** Filas (event_type:previous->new) de auditoría del paciente, ordenadas. */
    private List<String> audits(UUID id) {
        return new ArrayList<>(db.sql("""
                SELECT event_type, previous_status, new_status FROM audit_event
                WHERE patient_id = :id ORDER BY occurred_at, event_type
                """).bind("id", id)
                .map(r -> r.get("event_type", String.class) + ":" + r.get("previous_status", String.class)
                        + "->" + r.get("new_status", String.class))
                .all().collectList().block());
    }

    @Test
    void registeredToConsultation() {
        UUID id = register();
        patch(id, "EN_CONSULTA").expectStatus().isOk().expectBody()
                .jsonPath("$.id").isEqualTo(id.toString())
                .jsonPath("$.estado").isEqualTo("EN_CONSULTA")
                .jsonPath("$.minutosEnEstadoActual").isEqualTo(0);

        assertThat(status(id)).isEqualTo("EN_CONSULTA");
        assertThat(audits(id)).containsExactlyInAnyOrder("paciente_registrado:null->REGISTRADO",
                "paciente_en_consulta:REGISTRADO->EN_CONSULTA");
        Long matches = db.sql("""
                SELECT count(*) AS c FROM audit_event a JOIN patient p ON p.id = a.patient_id
                WHERE p.id = :id AND a.event_type = 'paciente_en_consulta'
                  AND a.occurred_at = p.status_changed_at
                """).bind("id", id).map(r -> r.get("c", Long.class)).one().block();
        assertThat(matches).isEqualTo(1L);
        client.get().uri("/triage/pacientes/" + id).header("X-API-Key", API_KEY).exchange()
                .expectBody().jsonPath("$.estado").isEqualTo("EN_CONSULTA");
    }

    @Test
    void directDischarge() {
        UUID id = register();
        patch(id, "ALTA").expectStatus().isOk().expectBody().jsonPath("$.estado").isEqualTo("ALTA");
        assertThat(status(id)).isEqualTo("ALTA");
        assertThat(audits(id)).hasSize(2).contains("paciente_alta:REGISTRADO->ALTA");
    }

    @Test
    void dischargeFromConsultation() {
        UUID id = register();
        patch(id, "EN_CONSULTA").expectStatus().isOk();
        patch(id, "ALTA").expectStatus().isOk();
        assertThat(audits(id)).contains("paciente_alta:EN_CONSULTA->ALTA").hasSize(3);
    }

    @Test
    void backwardTransitionReturns409() {
        UUID id = register();
        patch(id, "EN_CONSULTA").expectStatus().isOk();
        patch(id, "REGISTRADO").expectStatus().isEqualTo(409).expectBody()
                .jsonPath("$.title").isEqualTo("Transición de estado inválida")
                .jsonPath("$.estadoActual").isEqualTo("EN_CONSULTA")
                .jsonPath("$.estadoSolicitado").isEqualTo("REGISTRADO")
                .jsonPath("$.detail").value(d -> assertThat((String) d).contains("EN_CONSULTA", "REGISTRADO"));
        assertThat(status(id)).isEqualTo("EN_CONSULTA");
        assertThat(audits(id)).hasSize(2);
    }

    @Test
    void fromDischargeReturns409() {
        UUID id = register();
        patch(id, "ALTA").expectStatus().isOk();
        patch(id, "EN_CONSULTA").expectStatus().isEqualTo(409);
        patch(id, "ALTA").expectStatus().isEqualTo(409);
        patch(id, "REGISTRADO").expectStatus().isEqualTo(409);
        assertThat(status(id)).isEqualTo("ALTA");
        assertThat(audits(id)).hasSize(2);
    }

    @Test
    void sameStatusReturns409() {
        UUID id = register();
        patch(id, "REGISTRADO").expectStatus().isEqualTo(409);
        assertThat(status(id)).isEqualTo("REGISTRADO");
        assertThat(audits(id)).containsExactly("paciente_registrado:null->REGISTRADO");
    }

    @Test
    void unknownStatusReturns400() {
        UUID id = register();
        patch(id.toString(), "{\"estado\":\"FOO\"}", API_KEY).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.campo").isEqualTo("estado");
        assertThat(status(id)).isEqualTo("REGISTRADO");
    }

    @Test
    void missingOrNullStatusReturns400() {
        UUID id = register();
        patch(id.toString(), "{}", API_KEY).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.campo").isEqualTo("estado");
        patch(id.toString(), "{\"estado\":null}", API_KEY).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.campo").isEqualTo("estado");
        assertThat(status(id)).isEqualTo("REGISTRADO");
        assertThat(audits(id)).hasSize(1);
    }

    @Test
    void unknownPatientReturns404() {
        patch(UUID.randomUUID(), "ALTA").expectStatus().isNotFound().expectBody()
                .jsonPath("$.title").isEqualTo("Paciente no encontrado");
    }

    @Test
    void malformedIdReturns400() {
        patch("no-uuid", "{\"estado\":\"ALTA\"}", API_KEY).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.campo").isEqualTo("id");
    }

    @Test
    void missingApiKeyReturns401() {
        patch(UUID.randomUUID().toString(), "{\"estado\":\"ALTA\"}", null).expectStatus().isUnauthorized()
                .expectBody().isEmpty();
    }

    @Test
    void concurrentValidTransitionsStayConsistentWithAudit() {
        UUID id = register();
        List<Integer> codes = Mono.zip(
                        Mono.fromCallable(() -> patch(id, "EN_CONSULTA").returnResult(String.class)
                                .getStatus().value()).subscribeOn(Schedulers.boundedElastic()),
                        Mono.fromCallable(() -> patch(id, "ALTA").returnResult(String.class)
                                .getStatus().value()).subscribeOn(Schedulers.boundedElastic()))
                .map(t -> List.of(t.getT1(), t.getT2())).block();

        // Las dos transiciones son válidas desde REGISTRADO, pero EN_CONSULTA->ALTA también lo es:
        // si EN_CONSULTA gana y ALTA llega después, ambas pueden ser 200. Se exige coherencia con la auditoría.
        long transitions = audits(id).stream().filter(a -> !a.startsWith("paciente_registrado")).count();
        long ok = codes.stream().filter(c -> c == 200).count();
        assertThat(codes).allMatch(c -> c == 200 || c == 409);
        assertThat(transitions).isEqualTo(ok);
        assertThat(ok).isGreaterThanOrEqualTo(1);
    }

    @Test
    void concurrentSameTargetYieldsExactlyOne409() {
        UUID id = register();
        List<Integer> codes = Mono.zip(
                        Mono.fromCallable(() -> patch(id, "ALTA").returnResult(String.class)
                                .getStatus().value()).subscribeOn(Schedulers.boundedElastic()),
                        Mono.fromCallable(() -> patch(id, "ALTA").returnResult(String.class)
                                .getStatus().value()).subscribeOn(Schedulers.boundedElastic()))
                .map(t -> List.of(t.getT1(), t.getT2())).block();

        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(status(id)).isEqualTo("ALTA");
        assertThat(audits(id).stream().filter(a -> a.startsWith("paciente_alta")).count()).isEqualTo(1L);
    }

    @Autowired PatientRepositoryPort patientPort;

    @Test
    void conditionalUpdateOnlyAppliesWhenExpectedStatusMatches() {
        UUID id = register();
        java.time.Instant now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        assertThat(patientPort.updateStatus(id, PatientStatus.EN_CONSULTA, PatientStatus.ALTA, now).block())
                .isNull();
        assertThat(status(id)).isEqualTo("REGISTRADO");

        assertThat(patientPort.updateStatus(id, PatientStatus.REGISTRADO, PatientStatus.EN_CONSULTA, now).block())
                .isEqualTo(new PatientRepositoryPort.StatusUpdate(2L, false));
        assertThat(status(id)).isEqualTo("EN_CONSULTA");
    }

    @Test
    void auditFailureRollsBackStatus() {
        UUID id = register();
        doReturn(Mono.error(new IllegalStateException("fallo simulado"))).when(auditPort).save(any());

        patch(id, "EN_CONSULTA").expectStatus().is5xxServerError();

        assertThat(status(id)).isEqualTo("REGISTRADO");
        assertThat(audits(id)).containsExactly("paciente_registrado:null->REGISTRADO");
    }
}
