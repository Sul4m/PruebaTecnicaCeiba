package com.vidacruz.triage_service.infrastructure.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

@DiastolicaMenorQueSistolica
public record SignosVitalesRequest(
        @NotNull @Min(20) @Max(250) Integer frecuenciaCardiaca,
        @NotNull @Min(60) @Max(260) Integer presionSistolica,
        @NotNull @Min(30) @Max(160) Integer presionDiastolica,
        @NotNull @Min(70) @Max(100) Integer spo2,
        @NotNull @DecimalMin("34.0") @DecimalMax("42.0") BigDecimal temperatura,
        @NotNull @Min(0) @Max(10) Integer dolor,
        @NotNull @Min(3) @Max(15) Integer gcs) {
}
