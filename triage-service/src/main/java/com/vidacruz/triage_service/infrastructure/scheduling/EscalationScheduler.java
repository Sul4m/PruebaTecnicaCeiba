package com.vidacruz.triage_service.infrastructure.scheduling;

import com.vidacruz.triage_service.application.EscalateOverduePatientsUseCase;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Ciclo periódico reactivo (sin {@code block()}) que escala pacientes vencidos; concurrencia 1 por instancia. */
@Component
@ConditionalOnProperty(prefix = "triage.escalation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EscalationScheduler {

    private static final Logger log = LoggerFactory.getLogger(EscalationScheduler.class);
    static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final EscalateOverduePatientsUseCase useCase;
    private final EscalationProperties props;
    private final AtomicInteger failureStreak = new AtomicInteger();

    private volatile Disposable loop;
    private volatile Scheduler scheduler;

    public EscalationScheduler(EscalateOverduePatientsUseCase useCase, EscalationProperties props) {
        this.useCase = useCase;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (loop != null) {
            return;
        }
        scheduler = Schedulers.newSingle("escalation-scheduler", true);
        loop = Mono.defer(this::runCycle)
                .flatMap(ok -> Mono.delay(nextDelay(ok), scheduler))
                // Cualquier error del pipeline (no solo del ciclo) se registra y el bucle continúa.
                .onErrorResume(e -> {
                    log.error("Fallo inesperado en el bucle de escalamiento", e);
                    return Mono.delay(props.interval(), scheduler);
                })
                .repeat()
                .subscribeOn(scheduler)
                .subscribe(v -> { }, e -> log.error("El bucle de escalamiento terminó", e));
    }

    @PreDestroy
    public synchronized void stop() {
        if (loop != null) {
            loop.dispose();
            loop = null;
        }
        if (scheduler != null) {
            scheduler.dispose();
            scheduler = null;
        }
    }

    /** Un ciclo completo; un error se registra y devuelve {@code false} para que el bucle siga vivo. */
    Mono<Boolean> runCycle() {
        return useCase.escalateAll()
                .doOnNext(total -> {
                    if (total > 0) {
                        log.info("Escalamiento por SLA: {} paciente(s) escalado(s)", total);
                    }
                })
                .thenReturn(true)
                .onErrorResume(e -> {
                    log.warn("Ciclo de escalamiento falló", e);
                    return Mono.just(false);
                });
    }

    Duration nextDelay(boolean success) {
        if (success) {
            failureStreak.set(0);
            return props.interval();
        }
        int streak = Math.min(failureStreak.getAndIncrement(), 20);
        Duration cap = props.interval().compareTo(MAX_BACKOFF) > 0 ? props.interval() : MAX_BACKOFF;
        long factor = 1L << streak;
        // Saturante: si el producto superaría el tope (o desbordaría), se devuelve el tope sin multiplicar.
        if (props.interval().toMillis() > cap.toMillis() / factor) {
            return cap;
        }
        return props.interval().multipliedBy(factor);
    }
}
