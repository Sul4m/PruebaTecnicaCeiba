package com.vidacruz.triage_service.domain;

public enum PatientStatus {
    REGISTRADO,
    EN_CONSULTA,
    ALTA;

    /** Transiciones permitidas: REGISTRADO a EN_CONSULTA o ALTA, y EN_CONSULTA a ALTA. */
    public boolean canTransitionTo(PatientStatus target) {
        return switch (this) {
            case REGISTRADO -> target == EN_CONSULTA || target == ALTA;
            case EN_CONSULTA -> target == ALTA;
            case ALTA -> false;
        };
    }
}
