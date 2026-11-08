package com.vidacruz.triage_service.application;

import com.vidacruz.triage_service.domain.Patient;
import java.util.UUID;
import reactor.core.publisher.Mono;

public class GetPatientUseCase {

    private final PatientRepositoryPort patients;

    public GetPatientUseCase(PatientRepositoryPort patients) {
        this.patients = patients;
    }

    public Mono<Patient> get(UUID id) {
        return patients.findById(id)
                .switchIfEmpty(Mono.error(() -> new PatientNotFoundException(id)));
    }
}
