package com.vidacruz.triage_service.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage_service.PostgresTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import com.vidacruz.triage_service.application.AuditRepositoryPort;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Mono;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
class PatientRegistrationIntegrationTest extends PostgresTestSupport {

    private static final String E1 = """
            {"nombreCompleto":"Ana Pérez","edad":54,"quejaPrincipal":"Dolor torácico",
             "signosVitales":{"frecuenciaCardiaca":130,"presionSistolica":180,"presionDiastolica":110,
             "spo2":97,"temperatura":36.8,"dolor":8,"gcs":15}}
            """;

    @Autowired WebTestClient client;
    @Autowired DatabaseClient db;
    @Autowired MeterRegistry meters;
    @MockitoSpyBean AuditRepositoryPort auditPort;

    private long count(String table) {
        return db.sql("SELECT count(*) AS c FROM " + table).map(r -> r.get("c", Long.class)).one().block();
    }

    private WebTestClient.RequestHeadersSpec<?> post(String body, String apiKey) {
        WebTestClient.RequestBodySpec spec = client.post().uri("/triage/pacientes")
                .contentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) {
            spec.header("X-API-Key", apiKey);
        }
        return spec.bodyValue(body);
    }

    @Test
    void registersPatientAndAudit() {
        double before = meters.counter("triage.clasificaciones", "nivel", "CRITICO").count();
        long patients = count("patient");
        long audits = count("audit_event");

        Map<String, Object> body = post(E1, API_KEY).exchange()
                .expectStatus().isCreated()
                .expectBody(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .returnResult().getResponseBody();

        assertThat(body).containsEntry("nivel", "CRITICO").containsEntry("slaMinutos", 5)
                .containsEntry("estado", "REGISTRADO").containsKeys("id", "fechaRegistro");
        assertThat((String) body.get("fechaRegistro")).endsWith("Z");
        assertThat(count("patient")).isEqualTo(patients + 1);
        assertThat(count("audit_event")).isEqualTo(audits + 1);

        Map<String, Object> row = db.sql("""
                SELECT a.previous_status, a.new_status, a.event_type,
                       a.occurred_at = p.registered_at AS same_ts, p.status_changed_at = p.registered_at AS same_status_ts
                FROM audit_event a JOIN patient p ON p.id = a.patient_id
                WHERE p.id = :id""").bind("id", java.util.UUID.fromString((String) body.get("id")))
                .fetch().one().block();
        assertThat(row.get("previous_status")).isNull();
        assertThat(row).containsEntry("new_status", "REGISTRADO")
                .containsEntry("event_type", "paciente_registrado")
                .containsEntry("same_ts", true).containsEntry("same_status_ts", true);

        assertThat(meters.counter("triage.clasificaciones", "nivel", "CRITICO").count()).isEqualTo(before + 1);

        Map<String, Object> p = db.sql("SELECT * FROM patient WHERE id = :id")
                .bind("id", java.util.UUID.fromString((String) body.get("id"))).fetch().one().block();
        assertThat(p).containsEntry("level", "CRITICO").containsEntry("sla_minutes", 5)
                .containsEntry("heart_rate", (short) 130).containsEntry("systolic", (short) 180)
                .containsEntry("diastolic", (short) 110).containsEntry("spo2", (short) 97)
                .containsEntry("pain", (short) 8).containsEntry("gcs", (short) 15)
                .containsEntry("age", (short) 54).containsEntry("escalated", false)
                .containsEntry("status", "REGISTRADO")
                .containsEntry("full_name", "Ana Pérez").containsEntry("chief_complaint", "Dolor torácico");
        assertThat(((java.math.BigDecimal) p.get("temperature")).compareTo(new java.math.BigDecimal("36.8")))
                .isZero();
    }

    @Test
    void auditFailureRollsBackPatientAndSkipsMetric() {
        long patients = count("patient");
        long audits = count("audit_event");
        double before = meters.counter("triage.clasificaciones", "nivel", "CRITICO").count();
        doReturn(Mono.error(new IllegalStateException("fallo simulado"))).when(auditPort).save(any());

        post(E1, API_KEY).exchange().expectStatus().is5xxServerError();

        assertThat(count("patient")).isEqualTo(patients);
        assertThat(count("audit_event")).isEqualTo(audits);
        assertThat(meters.counter("triage.clasificaciones", "nivel", "CRITICO").count()).isEqualTo(before);
    }

    @Test
    void floatForIntegerFieldsReturns400() {
        post(E1.replace("\"edad\":54", "\"edad\":54.5"), API_KEY).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.campo").isEqualTo("edad");
        post(E1.replace("\"gcs\":15", "\"gcs\":14.7"), API_KEY).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.campo").isEqualTo("signosVitales.gcs");
    }

    @Test
    void diastolicOneBelowSystolicReturns201() {
        post(with("presionSistolica", "120").replace("\"presionDiastolica\":50", "\"presionDiastolica\":119"),
                API_KEY).exchange().expectStatus().isCreated();
    }

    @Test
    void rangeErrorOnSystolicWinsOverCrossFieldError() {
        post(with("presionSistolica", "40"), API_KEY).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.campo").isEqualTo("signosVitales.presionSistolica");
    }

    @Test
    void nulCharacterReturns400() {
        post(with("nombreCompleto", "Ana\\u0000X"), API_KEY).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.campo").isEqualTo("nombreCompleto");
        post(E1.replace("Dolor torácico", "Dolor\\u0000"), API_KEY).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.campo").isEqualTo("quejaPrincipal");
    }

    @Test
    void validationMessagesAreSpanishRegardlessOfLocale() {
        java.util.Locale previous = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.ENGLISH);
        try {
            post(with("spo2", "69"), API_KEY).exchange().expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.detail")
                    .isEqualTo("El campo 'signosVitales.spo2' es inválido: debe ser mayor o igual a 70");
            post(E1.replace("\"edad\":54,", ""), API_KEY).exchange().expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.detail")
                    .isEqualTo("El campo 'edad' es inválido: es obligatorio");
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    void wrongApiKeyOnHealthStillReturns200() {
        client.get().uri("/actuator/health/readiness").header("X-API-Key", "wrong").exchange()
                .expectStatus().isOk();
    }

    @Test
    void outOfRangeFieldReturns400AndPersistsNothing() {
        long patients = count("patient");
        long audits = count("audit_event");
        String bad = E1.replace("\"spo2\":97", "\"spo2\":69");

        post(bad, API_KEY).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.campo").isEqualTo("signosVitales.spo2")
                .jsonPath("$.detail").value(d -> assertThat((String) d).contains("signosVitales.spo2"));

        assertThat(count("patient")).isEqualTo(patients);
        assertThat(count("audit_event")).isEqualTo(audits);
    }

    @Test
    void diastolicNotLowerThanSystolicReturns400() {
        String bad = E1.replace("\"presionSistolica\":180,\"presionDiastolica\":110",
                "\"presionSistolica\":120,\"presionDiastolica\":120");
        post(bad, API_KEY).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.campo").isEqualTo("signosVitales.presionDiastolica");
    }

    @Test
    void missingFieldReturns400() {
        String bad = E1.replace("\"edad\":54,", "");
        post(bad, API_KEY).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.campo").isEqualTo("edad");
    }

    @Test
    void wrongTypeReturns400WithField() {
        String bad = E1.replace("\"gcs\":15", "\"gcs\":\"abc\"");
        post(bad, API_KEY).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.campo").isEqualTo("signosVitales.gcs");
    }

    @Test
    void malformedJsonReturns400() {
        post("{not json", API_KEY).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.status").isEqualTo(400);
    }

    @Test
    void missingApiKeyReturns401WithoutBody() {
        long patients = count("patient");
        post(E1, null).exchange().expectStatus().isUnauthorized().expectBody().isEmpty();
        assertThat(count("patient")).isEqualTo(patients);
    }

    @Test
    void wrongApiKeyReturns401() {
        post(E1, "nope").exchange().expectStatus().isUnauthorized().expectBody().isEmpty();
    }

    @Test
    void healthIsPublicButMetricsAreProtected() {
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
        client.get().uri("/actuator/metrics").exchange().expectStatus().isUnauthorized();
        client.get().uri("/actuator/metrics").header("X-API-Key", API_KEY).exchange().expectStatus().isOk();
    }

    // ---- Fuera de rango / valores límite ----

    private static String body(String name, String age, String sys, String dia, String hr, String spo2,
            String temp, String pain, String gcs) {
        return """
                {"nombreCompleto":"%s","edad":%s,"quejaPrincipal":"Dolor torácico",
                 "signosVitales":{"frecuenciaCardiaca":%s,"presionSistolica":%s,"presionDiastolica":%s,
                 "spo2":%s,"temperatura":%s,"dolor":%s,"gcs":%s}}
                """.formatted(name, age, hr, sys, dia, spo2, temp, pain, gcs);
    }

    /** Request válido con un único campo sustituido. */
    private static String with(String field, String value) {
        String[] v = {"Ana Pérez", "54", "180", "50", "80", "97", "36.8", "5", "15"};
        String[] names = {"nombreCompleto", "edad", "presionSistolica", "presionDiastolica",
                "frecuenciaCardiaca", "spo2", "temperatura", "dolor", "gcs"};
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(field)) {
                v[i] = value;
            }
        }
        return body(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8]);
    }

    static Stream<Arguments> outOfRange() {
        return Stream.of(
                Arguments.of("frecuenciaCardiaca", "19", "signosVitales.frecuenciaCardiaca"),
                Arguments.of("frecuenciaCardiaca", "251", "signosVitales.frecuenciaCardiaca"),
                Arguments.of("presionSistolica", "59", "signosVitales.presionSistolica"),
                Arguments.of("presionSistolica", "261", "signosVitales.presionSistolica"),
                Arguments.of("presionDiastolica", "29", "signosVitales.presionDiastolica"),
                Arguments.of("presionDiastolica", "161", "signosVitales.presionDiastolica"),
                Arguments.of("spo2", "69", "signosVitales.spo2"),
                Arguments.of("spo2", "101", "signosVitales.spo2"),
                Arguments.of("temperatura", "33.9", "signosVitales.temperatura"),
                Arguments.of("temperatura", "42.1", "signosVitales.temperatura"),
                Arguments.of("dolor", "-1", "signosVitales.dolor"),
                Arguments.of("dolor", "11", "signosVitales.dolor"),
                Arguments.of("gcs", "2", "signosVitales.gcs"),
                Arguments.of("gcs", "16", "signosVitales.gcs"),
                Arguments.of("edad", "-1", "edad"),
                Arguments.of("edad", "121", "edad"),
                Arguments.of("nombreCompleto", "ab", "nombreCompleto"),
                Arguments.of("nombreCompleto", "a".repeat(101), "nombreCompleto"));
    }

    @ParameterizedTest(name = "{0}={1} -> 400 {2}")
    @MethodSource("outOfRange")
    void outOfRangeReturns400WithExactField(String field, String value, String campo) {
        long patients = count("patient");
        post(with(field, value), API_KEY).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.campo").isEqualTo(campo)
                .jsonPath("$.detail").value(d -> assertThat((String) d).contains(campo));
        assertThat(count("patient")).isEqualTo(patients);
    }

    static Stream<Arguments> validEdges() {
        return Stream.of(
                Arguments.of("frecuenciaCardiaca", "20"), Arguments.of("frecuenciaCardiaca", "250"),
                Arguments.of("presionSistolica", "260"),
                Arguments.of("presionDiastolica", "30"), Arguments.of("presionDiastolica", "160"),
                Arguments.of("spo2", "70"), Arguments.of("spo2", "100"),
                Arguments.of("temperatura", "34.0"), Arguments.of("temperatura", "42.0"),
                Arguments.of("dolor", "0"), Arguments.of("dolor", "10"),
                Arguments.of("gcs", "3"), Arguments.of("gcs", "15"),
                Arguments.of("edad", "0"), Arguments.of("edad", "120"),
                Arguments.of("nombreCompleto", "abc"), Arguments.of("nombreCompleto", "a".repeat(100)));
    }

    @ParameterizedTest(name = "{0}={1} -> 201")
    @MethodSource("validEdges")
    void validEdgeValuesReturn201(String field, String value) {
        post(with(field, value), API_KEY).exchange().expectStatus().isCreated();
    }

    @Test
    void minimumSystolicWithLowerDiastolicReturns201() {
        post(body("Ana Pérez", "54", "60", "30", "80", "97", "36.8", "5", "15"), API_KEY)
                .exchange().expectStatus().isCreated();
    }

    // ---- Logs: JSON ECS con traceId, sin PII ni clave ----

    @Test
    void logsAreEcsJsonWithTraceIdAndNoPii(CapturedOutput output) {
        post(E1, API_KEY).exchange().expectStatus().isCreated();

        String line = output.getAll().lines().filter(l -> l.contains("Paciente registrado")).findFirst()
                .orElseThrow(() -> new AssertionError("No se encontró el log 'Paciente registrado'"));
        com.fasterxml.jackson.databind.JsonNode json;
        try {
            json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(line);
        } catch (Exception e) {
            throw new AssertionError("La línea de log no es JSON: " + line, e);
        }
        String traceId = json.path("traceId").asText("");
        assertThat(traceId).as("trace id en %s", line).isNotBlank();
        assertThat(json.has("@timestamp")).isTrue();

        String all = output.getAll();
        assertThat(all).doesNotContain("Ana Pérez").doesNotContain("Dolor torácico").doesNotContain(API_KEY);
    }
}
