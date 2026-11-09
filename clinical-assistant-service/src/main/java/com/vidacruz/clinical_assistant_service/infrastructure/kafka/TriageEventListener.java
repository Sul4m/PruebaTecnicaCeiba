package com.vidacruz.clinical_assistant_service.infrastructure.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.vidacruz.clinical_assistant_service.application.ProyeccionUrgenciasService;
import com.vidacruz.clinical_assistant_service.domain.EventoInvalidoException;
import com.vidacruz.clinical_assistant_service.domain.ProyeccionNoDisponibleException;
import com.vidacruz.clinical_assistant_service.domain.ResultadoProyeccion;
import com.vidacruz.clinical_assistant_service.infrastructure.config.ProjectionProperties;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * Consumidor reactivo de {@code triaje.eventos}. El listener devuelve un {@link Mono}: Spring Kafka confirma el
 * offset (AckMode MANUAL) solo cuando el Mono completa, es decir, despues de que Redis aplico el cambio o de que
 * el mensaje quedo publicado en el DLT. Un mensaje invalido va directo al DLT; un fallo deterministico se reintenta
 * con backoff acotado ({@code maxAttempts}) y luego va al DLT. Un fallo transitorio de Redis NUNCA va al DLT:
 * el Mono falla sin ack y el contenedor reentrega con backoff. Si ni el DLT acepta el mensaje, el Mono falla,
 * el offset NO se confirma y el manejador de errores del contenedor reintenta indefinidamente (nunca se descarta).
 * Los logs no incluyen el contenido del mensaje.
 */
@Component
@ConditionalOnProperty(prefix = "assistant.projection", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TriageEventListener {

    private static final Logger log = LoggerFactory.getLogger(TriageEventListener.class);

    private final TriageEventParser parser;
    private final ProyeccionUrgenciasService service;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ProjectionProperties props;
    private final MeterRegistry meters;

    public TriageEventListener(TriageEventParser parser, ProyeccionUrgenciasService service,
            KafkaTemplate<String, String> kafkaTemplate, ProjectionProperties props, MeterRegistry meters) {
        this.parser = parser;
        this.service = service;
        this.kafkaTemplate = kafkaTemplate;
        this.props = props;
        this.meters = meters;
    }

    @KafkaListener(id = "urgencias-projection", topics = "${assistant.projection.topic}",
            groupId = "${assistant.projection.group-id}")
    public Mono<Void> onMessage(ConsumerRecord<String, String> record) {
        return Mono.fromSupplier(() -> parser.parse(record.value()))
                .flatMap(evento -> service.aplicar(evento)
                        // Solo fallos deterministicos se reintentan aqui; los transitorios (Redis caido) se propagan.
                        .retryWhen(Retry.backoff(props.maxAttempts() - 1L, props.retryBackoff())
                                .maxBackoff(props.retryMaxBackoff())
                                .filter(t -> !(t instanceof ProyeccionNoDisponibleException))
                                .doBeforeRetry(s -> log.warn("Fallo al aplicar evento; reintento {}",
                                        s.totalRetries() + 1))))
                .doOnNext(this::registrar)
                .then()
                .onErrorResume(ex -> deadLetter(record, ex));
    }

    private void registrar(ResultadoProyeccion resultado) {
        meters.counter("assistant.projection.events", "result", resultado.name().toLowerCase()).increment();
        if (resultado == ResultadoProyeccion.APLICADO_CON_SALTO) {
            meters.counter("assistant.projection.version_gaps").increment();
            log.warn("Salto de version detectado: se aplico el snapshot completo");
        }
    }

    private Mono<Void> deadLetter(ConsumerRecord<String, String> record, Throwable ex) {
        Throwable cause = Exceptions.isRetryExhausted(ex) && ex.getCause() != null ? ex.getCause() : ex;
        if (cause instanceof ProyeccionNoDisponibleException) {
            // Transitorio (Redis no disponible): jamas al DLT. Sin ack; el manejador del contenedor reentrega.
            log.warn("Redis no disponible; el mensaje se reentregara (sin ack)");
            return Mono.error(cause);
        }
        String motivo = cause instanceof EventoInvalidoException e ? e.motivo() : "proyeccion_fallida";
        ProducerRecord<String, String> dlt = new ProducerRecord<>(props.dltTopic(), null, record.key(),
                record.value());
        if (record.value() == null) {
            dlt.headers().add(header("x-dlt-null-value", "true"));
        }
        dlt.headers().add(header("x-dlt-reason", motivo));
        dlt.headers().add(header("x-dlt-exception", cause.getClass().getName()));
        dlt.headers().add(header("x-dlt-original-topic", record.topic()));
        dlt.headers().add(header("x-dlt-original-partition", Integer.toString(record.partition())));
        dlt.headers().add(header("x-dlt-original-offset", Long.toString(record.offset())));
        return Mono.fromFuture(() -> kafkaTemplate.send(dlt))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(30))
                .doOnSuccess(r -> {
                    meters.counter("assistant.projection.dead_letter", "reason", motivo).increment();
                    log.error("Evento enviado al DLT: motivo={} topic={} partition={} offset={}", motivo,
                            record.topic(), record.partition(), record.offset());
                })
                .then();
    }

    private static RecordHeader header(String name, String value) {
        return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
