package com.vidacruz.triage_service.domain;

/** El SLA del enum es solo el valor por defecto; la fuente de verdad es la propiedad {@code triage.sla.minutes.*}. */
public enum TriageLevel {
    CRITICO(5),
    URGENTE(10),
    URGENTE_2(30),
    NO_URGENTE(60),
    NO_URGENTE_2(120);

    private final int slaMinutes;

    TriageLevel(int slaMinutes) {
        this.slaMinutes = slaMinutes;
    }

    public int slaMinutes() {
        return slaMinutes;
    }
}
