package com.vidacruz.triage_service.application;

import java.util.UUID;

public class PatientNotFoundException extends RuntimeException {

    private final UUID patientId;

    public PatientNotFoundException(UUID patientId) {
        super("No existe un paciente con id " + patientId);
        this.patientId = patientId;
    }

    public UUID patientId() {
        return patientId;
    }
}
