package com.vidacruz.triage_service.application;

import com.vidacruz.triage_service.domain.AuditEvent;
import com.vidacruz.triage_service.domain.Patient;
import com.vidacruz.triage_service.domain.PatientStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

public class ChangePatientStatusUseCase {

    /** Paciente ya actualizado y el estado previo. */
    public record Change(Patient patient, PatientStatus previous) {
    }

    private final PatientRepositoryPort patients;
    private final AuditRepositoryPort audits;
    private final TransactionalOperator tx;
    private final Clock clock;

    public ChangePatientStatusUseCase(PatientRepositoryPort patients, AuditRepositoryPort audits,
            TransactionalOperator tx, Clock clock) {
        this.patients = patients;
        this.audits = audits;
        this.tx = tx;
        this.clock = clock;
    }

    public Mono<Change> change(UUID id, PatientStatus requested) {
        return Mono.defer(() -> {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            return patients.findById(id)
                    .switchIfEmpty(Mono.error(() -> new PatientNotFoundException(id)))
                    .flatMap(current -> {
                        PatientStatus from = current.status();
                        if (!from.canTransitionTo(requested)) {
                            return Mono.error(new InvalidStatusTransitionException(from, requested));
                        }
                        return patients.updateStatus(id, from, requested, now)
                                .flatMap(updated -> updated
                                        ? audits.save(new AuditEvent(UUID.randomUUID(), id, from, requested,
                                                        eventType(requested), now))
                                                .thenReturn(new Change(current.withStatus(requested, now), from))
                                        // Carrera perdida: releer para informar el estado real.
                                        : patients.findById(id)
                                                .switchIfEmpty(Mono.error(() -> new PatientNotFoundException(id)))
                                                .flatMap(latest -> Mono.<Change>error(
                                                        InvalidStatusTransitionException.lostRace(latest.status(),
                                                                requested))));
                    })
                    .as(tx::transactional);
        });
    }

    private static String eventType(PatientStatus target) {
        return switch (target) {
            case EN_CONSULTA -> AuditEvent.PATIENT_IN_CONSULTATION;
            case ALTA -> AuditEvent.PATIENT_DISCHARGED;
            case REGISTRADO -> throw new IllegalArgumentException("Sin evento de transición hacia " + target);
        };
    }
}
