package com.vidacruz.triage_service.domain;

import java.time.Instant;
import java.util.UUID;

public record Patient(
        UUID id,
        String fullName,
        int age,
        String chiefComplaint,
        VitalSigns vitalSigns,
        TriageLevel level,
        PatientStatus status,
        boolean escalated,
        Instant registeredAt,
        Instant statusChangedAt,
        long aggregateVersion) {

    public Patient withStatus(PatientStatus newStatus, Instant changedAt, long newVersion, boolean newEscalated) {
        return new Patient(id, fullName, age, chiefComplaint, vitalSigns, level, newStatus, newEscalated,
                registeredAt, changedAt, newVersion);
    }

    public int slaMinutes() {
        return level.slaMinutes();
    }
}
