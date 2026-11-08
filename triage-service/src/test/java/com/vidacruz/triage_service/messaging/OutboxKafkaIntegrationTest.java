package com.vidacruz.triage_service.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.triage_service.PostgresTestSupport;
import com.vidacruz.triage_service.application.OutboxPort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest(properties = {"triage.outbox.enabled=true", "triage.outbox.max-backoff=2s"})
@AutoConfigureWebTestClient
class OutboxKafkaIntegrationTest extends PostgresTestSupport {

    private static final String TOPIC = "triaje.eventos";
    private static final String NAME = "Luis Gómez";
    private static final String COMPLAINT = "Renovación de recetario";
    private static final String E5 = """
            {"nombreCompleto":"%s","edad":40,"quejaPrincipal":"%s",
             "signosVitales":{"frecuenciaCardiaca":70,"presionSistolica":115,"presionDiastolica":75,
             "spo2":99,"temperatura":36.4,"dolor":0,"gcs":15}}
            """.formatted(NAME, COMPLAINT);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static KafkaConsumer<String, String> consumer;
    private static final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    @Autowired WebTestClient client;
    @Autowired DatabaseClient db;
    @MockitoSpyBean OutboxPort outboxPort;

    @BeforeAll
    static void startConsumer() {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(TOPIC));
    }

    @AfterAll
    static void stopConsumer() {
        consumer.close(Duration.ofSeconds(2));
    }

    @AfterEach
    void resetSpy() {
        reset(outboxPort);
    }

    private UUID register() {
        Map<String, Object> created = client.post().uri("/triage/pacientes")
                .contentType(MediaType.APPLICATION_JSON).header("X-API-Key", API_KEY).bodyValue(E5)
                .exchange().expectStatus().isCreated()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .returnResult().getResponseBody();
        return UUID.fromString((String) created.get("id"));
    }

    private WebTestClient.ResponseSpec patch(UUID id, String body) {
        return client.patch().uri("/triage/pacientes/" + id + "/estado")
                .contentType(MediaType.APPLICATION_JSON).header("X-API-Key", API_KEY)
                .bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec move(UUID id, String estado) {
        return patch(id, "{\"estado\":\"" + estado + "\"}");
    }

    private synchronized List<ConsumerRecord<String, String>> eventsOf(UUID id) {
        consumer.poll(Duration.ofMillis(300)).forEach(received::add);
        return received.stream().filter(r -> id.toString().equals(r.key())).toList();
    }

    private void awaitEvents(UUID id, int count) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .until(() -> eventsOf(id).size() >= count);
    }

    private long outboxRows(UUID id) {
        return db.sql("SELECT count(*) AS c FROM outbox_event WHERE aggregate_id = :id").bind("id", id)
                .map(r -> r.get("c", Long.class)).one().block();
    }

    private long unpublishedRows(UUID id) {
        return db.sql("SELECT count(*) AS c FROM outbox_event WHERE aggregate_id = :id AND published_at IS NULL")
                .bind("id", id).map(r -> r.get("c", Long.class)).one().block();
    }

    private long version(UUID id) {
        return db.sql("SELECT aggregate_version AS v FROM patient WHERE id = :id").bind("id", id)
                .map(r -> r.get("v", Long.class)).one().block();
    }

    @Test
    void registrationAndTransitionsArePublishedInOrderWithoutPii() throws Exception {
        UUID id = register();
        move(id, "EN_CONSULTA").expectStatus().isOk();
        move(id, "ALTA").expectStatus().isOk();

        awaitEvents(id, 3);
        List<ConsumerRecord<String, String>> events = eventsOf(id);
        assertThat(events).hasSize(3);
        assertThat(events).extracting(ConsumerRecord::partition).containsOnly(events.get(0).partition());

        String[] types = {"paciente_registrado", "paciente_en_consulta", "paciente_alta"};
        String[] states = {"REGISTRADO", "EN_CONSULTA", "ALTA"};
        for (int i = 0; i < 3; i++) {
            String json = events.get(i).value();
            JsonNode node = MAPPER.readTree(json);
            assertThat(node.get("eventType").asText()).isEqualTo(types[i]);
            assertThat(node.get("schemaVersion").asInt()).isEqualTo(1);
            assertThat(node.get("aggregateId").asText()).isEqualTo(id.toString());
            assertThat(node.get("aggregateVersion").asLong()).isEqualTo(i + 1);
            assertThat(node.get("occurredAt").asText()).endsWith("Z");
            assertThat(UUID.fromString(node.get("eventId").asText())).isNotNull();
            JsonNode payload = node.get("payload");
            assertThat(payload.get("patientId").asText()).isEqualTo(id.toString());
            assertThat(payload.get("nivel").asText()).isEqualTo("NO_URGENTE_2");
            assertThat(payload.get("estado").asText()).isEqualTo(states[i]);
            assertThat(payload.get("escalated").asBoolean()).isFalse();
            assertThat(payload.size()).isEqualTo(4);
            assertThat(json).doesNotContain(NAME, COMPLAINT, "Gómez");
        }

        await().atMost(Duration.ofSeconds(30)).until(() -> unpublishedRows(id) == 0);
        assertThat(outboxRows(id)).isEqualTo(3);
        assertThat(version(id)).isEqualTo(3);

        // occurredAt del evento = instante de la auditoría.
        Long matches = db.sql("""
                SELECT count(*) AS c FROM audit_event a JOIN outbox_event o
                  ON o.aggregate_id = a.patient_id AND o.event_type = a.event_type
                WHERE a.patient_id = :id AND o.created_at = a.occurred_at
                """).bind("id", id).map(r -> r.get("c", Long.class)).one().block();
        assertThat(matches).isEqualTo(3L);
    }

    @Test
    void rejectedRequestsCreateNoOutboxRow() {
        UUID id = register();
        move(id, "REGISTRADO").expectStatus().isEqualTo(409);
        patch(id, "{\"estado\":\"FOO\"}").expectStatus().isBadRequest();

        assertThat(outboxRows(id)).isEqualTo(1);
        assertThat(version(id)).isEqualTo(1);
    }

    @Test
    void outboxFailureRollsBackTransition() {
        UUID id = register();
        doReturn(Mono.error(new IllegalStateException("fallo simulado"))).when(outboxPort).append(any());

        move(id, "EN_CONSULTA").expectStatus().is5xxServerError();

        assertThat(outboxRows(id)).isEqualTo(1);
        assertThat(version(id)).isEqualTo(1);
        String status = db.sql("SELECT status FROM patient WHERE id = :id").bind("id", id)
                .map(r -> r.get("status", String.class)).one().block();
        assertThat(status).isEqualTo("REGISTRADO");
    }

    @Test
    void brokerDownKeepsApiAvailableAndEventIsDeliveredAfterRecovery() {
        var docker = KAFKA.getDockerClient();
        docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
        UUID id;
        try {
            long start = System.nanoTime();
            id = register();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
            assertThat(outboxRows(id)).isEqualTo(1);
            assertThat(unpublishedRows(id)).isEqualTo(1);
        } finally {
            docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }

        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500))
                .until(() -> unpublishedRows(id) == 0);
        awaitEvents(id, 1);
        assertThat(eventsOf(id)).hasSize(1);
        Long clean = db.sql("""
                SELECT count(*) AS c FROM outbox_event
                WHERE aggregate_id = :id AND attempts = 0 AND dead_lettered_at IS NULL
                """).bind("id", id).map(r -> r.get("c", Long.class)).one().block();
        assertThat(clean).isEqualTo(1L);
    }
}
