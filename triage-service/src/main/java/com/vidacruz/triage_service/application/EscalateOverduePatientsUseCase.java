package com.vidacruz.triage_service.application;

import com.vidacruz.triage_service.domain.AuditEvent;
import com.vidacruz.triage_service.domain.MedicalAlert;
import com.vidacruz.triage_service.domain.PatientStatus;
import com.vidacruz.triage_service.domain.TriageEventEnvelope;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Escala por SLA a los pacientes {@code REGISTRADO} vencidos, una sola vez por paciente. Cada lote es una
 * transacción: UPDATE condicional + auditoría + outbox + alerta médica.
 */
public class EscalateOverduePatientsUseCase {

    private static final Logger log = LoggerFactory.getLogger(EscalateOverduePatientsUseCase.class);

    private final PatientRepositoryPort patients;
    private final AuditRepositoryPort audits;
    private final OutboxPort outbox;
    private final AlertRepositoryPort alerts;
    private final EscalationMetricsPort metrics;
    private final TransactionalOperator tx;
    private final Clock clock;
    private final int batchSize;

    public EscalateOverduePatientsUseCase(PatientRepositoryPort patients, AuditRepositoryPort audits,
            OutboxPort outbox, AlertRepositoryPort alerts, EscalationMetricsPort metrics,
            TransactionalOperator tx, Clock clock, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize debe ser positivo");
        }
        this.patients = patients;
        this.audits = audits;
        this.outbox = outbox;
        this.alerts = alerts;
        this.metrics = metrics;
        this.tx = tx;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /** Escala todos los vencidos en lotes secuenciales; devuelve el total escalado en el ciclo. */
    public Mono<Long> escalateAll() {
        return Mono.defer(this::escalateBatch)
                .repeat()
                .takeUntil(count -> count < batchSize)
                .reduce(0L, (total, count) -> total + count);
    }

    /** Escala un lote (como máximo {@code batchSize}) en una transacción; devuelve cuántos escaló. */
    public Mono<Integer> escalateBatch() {
        return Mono.defer(() -> {
            // Microsegundos: precisión de timestamptz, igual que el resto de los casos de uso.
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            return patients.escalateOverdue(now, batchSize)
                    // Acotado por LIMIT: se materializa el lote para no intercalar sentencias sobre el stream.
                    .collectList()
                    .flatMap(escalated -> Flux.fromIterable(escalated)
                            .concatMap(p -> audits.save(new AuditEvent(UUID.randomUUID(), p.id(),
                                            PatientStatus.REGISTRADO, PatientStatus.REGISTRADO,
                                            AuditEvent.PATIENT_ESCALATED, now))
                                    .then(Mono.defer(() -> outbox.append(TriageEventEnvelope.of(
                                            AuditEvent.PATIENT_ESCALATED, p.id(), p.aggregateVersion(),
                                            p.level(), p.status(), true, now)))))
                            // Lock del contador al final de la transacción: serialización breve.
                            .thenMany(Flux.defer(() -> escalated.isEmpty() ? Flux.<MedicalAlert>empty()
                                    : alerts.appendAll(escalated, now)))
                            .collectList()
                            .map(created -> new BatchResult(escalated, created)))
                    .as(tx::transactional)
                    .doOnNext(this::record)
                    .map(r -> r.escalated().size());
        });
    }

    private record BatchResult(List<PatientRepositoryPort.EscalatedPatient> escalated,
            List<MedicalAlert> alerts) {
    }

    private void record(BatchResult result) {
        // Tras el commit: solo cuenta y registra lo que quedó persistido.
        result.escalated().forEach(p -> metrics.recordEscalation(p.level()));
        if (!result.alerts().isEmpty()) {
            log.info("Alertas creadas count={} cursorDesde={} cursorHasta={}", result.alerts().size(),
                    result.alerts().get(0).deliveryCursor(),
                    result.alerts().get(result.alerts().size() - 1).deliveryCursor());
        }
    }
}
