package com.vidacruz.triage_service.application;

import com.vidacruz.triage_service.domain.VitalSigns;

public record RegisterPatientCommand(
        String fullName,
        int age,
        String chiefComplaint,
        VitalSigns vitalSigns) {
}
