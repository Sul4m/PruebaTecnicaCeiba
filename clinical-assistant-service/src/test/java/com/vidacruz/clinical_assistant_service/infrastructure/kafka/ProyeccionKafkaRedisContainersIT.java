package com.vidacruz.clinical_assistant_service.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.vidacruz.clinical_assistant_service.support.ProjectionContainers;
import com.vidacruz.clinical_assistant_service.support.TriageEvents;
import io.micrometer.core.instrument.MeterRegistry;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Kafka real + Redis real + contexto completo: consumidor, proyeccion y endpoint. REQUIERE Docker.
 * Cada prueba limpia Redis; los contadores de metricas se comparan por delta.
 */
@SpringBootTest(properties = {
        "assistant.security.api-key=test-key",
        "assistant.projection.enabled=true",
        "assistant.projection.topic=triaje.eventos.it",
        "assistant.projection.dlt-topic=triaje.eventos.it.dlt",
        "spring.data.redis.database=1",
        "assistant.projection.max-attempts=2",
        "assistant.projection.retry-backoff=50ms",
        "assistant.projection.retry-max-backoff=100ms"})
@AutoConfigureWebTestClient
@org.springframework.test.annotation.DirtiesContext
@Testcontainers(disabledWithoutDocker = true)
class ProyeccionKafkaRedisContainersIT {

    private static final String KEY = "test-key";
    private static final String TOPIC = "triaje.eventos.it";
    private static final String DLT = "triaje.eventos.it.dlt";

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", ProjectionContainers.KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", ProjectionContainers.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> ProjectionContainers.REDIS.getMappedPort(6379));
        registry.add("assistant.projection.group-id", () -> "it-" + UUID.randomUUID());
    }

    @Autowired
    WebTestClient client;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    ReactiveStringRedisTemplate redis;
    @Autowired
    MeterRegistry meters;

    @BeforeEach
    void cleanRedis() {
        redis.getConnectionFactory().getReactiveConnection().serverCommands().flushDb().block();
    }

    private void send(String patientId, String json) throws Exception {
        kafka.send(TOPIC, patientId, json).get();
    }

    private double metric(String name, String tagKey, String tagValue) {
        var search = meters.find(name);
        if (tagKey != null) {
            search = search.tag(tagKey, tagValue);
        }
        var counter = search.counter();
        return counter == null ? 0 : counter.count();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> niveles() {
        Map<String, Object> body = client.get().uri("/asistente/estado-urgencias").header("X-API-Key", KEY)
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        return (Map<String, Integer>) body.get("niveles");
    }

    private void awaitLevel(String nivel, int expected) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(niveles().get(nivel)).isEqualTo(expected));
    }

    @Test
    void registerConsultAndDischargeFlowThroughKafkaIntoTheEndpoint() throws Exception {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        send(a, TriageEvents.registrado(a, 1, "NO_URGENTE_2"));
        send(b, TriageEvents.registrado(b, 1, "CRITICO"));
        awaitLevel("NO_URGENTE_2", 1);
        awaitLevel("CRITICO", 1);

        send(b, TriageEvents.escalado(b, 2, "CRITICO"));
        send(a, TriageEvents.enConsulta(a, 2, "NO_URGENTE_2"));
        send(a, TriageEvents.alta(a, 3, "NO_URGENTE_2"));
        awaitLevel("NO_URGENTE_2", 0);

        Map<String, Integer> n = niveles();
        assertThat(n).containsExactly(Map.entry("CRITICO", 1), Map.entry("URGENTE", 0), Map.entry("URGENTE_2", 0),
                Map.entry("NO_URGENTE", 0), Map.entry("NO_URGENTE_2", 0));
        client.get().uri("/asistente/estado-urgencias").header("X-API-Key", KEY).exchange()
                .expectBody().jsonPath("$.totalActivos").isEqualTo(1);
    }

    @Test
    void duplicateEventDoesNotAlterCounts() throws Exception {
        double before = metric("assistant.projection.events", "result", "duplicado");
        String p = UUID.randomUUID().toString();
        String json = TriageEvents.registrado(p, 1, "URGENTE");
        send(p, json);
        send(p, json);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> metric("assistant.projection.events", "result", "duplicado") >= before + 1);
        assertThat(niveles().get("URGENTE")).isEqualTo(1);
    }

    @Test
    void oldVersionIsIgnoredAndSkippedVersionIsAppliedFromTheSnapshot() throws Exception {
        double oldBefore = metric("assistant.projection.events", "result", "version_antigua");
        double gapsBefore = metric("assistant.projection.version_gaps", null, null);
        String old = UUID.randomUUID().toString();
        send(old, TriageEvents.enConsulta(old, 2, "URGENTE_2"));
        send(old, TriageEvents.registrado(old, 1, "CRITICO"));
        await().atMost(Duration.ofSeconds(30))
                .until(() -> metric("assistant.projection.events", "result", "version_antigua") >= oldBefore + 1);
        assertThat(niveles().get("URGENTE_2")).isEqualTo(1);
        assertThat(niveles().get("CRITICO")).isZero();

        String skip = UUID.randomUUID().toString();
        send(skip, TriageEvents.registrado(skip, 1, "NO_URGENTE"));
        awaitLevel("NO_URGENTE", 1);
        send(skip, TriageEvents.alta(skip, 3, "NO_URGENTE")); // se "perdio" la v2
        awaitLevel("NO_URGENTE", 0);
        assertThat(metric("assistant.projection.version_gaps", null, null)).isGreaterThanOrEqualTo(gapsBefore + 1);
    }

    @Test
    void invalidAndUnsupportedMessagesGoToTheDeadLetterTopicNeverSilentlyDropped() throws Exception {
        String p = UUID.randomUUID().toString();
        kafka.send(TOPIC, p, "esto no es json").get();
        kafka.send(TOPIC, p, TriageEvents.json(UUID.randomUUID().toString(), "paciente_trasladado", 1, p, 1,
                "CRITICO", "REGISTRADO", false)).get();
        kafka.send(TOPIC, p, TriageEvents.json(UUID.randomUUID().toString(), "paciente_registrado", 2, p, 1,
                "CRITICO", "REGISTRADO", false)).get();

        List<ConsumerRecord<String, String>> dead = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, ProjectionContainers.KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(DLT));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(dead::add);
                return dead.size() >= 3;
            });
        }
        List<String> reasons = dead.stream()
                .map(r -> new String(r.headers().lastHeader("x-dlt-reason").value(), StandardCharsets.UTF_8)).toList();
        assertThat(reasons).contains("invalido", "tipo_no_soportado", "schema_no_soportado");
        assertThat(dead).anySatisfy(r -> assertThat(r.value()).isEqualTo("esto no es json"));
        assertThat(niveles().values()).containsOnly(0);
        // Los mensajes siguientes siguen procesandose (el DLT no bloquea la particion).
        send(p, TriageEvents.registrado(p, 1, "URGENTE"));
        awaitLevel("URGENTE", 1);
    }

    @Test
    void endpointReturnsAllLevelsWithZerosAndRequiresTheAssistantApiKey() {
        assertThat(niveles()).containsOnlyKeys("CRITICO", "URGENTE", "URGENTE_2", "NO_URGENTE", "NO_URGENTE_2")
                .containsValues(0, 0, 0, 0, 0);
        client.get().uri("/asistente/estado-urgencias").exchange().expectStatus().isUnauthorized();
        client.get().uri("/asistente/estado-urgencias").header("X-API-Key", "otra").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void readinessIncludesKafkaAndRedis() {
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }
}
