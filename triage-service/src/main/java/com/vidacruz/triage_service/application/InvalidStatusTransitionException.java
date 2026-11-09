package com.vidacruz.triage_service.application;

import com.vidacruz.triage_service.domain.PatientStatus;

public class InvalidStatusTransitionException extends RuntimeException {

    private final PatientStatus current;
    private final PatientStatus requested;

    private final boolean concurrent;

    public InvalidStatusTransitionException(PatientStatus current, PatientStatus requested) {
        this(current, requested, false,
                "No se puede cambiar el estado de " + current + " a " + requested);
    }

    private InvalidStatusTransitionException(PatientStatus current, PatientStatus requested,
            boolean concurrent, String message) {
        super(message);
        this.current = current;
        this.requested = requested;
        this.concurrent = concurrent;
    }

    /** La transición era válida al leer, pero otro cambio concurrente ganó la actualización condicional. */
    public static InvalidStatusTransitionException lostRace(PatientStatus current, PatientStatus requested) {
        return new InvalidStatusTransitionException(current, requested, true,
                "El estado del paciente cambió concurrentemente a " + current + "; no se aplicó " + requested);
    }

    public boolean concurrent() {
        return concurrent;
    }

    public PatientStatus current() {
        return current;
    }

    public PatientStatus requested() {
        return requested;
    }
}
