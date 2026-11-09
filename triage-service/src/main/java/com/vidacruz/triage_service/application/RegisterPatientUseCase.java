package com.vidacruz.triage_service.application;

import com.vidacruz.triage_service.domain.AuditEvent;
import com.vidacruz.triage_service.domain.Patient;
import com.vidacruz.triage_service.domain.PatientStatus;
import com.vidacruz.triage_service.domain.TriageClassifier;
import com.vidacruz.triage_service.domain.TriageEventEnvelope;
import com.vidacruz.triage_service.domain.TriageLevel;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

public class RegisterPatientUseCase {

    private final PatientRepositoryPort patients;
    private final AuditRepositoryPort audits;
    private final OutboxPort outbox;
    private final ClassificationMetricsPort metrics;
    private final TransactionalOperator tx;
    private final Clock clock;
    private final SlaPolicy sla;

    public RegisterPatientUseCase(PatientRepositoryPort patients, AuditRepositoryPort audits,
            OutboxPort outbox, ClassificationMetricsPort metrics, TransactionalOperator tx, Clock clock,
            SlaPolicy sla) {
        this.patients = patients;
        this.audits = audits;
        this.outbox = outbox;
        this.metrics = metrics;
        this.tx = tx;
        this.clock = clock;
        this.sla = sla;
    }

    public Mono<Patient> register(RegisterPatientCommand command) {
        return Mono.defer(() -> {
            // Microsegundos: precisión de timestamptz, para que la respuesta coincida con lo persistido.
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            TriageLevel level = TriageClassifier.classify(command.vitalSigns(), command.chiefComplaint());
            Patient patient = new Patient(UUID.randomUUID(), command.fullName(), command.age(),
                    command.chiefComplaint(), command.vitalSigns(), level, sla.slaMinutes(level),
                    PatientStatus.REGISTRADO, false, now, now, 1L);
            AuditEvent audit = new AuditEvent(UUID.randomUUID(), patient.id(), null,
                    PatientStatus.REGISTRADO, AuditEvent.PATIENT_REGISTERED, now);
            TriageEventEnvelope event = TriageEventEnvelope.of(AuditEvent.PATIENT_REGISTERED, patient.id(), 1L,
                    level, PatientStatus.REGISTRADO, false, now);
            return patients.save(patient)
                    .then(audits.save(audit))
                    .then(outbox.append(event))
                    .thenReturn(patient)
                    .as(tx::transactional)
                    .doOnNext(saved -> metrics.recordClassification(saved.level()));
        });
    }
}
