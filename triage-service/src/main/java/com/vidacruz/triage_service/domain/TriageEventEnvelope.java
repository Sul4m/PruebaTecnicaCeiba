package com.vidacruz.triage_service.domain;

import java.time.Instant;
import java.util.UUID;

/** Envelope v1 de eventos de triaje (ADR-0005). Sin datos personales ni signos vitales. */
public record TriageEventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        Payload payload) {

    public static final int SCHEMA_VERSION = 1;

    public record Payload(UUID patientId, String nivel, String estado, boolean escalated) {
    }

    public static TriageEventEnvelope of(String eventType, UUID patientId, long aggregateVersion,
            TriageLevel level, PatientStatus status, boolean escalated, Instant occurredAt) {
        return new TriageEventEnvelope(UUID.randomUUID(), eventType, SCHEMA_VERSION, patientId,
                aggregateVersion, occurredAt, new Payload(patientId, level.name(), status.name(), escalated));
    }
}
