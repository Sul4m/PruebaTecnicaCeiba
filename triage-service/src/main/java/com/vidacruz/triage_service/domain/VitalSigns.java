package com.vidacruz.triage_service.domain;

import java.math.BigDecimal;

public record VitalSigns(
        int heartRate,
        int systolic,
        int diastolic,
        int spo2,
        BigDecimal temperature,
        int pain,
        int gcs) {
}
