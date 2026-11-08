package com.vidacruz.clinical_assistant_service.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.vidacruz.clinical_assistant_service.application.ProyeccionUrgenciasService;
import com.vidacruz.clinical_assistant_service.domain.ProyeccionNoDisponibleException;
import reactor.core.publisher.Mono;

/** Sin Docker: el servicio se simula. */
@SpringBootTest(properties = "assistant.security.api-key=test-key")
@AutoConfigureWebTestClient
class EstadoUrgenciasUnavailableIT {

    @Autowired
    WebTestClient client;
    @MockitoBean
    ProyeccionUrgenciasService service;

    @Test
    void redisFailureYields503ProblemDetailWithoutLeakingTheCause() {
        when(service.estado()).thenReturn(Mono.error(
                new ProyeccionNoDisponibleException(new RuntimeException("redis://secreto:6379 caido"))));

        client.get().uri("/asistente/estado-urgencias").header("X-API-Key", "test-key").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody(String.class).value(body -> {
                    assertThat(body).contains("Servicio no disponible");
                    assertThat(body).doesNotContain("secreto").doesNotContain("redis://");
                });
    }
}
