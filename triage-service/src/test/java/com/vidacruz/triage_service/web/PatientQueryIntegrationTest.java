package com.vidacruz.triage_service.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage_service.PostgresTestSupport;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest
@AutoConfigureWebTestClient
class PatientQueryIntegrationTest extends PostgresTestSupport {

    private static final String E5 = """
            {"nombreCompleto":"Luis Gómez","edad":40,"quejaPrincipal":"Renovación de recetario",
             "signosVitales":{"frecuenciaCardiaca":70,"presionSistolica":115,"presionDiastolica":75,
             "spo2":99,"temperatura":36.4,"dolor":0,"gcs":15}}
            """;

    @Autowired WebTestClient client;
    @Autowired DatabaseClient db;

    private UUID register() {
        Map<String, Object> created = client.post().uri("/triage/pacientes")
                .contentType(MediaType.APPLICATION_JSON).header("X-API-Key", API_KEY).bodyValue(E5)
                .exchange().expectStatus().isCreated()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .returnResult().getResponseBody();
        return UUID.fromString((String) created.get("id"));
    }

    private WebTestClient.ResponseSpec get(String id, String apiKey) {
        WebTestClient.RequestHeadersSpec<?> spec = client.get().uri("/triage/pacientes/" + id);
        if (apiKey != null) {
            spec = spec.header("X-API-Key", apiKey);
        }
        return spec.exchange();
    }

    @Test
    void returnsFullPatientWithStatusFlagsAndTimeInStatus() {
        UUID id = register();

        get(id.toString(), API_KEY).expectStatus().isOk().expectBody()
                .jsonPath("$.id").isEqualTo(id.toString())
                .jsonPath("$.nombreCompleto").isEqualTo("Luis Gómez")
                .jsonPath("$.edad").isEqualTo(40)
                .jsonPath("$.quejaPrincipal").isEqualTo("Renovación de recetario")
                .jsonPath("$.signosVitales.frecuenciaCardiaca").isEqualTo(70)
                .jsonPath("$.signosVitales.presionSistolica").isEqualTo(115)
                .jsonPath("$.signosVitales.presionDiastolica").isEqualTo(75)
                .jsonPath("$.signosVitales.spo2").isEqualTo(99)
                .jsonPath("$.signosVitales.temperatura").isEqualTo(36.4)
                .jsonPath("$.signosVitales.dolor").isEqualTo(0)
                .jsonPath("$.signosVitales.gcs").isEqualTo(15)
                .jsonPath("$.nivel").isEqualTo("NO_URGENTE_2")
                .jsonPath("$.slaMinutos").isEqualTo(120)
                .jsonPath("$.estado").isEqualTo("REGISTRADO")
                .jsonPath("$.fechaRegistro").value(v -> assertThat((String) v).endsWith("Z"))
                .jsonPath("$.fechaCambioEstado").exists()
                .jsonPath("$.flags.escalado").isEqualTo(false)
                .jsonPath("$.minutosEnEstadoActual").isEqualTo(0);
    }

    @Test
    void timeInStatusCountsWholeMinutesSinceLastStatusChange() {
        UUID id = register();
        db.sql("UPDATE patient SET status_changed_at = registered_at - interval '7 minutes 30 seconds' WHERE id = :id")
                .bind("id", id).fetch().rowsUpdated().block();

        Map<String, Object> body = get(id.toString(), API_KEY).expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .returnResult().getResponseBody();
        assertThat(body).containsEntry("minutosEnEstadoActual", 7);
        // fechaCambioEstado viene de status_changed_at, no de registered_at.
        assertThat(java.time.Duration.between(Instant.parse((String) body.get("fechaCambioEstado")),
                Instant.parse((String) body.get("fechaRegistro")))).isEqualTo(java.time.Duration.ofSeconds(450));
    }

    @Test
    void escalatedFlagIsExposed() {
        UUID id = register();
        db.sql("UPDATE patient SET escalated = true WHERE id = :id").bind("id", id).fetch().rowsUpdated().block();

        get(id.toString(), API_KEY).expectStatus().isOk().expectBody()
                .jsonPath("$.flags.escalado").isEqualTo(true);
    }

    @Test
    void unknownIdReturns404() {
        UUID unknown = UUID.randomUUID();
        get(unknown.toString(), API_KEY).expectStatus().isNotFound().expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.title").isEqualTo("Paciente no encontrado")
                .jsonPath("$.detail").isEqualTo("No existe un paciente con id " + unknown);
    }

    @Test
    void malformedIdReturns400WithField() {
        get("no-es-un-uuid", API_KEY).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.title").isEqualTo("Solicitud inválida")
                .jsonPath("$.campo").isEqualTo("id")
                .jsonPath("$.detail").value(d -> assertThat((String) d).contains("'id'"));
    }

    @Test
    void missingApiKeyReturns401() {
        get(UUID.randomUUID().toString(), null).expectStatus().isUnauthorized().expectBody().isEmpty();
    }
}
