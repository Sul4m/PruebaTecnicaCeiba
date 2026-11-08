package com.vidacruz.triage_service.infrastructure.web;

import com.vidacruz.triage_service.application.AlertStreamMetricsPort;
import com.vidacruz.triage_service.application.AlertStreamService;
import com.vidacruz.triage_service.application.InvalidReportParameterException;
import com.vidacruz.triage_service.infrastructure.config.AlertStreamProperties;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@RestController
class AlertStreamController {

    private static final Logger log = LoggerFactory.getLogger(AlertStreamController.class);

    private static final Pattern LAST_EVENT_ID = Pattern.compile("^[0-9]{1,18}$");

    private final AlertStreamService service;
    private final AlertStreamProperties props;
    private final AlertStreamMetricsPort metrics;

    AlertStreamController(AlertStreamService service, AlertStreamProperties props,
            AlertStreamMetricsPort metrics) {
        this.service = service;
        this.props = props;
        this.metrics = metrics;
    }

    @GetMapping(path = "/triage/alertas/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<AlertaResponse>> stream(
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        Long from = parse(lastEventId);

        // Buffer acotado solo para el heartbeat: un cliente que no pide nada lo desborda y la conexión se cancela
        // (log + cierre limpio). Las alertas no pasan por buffer: el drenaje sigue la demanda del cliente
        // (prefetch 1 en el merge).
        return Flux.defer(() -> {
            Sinks.Empty<Void> overflow = Sinks.empty();
            Flux<ServerSentEvent<AlertaResponse>> alerts = service.stream(from)
                    .map(a -> ServerSentEvent.builder(AlertaResponse.from(a))
                            .id(Long.toString(a.deliveryCursor()))
                            .event("alerta")
                            .build());
            Flux<ServerSentEvent<AlertaResponse>> heartbeat = Flux.interval(props.heartbeatInterval())
                    .map(i -> ServerSentEvent.<AlertaResponse>builder().comment("heartbeat").build())
                    .onBackpressureBuffer(props.bufferSize(), dropped -> overflow.tryEmitEmpty(),
                            BufferOverflowStrategy.DROP_LATEST);
            return Flux.merge(Flux.just(alerts, heartbeat), 2, 1)
                    .takeUntilOther(overflow.asMono().doOnTerminate(() -> log.warn(
                            "Cliente SSE lento: buffer de {} desbordado, conexión cancelada", props.bufferSize())));
        })
                .doOnSubscribe(s -> {
                    metrics.connectionOpened();
                    log.info("Conexión SSE de alertas abierta desde cursor={}", from == null ? "nuevas" : from);
                })
                .doFinally(signal -> {
                    metrics.connectionClosed();
                    log.info("Conexión SSE de alertas cerrada ({})", signal);
                });
    }

    private static Long parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (LAST_EVENT_ID.matcher(v).matches()) {
            return Long.parseLong(v);
        }
        throw new InvalidReportParameterException("Last-Event-ID", "debe ser un entero mayor o igual a 0");
    }
}
