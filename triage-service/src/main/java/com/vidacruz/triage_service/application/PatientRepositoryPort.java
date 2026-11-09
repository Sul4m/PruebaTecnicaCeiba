package com.vidacruz.triage_service.application;

import com.vidacruz.triage_service.domain.Patient;
import com.vidacruz.triage_service.domain.PatientStatus;
import com.vidacruz.triage_service.domain.TriageLevel;
import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PatientRepositoryPort {

    /** Versión nueva del agregado y flag de escalamiento, leídos de la fila actualizada. */
    record StatusUpdate(long aggregateVersion, boolean escalated) {
    }

    /** Paciente recién escalado, con la versión devuelta por el UPDATE. */
    record EscalatedPatient(UUID id, TriageLevel level, PatientStatus status, long aggregateVersion) {
    }

    Mono<Patient> save(Patient patient);

    Mono<Patient> findById(UUID id);

    /**
     * Actualiza el estado solo si sigue siendo el esperado e incrementa la versión del agregado en el
     * mismo UPDATE. Vacío si no afectó ninguna fila.
     */
    Mono<StatusUpdate> updateStatus(UUID id, PatientStatus expected, PatientStatus next, Instant changedAt);

    /**
     * Marca como escalados, en una sola sentencia, hasta {@code limit} pacientes {@code REGISTRADO} sin
     * escalar cuyo tiempo desde el registro supera estrictamente su SLA, saltando filas bloqueadas por otra
     * instancia. Debe ejecutarse dentro de la transacción del caso de uso.
     */
    Flux<EscalatedPatient> escalateOverdue(Instant now, int limit);
}
