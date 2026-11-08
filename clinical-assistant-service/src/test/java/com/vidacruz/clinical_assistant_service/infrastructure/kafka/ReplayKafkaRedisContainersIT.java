package com.vidacruz.clinical_assistant_service.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.vidacruz.clinical_assistant_service.support.ProjectionContainers;
import com.vidacruz.clinical_assistant_service.support.TriageEvents;

/**
 * Reconstruccion de la proyeccion: con Redis vacio y un grupo NUEVO leyendo desde el offset mas antiguo se obtienen
 * los mismos conteos. Topic propio para que el historial sea solo de esta prueba. REQUIERE Docker.
 */
@SpringBootTest(properties = {
        "assistant.security.api-key=test-key",
        "assistant.projection.enabled=true",
        "assistant.projection.topic=triaje.eventos.replay",
        "assistant.projection.dlt-topic=triaje.eventos.replay.dlt",
        "spring.data.redis.database=2"})
@AutoConfigureWebTestClient
@org.springframework.test.annotation.DirtiesContext
@Testcontainers(disabledWithoutDocker = true)
class ReplayKafkaRedisContainersIT {

    private static final String KEY = "test-key";

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", ProjectionContainers.KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", ProjectionContainers.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> ProjectionContainers.REDIS.getMappedPort(6379));
        registry.add("assistant.projection.group-id", () -> "replay-a-" + UUID.randomUUID());
    }

    @Autowired
    WebTestClient client;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    ReactiveStringRedisTemplate redis;
    @Autowired
    KafkaListenerEndpointRegistry registry;

    @SuppressWarnings("unchecked")
    private Map<String, Integer> niveles() {
        Map<String, Object> body = client.get().uri("/asistente/estado-urgencias").header("X-API-Key", KEY)
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        return (Map<String, Integer>) body.get("niveles");
    }

    private void send(String patient, String json) throws Exception {
        kafka.send("triaje.eventos.replay", patient, json).get();
    }

    @Test
    void newConsumerGroupFromEarliestRebuildsTheSameCountsAfterFlushingRedis() throws Exception {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        String c = UUID.randomUUID().toString();
        String dup = TriageEvents.registrado(c, 1, "URGENTE_2");
        send(a, TriageEvents.registrado(a, 1, "CRITICO"));
        send(a, TriageEvents.escalado(a, 2, "CRITICO"));
        send(b, TriageEvents.registrado(b, 1, "NO_URGENTE"));
        send(b, TriageEvents.enConsulta(b, 2, "NO_URGENTE"));
        send(b, TriageEvents.alta(b, 3, "NO_URGENTE"));
        send(c, dup);
        send(c, dup);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Map<String, Integer> n = niveles();
            assertThat(n.get("CRITICO")).isEqualTo(1);
            assertThat(n.get("URGENTE_2")).isEqualTo(1);
            assertThat(n.get("NO_URGENTE")).isZero();
        });
        Map<String, Integer> before = niveles();

        var container = registry.getListenerContainer("urgencias-projection");
        container.stop();
        redis.getConnectionFactory().getReactiveConnection().serverCommands().flushDb().block();
        assertThat(niveles().values()).containsOnly(0);

        container.getContainerProperties().setGroupId("replay-b-" + UUID.randomUUID());
        container.start();

        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(niveles()).isEqualTo(before));
    }
}
