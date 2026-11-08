package com.vidacruz.triage_service.application;

import com.vidacruz.triage_service.domain.Patient;
import com.vidacruz.triage_service.domain.PatientStatus;
import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface PatientRepositoryPort {
    Mono<Patient> save(Patient patient);

    Mono<Patient> findById(UUID id);

    /** Actualiza el estado solo si sigue siendo el esperado. Devuelve true si afectó una fila. */
    Mono<Boolean> updateStatus(UUID id, PatientStatus expected, PatientStatus next, Instant changedAt);
}
