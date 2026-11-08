package com.vidacruz.triage_service.application;

import com.vidacruz.triage_service.domain.Patient;
import com.vidacruz.triage_service.domain.PatientStatus;
import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface PatientRepositoryPort {

    /** Versión nueva del agregado y flag de escalamiento, leídos de la fila actualizada. */
    record StatusUpdate(long aggregateVersion, boolean escalated) {
    }

    Mono<Patient> save(Patient patient);

    Mono<Patient> findById(UUID id);

    /**
     * Actualiza el estado solo si sigue siendo el esperado e incrementa la versión del agregado en el
     * mismo UPDATE. Vacío si no afectó ninguna fila.
     */
    Mono<StatusUpdate> updateStatus(UUID id, PatientStatus expected, PatientStatus next, Instant changedAt);
}
