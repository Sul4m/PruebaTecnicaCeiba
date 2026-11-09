package com.vidacruz.triage_service.domain;

import static com.vidacruz.triage_service.domain.PatientStatus.ALTA;
import static com.vidacruz.triage_service.domain.PatientStatus.EN_CONSULTA;
import static com.vidacruz.triage_service.domain.PatientStatus.REGISTRADO;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PatientStatusTest {

    static Stream<Arguments> combinations() {
        return Stream.of(
                Arguments.of(REGISTRADO, REGISTRADO, false),
                Arguments.of(REGISTRADO, EN_CONSULTA, true),
                Arguments.of(REGISTRADO, ALTA, true),
                Arguments.of(EN_CONSULTA, REGISTRADO, false),
                Arguments.of(EN_CONSULTA, EN_CONSULTA, false),
                Arguments.of(EN_CONSULTA, ALTA, true),
                Arguments.of(ALTA, REGISTRADO, false),
                Arguments.of(ALTA, EN_CONSULTA, false),
                Arguments.of(ALTA, ALTA, false));
    }

    @ParameterizedTest
    @MethodSource("combinations")
    void transitionTable(PatientStatus from, PatientStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }
}
