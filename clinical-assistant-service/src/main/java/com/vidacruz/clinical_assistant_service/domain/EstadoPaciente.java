package com.vidacruz.clinical_assistant_service.domain;

public enum EstadoPaciente {
    REGISTRADO, EN_CONSULTA, ALTA;

    /** Activo significa estado distinto de ALTA (PRD / RF-04B). */
    public boolean activo() {
        return this != ALTA;
    }
}
