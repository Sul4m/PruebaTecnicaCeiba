package com.vidacruz.triage_service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TriageClassifierTest {

    private static VitalSigns vitals(int hr, int sys, int dia, int spo2, String temp, int pain, int gcs) {
        return new VitalSigns(hr, sys, dia, spo2, new BigDecimal(temp), pain, gcs);
    }

    /** Signos vitales normales: no disparan ningún nivel. */
    private static VitalSigns normal() {
        return vitals(72, 120, 80, 99, "36.5", 2, 15);
    }

    @ParameterizedTest(name = "{0} -> {9} / SLA {10}")
    @CsvSource({
            "E1,130,180,110,97,36.8,8,15,Dolor torácico,CRITICO,5",
            "E2,95,150,95,91,37.0,4,15,Dificultad para respirar,URGENTE,10",
            "E3,88,130,85,98,38.2,6,15,Fiebre,URGENTE_2,30",
            "E4,72,120,80,99,36.5,2,15,Herida por cortar,NO_URGENTE,60",
            "E5,70,115,75,99,36.4,0,15,Renovación de recetario,NO_URGENTE_2,120",
            "E6,35,80,50,85,35.9,0,6,Inconsciente,CRITICO,5"
    })
    void referenceCases(String id, int hr, int sys, int dia, int spo2, String temp, int pain, int gcs,
            String complaint, TriageLevel expected, int sla) {
        TriageLevel level = TriageClassifier.classify(vitals(hr, sys, dia, spo2, temp, pain, gcs), complaint);
        assertThat(level).isEqualTo(expected);
        assertThat(level.slaMinutes()).isEqualTo(sla);
    }

    @Test
    void normalVitalsAreNoUrgente() {
        assertThat(TriageClassifier.classify(normal(), "Dolor de cabeza")).isEqualTo(TriageLevel.NO_URGENTE);
    }

    @ParameterizedTest(name = "FC {0} -> {1}")
    @CsvSource({"39,CRITICO", "40,NO_URGENTE", "89,NO_URGENTE", "90,URGENTE_2", "120,URGENTE_2", "121,CRITICO"})
    void heartRateBoundaries(int hr, TriageLevel expected) {
        assertThat(TriageClassifier.classify(vitals(hr, 120, 80, 99, "36.5", 2, 15), "x")).isEqualTo(expected);
    }

    @ParameterizedTest(name = "SpO2 {0} -> {1}")
    @CsvSource({"89,CRITICO", "90,URGENTE", "93,URGENTE", "94,NO_URGENTE"})
    void spo2Boundaries(int spo2, TriageLevel expected) {
        assertThat(TriageClassifier.classify(vitals(72, 120, 80, spo2, "36.5", 2, 15), "x")).isEqualTo(expected);
    }

    @ParameterizedTest(name = "sistólica {0} -> {1}")
    @CsvSource({"89,CRITICO", "90,URGENTE", "100,URGENTE", "101,NO_URGENTE"})
    void systolicBoundaries(int sys, TriageLevel expected) {
        assertThat(TriageClassifier.classify(vitals(72, sys, 60, 99, "36.5", 2, 15), "x")).isEqualTo(expected);
    }

    @ParameterizedTest(name = "temp {0} -> {1}")
    @CsvSource({"37.9,NO_URGENTE", "38.0,URGENTE_2", "38.4,URGENTE_2", "38.5,URGENTE"})
    void temperatureBoundaries(String temp, TriageLevel expected) {
        assertThat(TriageClassifier.classify(vitals(72, 120, 80, 99, temp, 2, 15), "x")).isEqualTo(expected);
    }

    @ParameterizedTest(name = "GCS {0} -> {1}")
    @CsvSource({"8,CRITICO", "9,URGENTE", "13,URGENTE", "14,NO_URGENTE"})
    void gcsBoundaries(int gcs, TriageLevel expected) {
        assertThat(TriageClassifier.classify(vitals(72, 120, 80, 99, "36.5", 2, gcs), "x")).isEqualTo(expected);
    }

    @ParameterizedTest(name = "dolor {0} -> {1}")
    @CsvSource({"6,NO_URGENTE", "7,URGENTE_2"})
    void painBoundaries(int pain, TriageLevel expected) {
        assertThat(TriageClassifier.classify(vitals(72, 120, 80, 99, "36.5", pain, 15), "x")).isEqualTo(expected);
    }

    @Test
    void mostSevereLevelPrevails() {
        // SpO2 85 (nivel 1) + dolor 8 (nivel 3)
        assertThat(TriageClassifier.classify(vitals(72, 120, 80, 85, "36.5", 8, 15), "x"))
                .isEqualTo(TriageLevel.CRITICO);
    }

    @ParameterizedTest
    @CsvSource({
            "documentos", "recolección de resultados", "renovación de recetario", "vacunación", "seguimiento",
            "'  VACUNACION  '", "Renovacion   de   Recetario", "RECOLECCIÓN DE RESULTADOS"
    })
    void administrativeComplaintsNormalized(String complaint) {
        assertThat(TriageClassifier.classify(normal(), complaint)).isEqualTo(TriageLevel.NO_URGENTE_2);
    }

    @Test
    void administrativeMatchToleratesUnicodeWhitespace() {
        assertThat(TriageClassifier.classify(normal(), "\u00A0renovación\u00A0de\u2003recetario\u00A0"))
                .isEqualTo(TriageLevel.NO_URGENTE_2);
    }

    @Test
    void administrativeWithSeverityIsNotLevel5() {
        // "Vacunación" + FC 95 -> URGENTE_2
        assertThat(TriageClassifier.classify(vitals(95, 120, 80, 99, "36.5", 2, 15), "Vacunación"))
                .isEqualTo(TriageLevel.URGENTE_2);
    }

    @Test
    void partialMatchIsNotAdministrative() {
        assertThat(TriageClassifier.classify(normal(), "vacunación y fiebre")).isEqualTo(TriageLevel.NO_URGENTE);
    }
}
