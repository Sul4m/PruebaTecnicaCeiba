package com.vidacruz.triage_service.application;

import com.vidacruz.triage_service.domain.Patient;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface PatientRepositoryPort {
    Mono<Patient> save(Patient patient);

    Mono<Patient> findById(UUID id);
}
