package com.vidacruz.clinical_assistant_service.support;

import java.util.UUID;

/** Construye envelopes v1 (docs/eventos-triaje.md) para las pruebas. */
public final class TriageEvents {

    private TriageEvents() {
    }

    public static String json(String eventId, String eventType, int schemaVersion, String patientId, long version,
            String nivel, String estado, boolean escalated) {
        return """
                {"eventId":"%s","eventType":"%s","schemaVersion":%d,"aggregateId":"%s","aggregateVersion":%d,\
                "occurredAt":"2026-11-08T10:15:30.123456Z","payload":{"patientId":"%s","nivel":"%s","estado":"%s",\
                "escalated":%s}}""".formatted(eventId, eventType, schemaVersion, patientId, version, patientId, nivel,
                estado, escalated);
    }

    public static String registrado(String patientId, long version, String nivel) {
        return json(UUID.randomUUID().toString(), "paciente_registrado", 1, patientId, version, nivel, "REGISTRADO",
                false);
    }

    public static String escalado(String patientId, long version, String nivel) {
        return json(UUID.randomUUID().toString(), "paciente_escalado", 1, patientId, version, nivel, "REGISTRADO",
                true);
    }

    public static String enConsulta(String patientId, long version, String nivel) {
        return json(UUID.randomUUID().toString(), "paciente_en_consulta", 1, patientId, version, nivel,
                "EN_CONSULTA", false);
    }

    public static String alta(String patientId, long version, String nivel) {
        return json(UUID.randomUUID().toString(), "paciente_alta", 1, patientId, version, nivel, "ALTA", false);
    }
}
