package com.vidacruz.clinical_assistant_service.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.clinical_assistant_service.application.ProyeccionUrgenciasService;
import com.vidacruz.clinical_assistant_service.domain.EventoTriaje;
import com.vidacruz.clinical_assistant_service.domain.ProyeccionNoDisponibleException;
import com.vidacruz.clinical_assistant_service.domain.ResultadoProyeccion;
import com.vidacruz.clinical_assistant_service.infrastructure.config.ProjectionProperties;
import com.vidacruz.clinical_assistant_service.support.TriageEvents;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Sin Docker. "Sin ack" == el Mono del listener termina en error (el contenedor solo confirma al completar). */
class TriageEventListenerTest {

    private final ProyeccionUrgenciasService service = mock(ProyeccionUrgenciasService.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private TriageEventListener listener;
    private String patient;
    private String json;

    @BeforeEach
    void setUp() {
        var props = new ProjectionProperties(true, "triaje.eventos", "triaje.eventos.dlt", "g", 3,
                Duration.ofMillis(1), Duration.ofMillis(2));
        listener = new TriageEventListener(new TriageEventParser(new ObjectMapper()), service, kafka, props,
                new SimpleMeterRegistry());
        patient = UUID.randomUUID().toString();
        json = TriageEvents.registrado(patient, 1, "CRITICO");
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("triaje.eventos", 2, 41L, patient, value);
    }

    @SuppressWarnings("unchecked")
    private void dltSendOk() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(
                (SendResult<String, String>) null));
    }

    private static String header(ProducerRecord<String, String> r, String name) {
        return new String(r.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    @Test
    void appliedEventCompletesWithoutTouchingTheDlt() {
        when(service.aplicar(any())).thenReturn(Mono.just(ResultadoProyeccion.APLICADO));
        StepVerifier.create(listener.onMessage(record(json))).verifyComplete();
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void transientRedisFailureIsNeverDeadLetteredAndIsNotAcked() {
        when(service.aplicar(any())).thenReturn(Mono.error(new ProyeccionNoDisponibleException(new RuntimeException())));
        StepVerifier.create(listener.onMessage(record(json)))
                .expectError(ProyeccionNoDisponibleException.class).verify();
        verify(kafka, never()).send(any(ProducerRecord.class));
        verify(service, times(1)).aplicar(any(EventoTriaje.class)); // sin reintentos internos: reentrega el contenedor
    }

    @Test
    @SuppressWarnings("unchecked")
    void deterministicFailureRetriesBoundedThenGoesToDltWithOriginHeaders() {
        var subscriptions = new java.util.concurrent.atomic.AtomicInteger();
        when(service.aplicar(any())).thenReturn(Mono.defer(() -> {
            subscriptions.incrementAndGet();
            return Mono.error(new IllegalStateException("codigo desconocido"));
        }));
        dltSendOk();

        StepVerifier.create(listener.onMessage(record(json))).verifyComplete();

        assertThat(subscriptions.get()).isEqualTo(3); // max-attempts = intento inicial + 2 reintentos
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        ProducerRecord<String, String> dlt = captor.getValue();
        assertThat(dlt.topic()).isEqualTo("triaje.eventos.dlt");
        assertThat(dlt.key()).isEqualTo(patient);
        assertThat(dlt.value()).isEqualTo(json);
        assertThat(header(dlt, "x-dlt-reason")).isEqualTo("proyeccion_fallida");
        assertThat(header(dlt, "x-dlt-exception")).isEqualTo(IllegalStateException.class.getName());
        assertThat(header(dlt, "x-dlt-original-topic")).isEqualTo("triaje.eventos");
        assertThat(header(dlt, "x-dlt-original-partition")).isEqualTo("2");
        assertThat(header(dlt, "x-dlt-original-offset")).isEqualTo("41");
    }

    @Test
    @SuppressWarnings("unchecked")
    void invalidMessageGoesStraightToDltWithoutCallingRedis() {
        dltSendOk();
        StepVerifier.create(listener.onMessage(record("no es json"))).verifyComplete();
        verify(service, never()).aplicar(any());
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        assertThat(header(captor.getValue(), "x-dlt-reason")).isEqualTo("invalido");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tombstoneIsDeadLetteredExplicitly() {
        dltSendOk();
        StepVerifier.create(listener.onMessage(record(null))).verifyComplete();
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        assertThat(captor.getValue().value()).isNull();
        assertThat(header(captor.getValue(), "x-dlt-null-value")).isEqualTo("true");
        assertThat(header(captor.getValue(), "x-dlt-reason")).isEqualTo("invalido");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedDltSendIsNotAcked() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(
                new IllegalStateException("broker caido")));
        StepVerifier.create(listener.onMessage(record("no es json"))).expectError().verify();
    }
}
