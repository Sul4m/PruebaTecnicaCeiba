package com.vidacruz.clinical_assistant_service.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.vidacruz.clinical_assistant_service.domain.ConsultaResultado;

/** El umbral es configurable: con umbral maximo, incluso una pregunta cubierta se abstiene sin invocar al modelo. */
@SpringBootTest(properties = { "assistant.security.api-key=test-key", "assistant.rag.similarity-threshold=1.0" })
@AutoConfigureWebTestClient
class ConsultaThresholdIT {

    @Autowired
    WebTestClient client;
    @MockitoBean
    ChatModel chatModel;

    @Test
    void thresholdFromConfigurationDrivesAbstention() {
        client.post().uri("/asistente/consulta").header("X-API-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("pregunta", "¿Cuáles son las medidas iniciales ante una crisis hipertensiva?"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.respuesta").isEqualTo(ConsultaResultado.AVISO_SIN_PROTOCOLO)
                .jsonPath("$.fuentes").isEmpty();
        verify(chatModel, never()).stream(any(Prompt.class));
    }
}
