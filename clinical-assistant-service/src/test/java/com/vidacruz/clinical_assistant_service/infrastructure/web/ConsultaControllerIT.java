package com.vidacruz.clinical_assistant_service.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.vidacruz.clinical_assistant_service.domain.ConsultaResultado;
import com.vidacruz.clinical_assistant_service.infrastructure.ai.SpringAiProtocolRetriever;
import reactor.core.publisher.Flux;

/** Integracion con modelo de chat simulado (sin proveedor real, sin red). Embeddings: doble determinista de dev. */
@SpringBootTest(properties = "assistant.security.api-key=test-key")
@AutoConfigureWebTestClient
class ConsultaControllerIT {

    private static final String KEY = "test-key";

    @Autowired
    WebTestClient client;
    @Autowired
    SpringAiProtocolRetriever retriever;
    @MockitoBean
    ChatModel chatModel;

    @BeforeEach
    void stubModel() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("Respuesta simulada. Ver PROTO-999."))))));
    }

    private WebTestClient.ResponseSpec post(String key, Object body) {
        var spec = client.post().uri("/asistente/consulta").contentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            spec.header("X-API-Key", key);
        }
        return spec.bodyValue(body).exchange();
    }

    @Test
    void corpusIsLoadedWithMetadataAtStartup() {
        assertThat(retriever.isIndexed()).isTrue();
        assertThat(retriever.corpus()).hasSize(7)
                .allSatisfy(c -> assertThat(c.corpusVersion()).isEqualTo("v1"));
    }

    @Test
    void hypertensiveCrisisQuestionCitesProto001() {
        post(KEY, Map.of("pregunta", "¿Cuáles son las medidas iniciales ante una crisis hipertensiva?"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.respuesta").isEqualTo("Respuesta simulada. Ver PROTO-999.")
                .jsonPath("$.fuentes[0].protocolo").isEqualTo("PROTO-001")
                .jsonPath("$.fuentes[0].seccion").isEqualTo("Medidas iniciales")
                .jsonPath("$.fuentes[?(@.protocolo=='PROTO-999')]").isEmpty();
    }

    @Test
    void sepsisQuestionCitesProto002() {
        post(KEY, Map.of("pregunta", "¿Cuándo se administra el primer antibiótico en sepsis?"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.fuentes[0].protocolo").isEqualTo("PROTO-002")
                .jsonPath("$.fuentes[0].seccion").isEqualTo("Acciones iniciales");
    }

    @Test
    void unrelatedQuestionAbstainsWithoutCallingModel() {
        post(KEY, Map.of("pregunta", "¿Cuál es el tratamiento de primera línea para la diabetes tipo 2?"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.respuesta").isEqualTo(ConsultaResultado.AVISO_SIN_PROTOCOLO)
                .jsonPath("$.fuentes").isEmpty();
        verify(chatModel, never()).stream(any(Prompt.class));
    }

    @Test
    void stopwordOnlyQuestionAbstainsWithoutCallingModel() {
        post(KEY, Map.of("pregunta", "¿qué es?"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.respuesta").isEqualTo(ConsultaResultado.AVISO_SIN_PROTOCOLO)
                .jsonPath("$.fuentes").isEmpty();
        verify(chatModel, never()).stream(any(Prompt.class));
    }

    @Test
    void emptyQuestionIs400WithCampo() {
        post(KEY, Map.of("pregunta", "   "))
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.campo").isEqualTo("pregunta");
    }

    @Test
    void missingQuestionIs400WithCampo() {
        post(KEY, Map.of())
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.campo").isEqualTo("pregunta");
    }

    @Test
    void overLimitQuestionIs400WithCampoAndDoesNotEchoText() {
        String longQuestion = "a".repeat(ConsultaRequest.MAX_PREGUNTA + 1);
        post(KEY, Map.of("pregunta", longQuestion))
                .expectStatus().isBadRequest()
                .expectBody(String.class).value(b -> {
                    assertThat(b).contains("\"campo\":\"pregunta\"");
                    assertThat(b).doesNotContain(longQuestion);
                });
    }

    @Test
    void malformedBodyIs400() {
        client.post().uri("/asistente/consulta").header("X-API-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{no-json")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.campo").isEqualTo("pregunta").jsonPath("$.status").isEqualTo(400);
    }

    @Test
    void missingApiKeyIs401WithEmptyBody() {
        post(null, Map.of("pregunta", "hola")).expectStatus().isUnauthorized().expectBody().isEmpty();
    }

    @Test
    void wrongApiKeyIs401WithEmptyBody() {
        post("otra-clave", Map.of("pregunta", "hola")).expectStatus().isUnauthorized().expectBody().isEmpty();
    }

    @Test
    void providerFailureIs503ProblemDetail() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("upstream down")));
        post(KEY, Map.of("pregunta", "¿Cuáles son las medidas iniciales ante una crisis hipertensiva?"))
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.status").isEqualTo(503);
    }

    @Test
    void healthProbesArePublic() {
        client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
    }

    @Test
    void otherActuatorEndpointsRequireApiKey() {
        client.get().uri("/actuator/metrics").exchange().expectStatus().isUnauthorized();
    }
}
