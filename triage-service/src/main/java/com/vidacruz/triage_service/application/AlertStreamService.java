package com.vidacruz.triage_service.application;

import com.vidacruz.triage_service.domain.MedicalAlert;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Genera el flujo de alertas de UNA conexión leyendo la tabla (única fuente de verdad, ADR-0002) con polling
 * acotado. Cada conexión tiene su propio cursor; no hay Sink ni cola compartida.
 *
 * <p>El drenaje respeta la demanda downstream: el siguiente lote se consulta solo cuando el anterior se
 * entregó por completo (el flujo del lote respeta backpressure y el repeat espera su finalización).
 *
 * <p>Un lote lleno se repite sin esperar; uno incompleto espera {@code pollInterval}. Un error de la base se
 * propaga y termina el flujo: el cliente se reconecta con {@code Last-Event-ID} y no pierde alertas.
 */
public class AlertStreamService {

    private final AlertRepositoryPort alerts;
    private final Duration pollInterval;
    private final int batchSize;

    public AlertStreamService(AlertRepositoryPort alerts, Duration pollInterval, int batchSize) {
        this.alerts = alerts;
        this.pollInterval = pollInterval;
        this.batchSize = batchSize;
    }

    /** @param lastEventId cursor desde el cual entregar (exclusivo); null = solo alertas nuevas. */
    public Flux<MedicalAlert> stream(Long lastEventId) {
        // Un cursor mayor al máximo (cliente obsoleto) se recorta al máximo para no quedar mudo para siempre.
        Mono<Long> start = lastEventId != null
                ? alerts.findMaxCursor().map(max -> Math.min(lastEventId, max))
                : alerts.findMaxCursor();
        return start.flatMapMany(this::poll);
    }

    private Flux<MedicalAlert> poll(long startCursor) {
        AtomicLong cursor = new AtomicLong(startCursor);
        AtomicBoolean full = new AtomicBoolean();
        return Flux.defer(() -> alerts.findAfter(cursor.get(), batchSize).collectList()
                        .flatMapMany(batch -> deliver(batch, cursor, full)))
                .repeatWhen(companion -> companion.concatMap(
                        n -> full.get() ? Mono.just(n) : Mono.delay(pollInterval)));
    }

    private Flux<MedicalAlert> deliver(List<MedicalAlert> batch, AtomicLong cursor, AtomicBoolean full) {
        full.set(batch.size() >= batchSize);
        if (!batch.isEmpty()) {
            cursor.set(batch.get(batch.size() - 1).deliveryCursor());
        }
        return Flux.fromIterable(batch);
    }
}
