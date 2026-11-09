package com.vidacruz.triage_service.infrastructure.web;

import com.vidacruz.triage_service.domain.PatientStatus;
import jakarta.validation.constraints.NotNull;

record CambiarEstadoRequest(@NotNull(message = "es obligatorio") PatientStatus estado) {
}
