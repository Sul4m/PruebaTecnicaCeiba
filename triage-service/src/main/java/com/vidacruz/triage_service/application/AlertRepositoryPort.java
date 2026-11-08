package com.vidacruz.triage_service.application;

import com.vidacruz.triage_service.domain.MedicalAlert;
import java.time.Instant;
import java.util.List;
import reactor.core.publisher.Flux;

public interface AlertRepositoryPort {

    /**
     * Toma el lock del contador singleton (una vez por lote), asigna cursores consecutivos en el orden de la
     * lista e inserta las alertas. Debe ejecutarse dentro de la transacción del llamador: el lock se mantiene
     * hasta el commit.
     */
    Flux<MedicalAlert> appendAll(List<PatientRepositoryPort.EscalatedPatient> escalated, Instant createdAt);

    /** Alertas con cursor mayor a {@code cursor}, ascendentes, hasta {@code limit}. */
    Flux<MedicalAlert> findAfter(long cursor, int limit);
}
