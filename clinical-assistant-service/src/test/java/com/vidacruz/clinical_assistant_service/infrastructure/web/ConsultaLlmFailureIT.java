package com.vidacruz.clinical_assistant_service.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.EnumMap;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.vidacruz.clinical_assistant_service.application.ProyeccionUrgenciasService;
import com.vidacruz.clinical_assistant_service.domain.ConsultaResultado;
import com.vidacruz.clinical_assistant_service.domain.EstadoUrgencias;
import com.vidacruz.clinical_assistant_service.domain.NivelTriaje;
import com.vidacruz.clinical_assistant_service.infrastructure.ai.LlmGuard;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Story 4.3: proveedor de chat con error/timeout => 503, circuito abierto; urgencias y abstencion intactas. */
@SpringBootTest(properties = { "assistant.security.api-key=test-key", "assistant.rag.llm-timeout=300ms",
        "assistant.llm.circuit-breaker.minimum-number-of-calls=3", "assistant.llm.circuit-breaker.sliding-window-size=3" })
@AutoConfigureWebTestClient
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ConsultaLlmFailureIT {

    private static final String Q = "¿Cuáles son las medidas iniciales ante una crisis hipertensiva?";

    @Autowired
    WebTestClient client;
    @Autowired
    LlmGuard guard;
    @MockitoBean
    ChatModel chatModel;
    @MockitoBean
    ProyeccionUrgenciasService urgencias;

    @BeforeEach
    void urgenciasUp() {
        Map<NivelTriaje, Long> m = new EnumMap<>(NivelTriaje.class);
        for (NivelTriaje n : NivelTriaje.values()) {
            m.put(n, 0L);
        }
        when(urgencias.estado()).thenReturn(Mono.just(new EstadoUrgencias(m)));
    }

    private WebTestClient.ResponseSpec consulta(String pregunta) {
        return client.post().uri("/asistente/consulta").header("X-API-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("pregunta", pregunta)).exchange();
    }

    private void assert503(WebTestClient.ResponseSpec spec) {
        spec.expectStatus().isEqualTo(503)
                .expectBody(String.class).value(body -> {
                    assertThat(body).contains("Servicio no disponible").contains("no esta disponible temporalmente");
                    assertThat(body).doesNotContain("PROTO").doesNotContain("Exception").doesNotContain("at com.");
                });
    }

    @Test
    void providerErrorYields503AndOpensCircuitThenRejectsFast() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("429 secreto")));

        for (int i = 0; i < 3; i++) {
            assert503(consulta(Q));
        }
        assertThat(guard.circuitBreaker(LlmGuard.CHAT).getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assert503(consulta(Q));
        verify(chatModel, times(3)).stream(any(Prompt.class));
    }

    @Test
    void providerTimeoutYields503() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.never());

        assert503(consulta(Q));
        assertThat(guard.circuitBreaker(LlmGuard.CHAT).getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void abstentionIsNotAProviderFailure() {
        client.post().uri("/asistente/consulta").header("X-API-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("pregunta", "¿Cual es el tratamiento de primera linea para la diabetes tipo 2?"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.respuesta").isEqualTo(ConsultaResultado.AVISO_SIN_PROTOCOLO);
        // Contenido que cita un protocolo ajeno: se descarta con 200 + aviso, no 503, y el circuito sigue cerrado.
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("Segun PROTO-999, hacer X."))))));
        consulta(Q).expectStatus().isOk()
                .expectBody().jsonPath("$.respuesta").isEqualTo(ConsultaResultado.AVISO_SIN_PROTOCOLO);
        assertThat(guard.circuitBreaker(LlmGuard.CHAT).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void validationErrorsStill400AndDoNotTouchProvider() {
        client.post().uri("/asistente/consulta").header("X-API-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("pregunta", " "))
                .exchange().expectStatus().isBadRequest();
        verify(chatModel, times(0)).stream(any(Prompt.class));
    }

    @Test
    void urgenciasStatusKeepsAnsweringWithProviderDownAndCircuitOpen() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("down")));
        for (int i = 0; i < 4; i++) {
            consulta(Q).expectStatus().isEqualTo(503);
        }
        client.get().uri("/asistente/estado-urgencias").header("X-API-Key", "test-key").exchange()
                .expectStatus().isOk();
    }
}
