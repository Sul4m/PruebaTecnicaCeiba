package com.vidacruz.triage_service.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage_service.PostgresTestSupport;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Datos sintéticos el 2001-03-10 (UTC) con resultados calculados a mano. Hora 10:00:
 * <pre>
 * A CRITICO      10:00:00  consulta 10:10:00  espera 10
 * B CRITICO      10:15:00  consulta 10:35:00  espera 20
 * C URGENTE      10:30:00  consulta 11:10:00  espera 40 (la consulta cae en la hora siguiente)
 * E URGENTE_2    10:45:00  consultas 10:50:00 y 12:45:00 -> solo la primera: espera 5
 * D NO_URGENTE_2 10:59:59  sin consulta (solo alta 11:30)
 * </pre>
 * total 5; CRITICO 2, URGENTE 1, URGENTE_2 1, NO_URGENTE 0, NO_URGENTE_2 1.
 * Esperas {5, 10, 20, 40}: promedio 75/4 = 18.75; p95 = 20 + 0.85 * (40 - 20) = 37.00.
 * Hora 11:00: F NO_URGENTE 11:00:00 sin consulta -> total 1, promedio y p95 null.
 * Fuera de [10:00, 12:00): H 09:59:59 y G 12:00:00.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class OperationsReportIntegrationTest extends PostgresTestSupport {

    private static final String DAY = "2001-03-10";

    @Autowired WebTestClient client;
    @Autowired DatabaseClient db;

    @BeforeEach
    void seed() {
        db.sql("DELETE FROM audit_event WHERE patient_id IN (SELECT id FROM patient WHERE registered_at < '2002-01-01')")
                .fetch().rowsUpdated().block();
        db.sql("DELETE FROM patient WHERE registered_at < '2002-01-01'").fetch().rowsUpdated().block();

        UUID a = patient("CRITICO", "10:00:00");
        UUID b = patient("CRITICO", "10:15:00");
        UUID c = patient("URGENTE", "10:30:00");
        UUID e = patient("URGENTE_2", "10:45:00");
        UUID d = patient("NO_URGENTE_2", "10:59:59");
        patient("NO_URGENTE", "11:00:00");
        patient("CRITICO", "09:59:59");
        patient("URGENTE", "12:00:00");

        event(a, "paciente_en_consulta", "REGISTRADO", "EN_CONSULTA", "10:10:00");
        event(b, "paciente_en_consulta", "REGISTRADO", "EN_CONSULTA", "10:35:00");
        event(c, "paciente_en_consulta", "REGISTRADO", "EN_CONSULTA", "11:10:00");
        event(e, "paciente_en_consulta", "REGISTRADO", "EN_CONSULTA", "10:50:00");
        event(e, "paciente_en_consulta", "REGISTRADO", "EN_CONSULTA", "12:45:00");
        event(d, "paciente_alta", "REGISTRADO", "ALTA", "11:30:00");
    }

    private static OffsetDateTime at(String time) {
        return OffsetDateTime.parse(DAY + "T" + time + "Z").withOffsetSameInstant(ZoneOffset.UTC);
    }

    private UUID patient(String level, String registeredAt) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO patient (id, full_name, age, chief_complaint, heart_rate, systolic, diastolic, spo2,
                    temperature, pain, gcs, level, sla_minutes, status, escalated, registered_at, status_changed_at)
                VALUES (:id, 'Paciente Sintetico', 30, 'Prueba', 70, 115, 75, 99, 36.5, 0, 15, :level, 5,
                    'REGISTRADO', false, :at, :at)
                """)
                .bind("id", id).bind("level", level).bind("at", at(registeredAt))
                .fetch().rowsUpdated().block();
        return id;
    }

    private void event(UUID patientId, String type, String previous, String next, String occurredAt) {
        db.sql("""
                INSERT INTO audit_event (id, patient_id, previous_status, new_status, event_type, occurred_at)
                VALUES (:id, :patient, :previous, :next, :type, :at)
                """)
                .bind("id", UUID.randomUUID()).bind("patient", patientId).bind("previous", previous)
                .bind("next", next).bind("type", type).bind("at", at(occurredAt))
                .fetch().rowsUpdated().block();
    }

    private WebTestClient.ResponseSpec report(String query, String apiKey) {
        WebTestClient.RequestHeadersSpec<?> spec = client.get().uri("/triage/reportes/operacion" + query);
        if (apiKey != null) {
            spec = spec.header("X-API-Key", apiKey);
        }
        return spec.exchange();
    }

    private static String range(String from, String to) {
        return "?desde=" + DAY + "T" + from + "Z&hasta=" + DAY + "T" + to + "Z";
    }

    @Test
    void groupsByUtcHourWithCountsAverageAndP95() {
        report(range("10:00:00", "12:00:00"), API_KEY).expectStatus().isOk().expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].hora").isEqualTo(DAY + "T10:00:00Z")
                .jsonPath("$[0].totalRegistrados").isEqualTo(5)
                .jsonPath("$[0].porNivel.CRITICO").isEqualTo(2)
                .jsonPath("$[0].porNivel.URGENTE").isEqualTo(1)
                .jsonPath("$[0].porNivel.URGENTE_2").isEqualTo(1)
                .jsonPath("$[0].porNivel.NO_URGENTE").isEqualTo(0)
                .jsonPath("$[0].porNivel.NO_URGENTE_2").isEqualTo(1)
                .jsonPath("$[0].promedioMinutosHastaConsulta").isEqualTo(18.75)
                .jsonPath("$[0].p95MinutosHastaConsulta").isEqualTo(37.0);
    }

    @Test
    void bucketWithoutAnyConsultationHasNullAverageAndP95ButKeepsCounts() {
        report(range("10:00:00", "12:00:00"), API_KEY).expectStatus().isOk().expectBody()
                .jsonPath("$[1].hora").isEqualTo(DAY + "T11:00:00Z")
                .jsonPath("$[1].totalRegistrados").isEqualTo(1)
                .jsonPath("$[1].porNivel.NO_URGENTE").isEqualTo(1)
                .jsonPath("$[1].porNivel.CRITICO").isEqualTo(0)
                .jsonPath("$[1].promedioMinutosHastaConsulta").value(v -> assertThat(v).isNull())
                .jsonPath("$[1].p95MinutosHastaConsulta").value(v -> assertThat(v).isNull());
    }

    @Test
    void rangeIsInclusiveOfDesdeAndExclusiveOfHasta() {
        // desde = 10:00:00 incluye a A; hasta = 12:00:00 excluye a G (12:00:00); H (09:59:59) queda fuera.
        report(range("10:00:00", "12:00:00"), API_KEY).expectStatus().isOk().expectBody()
                .jsonPath("$[0].totalRegistrados").isEqualTo(5)
                .jsonPath("$.length()").isEqualTo(2);

        // hasta un segundo después incorpora a G en una tercera fila.
        report(range("10:00:00", "12:00:01"), API_KEY).expectStatus().isOk().expectBody()
                .jsonPath("$.length()").isEqualTo(3)
                .jsonPath("$[2].hora").isEqualTo(DAY + "T12:00:00Z")
                .jsonPath("$[2].totalRegistrados").isEqualTo(1)
                .jsonPath("$[2].porNivel.URGENTE").isEqualTo(1);

        // desde un segundo después excluye a A: la hora 10 queda con 4 pacientes y esperas {5, 20, 40}.
        // promedio 65/3 = 21.67; p95 = 20 + 0.9 * 20 = 38.00.
        report(range("10:00:01", "12:00:00"), API_KEY).expectStatus().isOk().expectBody()
                .jsonPath("$[0].totalRegistrados").isEqualTo(4)
                .jsonPath("$[0].porNivel.CRITICO").isEqualTo(1)
                .jsonPath("$[0].promedioMinutosHastaConsulta").isEqualTo(21.67)
                .jsonPath("$[0].p95MinutosHastaConsulta").isEqualTo(38.0);
    }

    @Test
    void usesOnlyTheFirstConsultationEntry() {
        // E tiene consultas a los 5 y a los 120 minutos; solo cuenta la primera (si no, el promedio no sería 18.75).
        report("?desde=" + DAY + "T10:45:00Z&hasta=" + DAY + "T10:46:00Z", API_KEY).expectStatus().isOk().expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].totalRegistrados").isEqualTo(1)
                .jsonPath("$[0].promedioMinutosHastaConsulta").isEqualTo(5.0)
                .jsonPath("$[0].p95MinutosHastaConsulta").isEqualTo(5.0);
    }

    @Test
    void acceptsOffsetsAndInterpretsRangeInUtc() {
        // 05:00-05:00 == 10:00Z y 07:00-05:00 == 12:00Z.
        report("?desde=" + DAY + "T05:00:00-05:00&hasta=" + DAY + "T07:00:00-05:00", API_KEY)
                .expectStatus().isOk().expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].hora").isEqualTo(DAY + "T10:00:00Z")
                .jsonPath("$[0].totalRegistrados").isEqualTo(5);
        // Sin offset se interpreta como UTC.
        report("?desde=" + DAY + "T10:00:00&hasta=" + DAY + "T12:00:00", API_KEY)
                .expectStatus().isOk().expectBody().jsonPath("$[0].totalRegistrados").isEqualTo(5);
    }

    @Test
    void acceptsUnencodedPositiveOffset() {
        // '+' literal (sin %2B): 15:00+05:00 == 10:00Z y 17:00+05:00 == 12:00Z.
        client.get()
                .uri(java.net.URI.create("/triage/reportes/operacion?desde=" + DAY + "T15:00:00+05:00&hasta=" + DAY
                        + "T17:00:00+05:00"))
                .header("X-API-Key", API_KEY).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].hora").isEqualTo(DAY + "T10:00:00Z")
                .jsonPath("$[0].totalRegistrados").isEqualTo(5);
    }

    @Test
    void porNivelKeysMatchTriageLevelNames() {
        report(range("10:00:00", "12:00:00"), API_KEY).expectStatus().isOk().expectBody()
                .jsonPath("$[0].porNivel").value(v -> assertThat(((java.util.Map<String, ?>) v).keySet())
                        .containsExactlyInAnyOrderElementsOf(java.util.Arrays
                                .stream(com.vidacruz.triage_service.domain.TriageLevel.values())
                                .map(Enum::name).toList()));
    }

    @Test
    void emptyRangeReturnsEmptyList() {
        report(range("13:00:00", "14:00:00"), API_KEY).expectStatus().isOk().expectBody()
                .jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    void missingDesdeReturns400() {
        report("?hasta=" + DAY + "T12:00:00Z", API_KEY).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.title").isEqualTo("Solicitud inválida")
                .jsonPath("$.campo").isEqualTo("desde")
                .jsonPath("$.detail").value(d -> assertThat((String) d).contains("'desde'"));
    }

    @Test
    void missingHastaReturns400() {
        report("?desde=" + DAY + "T10:00:00Z", API_KEY).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.campo").isEqualTo("hasta");
    }

    @Test
    void invalidFormatReturns400NamingTheParameter() {
        report("?desde=ayer&hasta=" + DAY + "T12:00:00Z", API_KEY).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.campo").isEqualTo("desde");
        report("?desde=" + DAY + "T10:00:00Z&hasta=2001-13-45", API_KEY).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.campo").isEqualTo("hasta");
        report("?desde=" + DAY + "&hasta=" + DAY + "T12:00:00Z", API_KEY).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.campo").isEqualTo("desde");
    }

    @Test
    void desdeNotBeforeHastaReturns400() {
        report(range("12:00:00", "12:00:00"), API_KEY).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.campo").isEqualTo("desde")
                .jsonPath("$.detail").value(d -> assertThat((String) d).contains("'hasta'"));
        report(range("12:00:00", "10:00:00"), API_KEY).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.campo").isEqualTo("desde");
    }

    @Test
    void missingApiKeyReturns401() {
        report(range("10:00:00", "12:00:00"), null).expectStatus().isUnauthorized().expectBody().isEmpty();
    }
}
