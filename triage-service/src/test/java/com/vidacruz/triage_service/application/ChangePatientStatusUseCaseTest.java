package com.vidacruz.triage_service.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidacruz.triage_service.domain.AuditEvent;
import com.vidacruz.triage_service.domain.Patient;
import com.vidacruz.triage_service.domain.PatientStatus;
import com.vidacruz.triage_service.domain.TriageEventEnvelope;
import com.vidacruz.triage_service.domain.TriageLevel;
import com.vidacruz.triage_service.domain.VitalSigns;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ChangePatientStatusUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-08T10:15:30.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-11-08T10:15:30.123456Z");

    private final UUID id = UUID.randomUUID();
    private PatientRepositoryPort patients;
    private AuditRepositoryPort audits;
    private OutboxPort outbox;
    private ChangePatientStatusUseCase useCase;

    @BeforeEach
    void setUp() {
        patients = mock(PatientRepositoryPort.class);
        audits = mock(AuditRepositoryPort.class);
        outbox = mock(OutboxPort.class);
        when(outbox.append(any())).thenReturn(Mono.empty());
        TransactionalOperator tx = mock(TransactionalOperator.class);
        when(tx.transactional(any(Mono.class))).thenAnswer(i -> i.getArgument(0));
        useCase = new ChangePatientStatusUseCase(patients, audits, outbox, tx, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Patient patient(PatientStatus status) {
        return new Patient(id, "Luis Gómez", 40, "Renovación de recetario",
                new VitalSigns(70, 115, 75, 99, new BigDecimal("36.4"), 0, 15), TriageLevel.NO_URGENTE_2, 120,
                status, false, NOW_MICROS, NOW_MICROS, 1L);
    }

    @Test
    void lostRaceReportsRereadStateAndSkipsAudit() {
        when(patients.findById(id)).thenReturn(Mono.just(patient(PatientStatus.REGISTRADO)),
                Mono.just(patient(PatientStatus.EN_CONSULTA)));
        when(patients.updateStatus(eq(id), eq(PatientStatus.REGISTRADO), eq(PatientStatus.ALTA), any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(useCase.change(id, PatientStatus.ALTA))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(InvalidStatusTransitionException.class);
                    InvalidStatusTransitionException ex = (InvalidStatusTransitionException) e;
                    assertThat(ex.current()).isEqualTo(PatientStatus.EN_CONSULTA);
                    assertThat(ex.requested()).isEqualTo(PatientStatus.ALTA);
                    assertThat(ex.concurrent()).isTrue();
                    assertThat(ex.getMessage()).isEqualTo(
                            "El estado del paciente cambió concurrentemente a EN_CONSULTA; no se aplicó ALTA");
                })
                .verify();

        verify(audits, never()).save(any());
        verify(outbox, never()).append(any());
    }

    @Test
    void happyPathSavesAuditWithTruncatedClockInstant() {
        when(patients.findById(id)).thenReturn(Mono.just(patient(PatientStatus.REGISTRADO)));
        when(patients.updateStatus(eq(id), eq(PatientStatus.REGISTRADO), eq(PatientStatus.EN_CONSULTA),
                eq(NOW_MICROS))).thenReturn(Mono.just(new PatientRepositoryPort.StatusUpdate(2L, false)));
        when(audits.save(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));

        StepVerifier.create(useCase.change(id, PatientStatus.EN_CONSULTA))
                .assertNext(c -> {
                    assertThat(c.previous()).isEqualTo(PatientStatus.REGISTRADO);
                    assertThat(c.patient().status()).isEqualTo(PatientStatus.EN_CONSULTA);
                    assertThat(c.patient().statusChangedAt()).isEqualTo(NOW_MICROS);
                    assertThat(c.patient().aggregateVersion()).isEqualTo(2L);
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audits).save(captor.capture());
        AuditEvent audit = captor.getValue();
        assertThat(audit.patientId()).isEqualTo(id);
        assertThat(audit.previousStatus()).isEqualTo(PatientStatus.REGISTRADO);
        assertThat(audit.newStatus()).isEqualTo(PatientStatus.EN_CONSULTA);
        assertThat(audit.eventType()).isEqualTo(AuditEvent.PATIENT_IN_CONSULTATION);
        assertThat(audit.occurredAt()).isEqualTo(NOW_MICROS);

        ArgumentCaptor<TriageEventEnvelope> eventCaptor = ArgumentCaptor.forClass(TriageEventEnvelope.class);
        verify(outbox).append(eventCaptor.capture());
        TriageEventEnvelope event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo("paciente_en_consulta");
        assertThat(event.aggregateId()).isEqualTo(id);
        assertThat(event.aggregateVersion()).isEqualTo(2L);
        assertThat(event.occurredAt()).isEqualTo(audit.occurredAt());
        assertThat(event.payload().estado()).isEqualTo("EN_CONSULTA");
        assertThat(event.payload().nivel()).isEqualTo("NO_URGENTE_2");
    }
}
