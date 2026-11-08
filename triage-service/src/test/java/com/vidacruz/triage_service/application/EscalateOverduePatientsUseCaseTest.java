package com.vidacruz.triage_service.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidacruz.triage_service.domain.AuditEvent;
import com.vidacruz.triage_service.domain.PatientStatus;
import com.vidacruz.triage_service.domain.TriageEventEnvelope;
import com.vidacruz.triage_service.domain.TriageLevel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class EscalateOverduePatientsUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-08T10:15:30.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-11-08T10:15:30.123456Z");

    private PatientRepositoryPort patients;
    private AuditRepositoryPort audits;
    private OutboxPort outbox;
    private EscalationMetricsPort metrics;
    private EscalateOverduePatientsUseCase useCase;

    @BeforeEach
    void setUp() {
        patients = mock(PatientRepositoryPort.class);
        audits = mock(AuditRepositoryPort.class);
        outbox = mock(OutboxPort.class);
        metrics = mock(EscalationMetricsPort.class);
        when(outbox.append(any())).thenReturn(Mono.empty());
        when(audits.save(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        TransactionalOperator tx = mock(TransactionalOperator.class);
        when(tx.transactional(any(Mono.class))).thenAnswer(i -> i.getArgument(0));
        useCase = new EscalateOverduePatientsUseCase(patients, audits, outbox, metrics, tx,
                Clock.fixed(NOW, ZoneOffset.UTC), 2);
    }

    private static PatientRepositoryPort.EscalatedPatient escalated(TriageLevel level, long version) {
        return new PatientRepositoryPort.EscalatedPatient(UUID.randomUUID(), level, PatientStatus.REGISTRADO,
                version);
    }

    @Test
    void noOverdueMeansNoAuditNoOutboxNoMetric() {
        when(patients.escalateOverdue(NOW_MICROS, 2)).thenReturn(Flux.empty());

        StepVerifier.create(useCase.escalateAll()).expectNext(0L).verifyComplete();

        verify(audits, never()).save(any());
        verify(outbox, never()).append(any());
        verify(metrics, never()).recordEscalation(any());
    }

    @Test
    void escalatedPatientGetsAuditOutboxAndMetric() {
        var p = escalated(TriageLevel.CRITICO, 2L);
        when(patients.escalateOverdue(NOW_MICROS, 2)).thenReturn(Flux.just(p));

        StepVerifier.create(useCase.escalateBatch()).expectNext(1).verifyComplete();

        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audits).save(audit.capture());
        assertThat(audit.getValue().patientId()).isEqualTo(p.id());
        assertThat(audit.getValue().previousStatus()).isEqualTo(PatientStatus.REGISTRADO);
        assertThat(audit.getValue().newStatus()).isEqualTo(PatientStatus.REGISTRADO);
        assertThat(audit.getValue().eventType()).isEqualTo(AuditEvent.PATIENT_ESCALATED);
        assertThat(audit.getValue().occurredAt()).isEqualTo(NOW_MICROS);

        ArgumentCaptor<TriageEventEnvelope> event = ArgumentCaptor.forClass(TriageEventEnvelope.class);
        verify(outbox).append(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("paciente_escalado");
        assertThat(event.getValue().aggregateVersion()).isEqualTo(2L);
        assertThat(event.getValue().payload().escalated()).isTrue();
        assertThat(event.getValue().payload().estado()).isEqualTo("REGISTRADO");
        assertThat(event.getValue().payload().nivel()).isEqualTo("CRITICO");
        verify(metrics).recordEscalation(TriageLevel.CRITICO);
    }

    @Test
    void fullBatchRepeatsUntilAPartialOneAndSumsTotal() {
        when(patients.escalateOverdue(NOW_MICROS, 2)).thenReturn(
                Flux.just(escalated(TriageLevel.URGENTE, 2L), escalated(TriageLevel.URGENTE, 2L)),
                Flux.just(escalated(TriageLevel.URGENTE_2, 2L)));

        StepVerifier.create(useCase.escalateAll()).expectNext(3L).verifyComplete();

        verify(patients, times(2)).escalateOverdue(eq(NOW_MICROS), eq(2));
    }

    @Test
    void auditFailurePropagatesAndSkipsOutboxAndMetric() {
        when(patients.escalateOverdue(NOW_MICROS, 2)).thenReturn(Flux.just(escalated(TriageLevel.CRITICO, 2L)));
        doReturn(Mono.error(new IllegalStateException("audit down"))).when(audits).save(any());

        StepVerifier.create(useCase.escalateBatch())
                .expectErrorMessage("audit down")
                .verify();

        verify(outbox, never()).append(any());
        verify(metrics, never()).recordEscalation(any());
    }
}
