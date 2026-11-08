package com.vidacruz.triage_service.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage_service.domain.Patient;
import com.vidacruz.triage_service.domain.PatientStatus;
import com.vidacruz.triage_service.domain.TriageLevel;
import com.vidacruz.triage_service.domain.VitalSigns;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PacienteDetalleResponseTest {

    private static final Instant CHANGED = Instant.parse("2026-11-07T10:00:00Z");

    private static Patient patient() {
        return new Patient(UUID.randomUUID(), "Ana Pérez", 54, "Dolor torácico",
                new VitalSigns(130, 180, 110, 97, new BigDecimal("36.8"), 8, 15), TriageLevel.CRITICO,
                PatientStatus.REGISTRADO, false, CHANGED, CHANGED, 1L);
    }

    @ParameterizedTest(name = "now={0} -> {1} min")
    @CsvSource({
            "2026-11-07T10:00:00Z,0",
            "2026-11-07T10:00:59.999Z,0",
            "2026-11-07T10:01:00Z,1",
            "2026-11-07T11:30:30Z,90",
            "2026-11-07T09:59:00Z,0"
    })
    void minutesInStatusAreWholeMinutesAndNeverNegative(String now, long expected) {
        assertThat(PacienteDetalleResponse.from(patient(), Instant.parse(now)).minutosEnEstadoActual())
                .isEqualTo(expected);
    }
}
