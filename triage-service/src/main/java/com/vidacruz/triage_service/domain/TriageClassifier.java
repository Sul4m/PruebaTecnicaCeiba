package com.vidacruz.triage_service.domain;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** RN-01: clasificación por gravedad. Dominio puro, sin dependencias de framework. */
public final class TriageClassifier {

    private static final BigDecimal TEMP_URGENT = new BigDecimal("38.5");
    private static final BigDecimal TEMP_URGENT_2 = new BigDecimal("38.0");

    private static final Set<String> ADMINISTRATIVE = Set.of(
                    "documentos",
                    "recolección de resultados",
                    "renovación de recetario",
                    "vacunación",
                    "seguimiento")
            .stream()
            .map(TriageClassifier::normalize)
            .collect(Collectors.toUnmodifiableSet());

    private TriageClassifier() {
    }

    public static TriageLevel classify(VitalSigns v, String chiefComplaint) {
        if (isCritical(v)) {
            return TriageLevel.CRITICO;
        }
        if (isUrgent(v)) {
            return TriageLevel.URGENTE;
        }
        if (isUrgent2(v)) {
            return TriageLevel.URGENTE_2;
        }
        return isAdministrative(chiefComplaint) ? TriageLevel.NO_URGENTE_2 : TriageLevel.NO_URGENTE;
    }

    private static boolean isCritical(VitalSigns v) {
        return v.heartRate() < 40 || v.heartRate() > 120
                || v.spo2() < 90
                || v.systolic() < 90
                || v.gcs() <= 8;
    }

    private static boolean isUrgent(VitalSigns v) {
        return (v.spo2() >= 90 && v.spo2() <= 93)
                || (v.systolic() >= 90 && v.systolic() <= 100)
                || v.temperature().compareTo(TEMP_URGENT) >= 0
                || (v.gcs() >= 9 && v.gcs() <= 13);
    }

    private static boolean isUrgent2(VitalSigns v) {
        return (v.heartRate() >= 90 && v.heartRate() <= 120)
                || (v.temperature().compareTo(TEMP_URGENT_2) >= 0 && v.temperature().compareTo(TEMP_URGENT) < 0)
                || v.pain() >= 7;
    }

    private static boolean isAdministrative(String complaint) {
        return complaint != null && ADMINISTRATIVE.contains(normalize(complaint));
    }

    static String normalize(String text) {
        // NFKD convierte espacios Unicode (p. ej. U+00A0) y separa tildes; luego se colapsa todo espacio Unicode.
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD);
        return decomposed.replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Z}\\s]+", " ")
                .strip();
    }
}
