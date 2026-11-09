package com.vidacruz.clinical_assistant_service.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Correlacion (Observabilidad): cada log ECS de una peticion lleva traceId. Sin Docker (dobles de dev). */
@SpringBootTest(properties = "assistant.security.api-key=test-key")
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
class LogCorrelationIT {

    @Autowired
    WebTestClient client;

    @Test
    void retrievalLogLineCarriesTraceIdAndNoQuestionText(CapturedOutput output) {
        String pregunta = "¿Cuáles son las medidas iniciales ante una crisis hipertensiva?";
        client.post().uri("/asistente/consulta").contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", "test-key").bodyValue(Map.of("pregunta", pregunta))
                .exchange().expectStatus().isOk();

        String line = output.getAll().lines().filter(l -> l.contains("RAG recuperacion")).findFirst()
                .orElseThrow(() -> new AssertionError("No se encontro el log de recuperacion"));
        JsonNode json;
        try {
            json = new ObjectMapper().readTree(line);
        } catch (Exception e) {
            throw new AssertionError("La linea de log no es JSON: " + line, e);
        }
        assertThat(json.path("traceId").asText("")).as("traceId en %s", line).isNotBlank();
        assertThat(output.getAll()).doesNotContain(pregunta).doesNotContain("test-key");
    }
}
