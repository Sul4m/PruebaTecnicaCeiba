package com.vidacruz.triage_service.infrastructure.persistence;

import com.vidacruz.triage_service.application.HourlyOperationsRow;
import com.vidacruz.triage_service.application.OperationsReportPort;
import com.vidacruz.triage_service.domain.AuditEvent;
import com.vidacruz.triage_service.domain.TriageLevel;
import io.r2dbc.spi.Row;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

/**
 * Toda la agregación ocurre en una sola consulta SQL; aquí solo se mapean filas ya agrupadas.
 * Rango {@code [desde, hasta)} y bucket por hora en UTC. La espera es desde el registro hasta la
 * primera entrada a consulta; los pacientes sin consulta cuentan en total y por nivel, pero
 * {@code AVG} y {@code percentile_cont} ignoran sus {@code NULL}.
 */
@Repository
class R2dbcOperationsReportRepository implements OperationsReportPort {

    private static final String REPORT = """
            WITH in_range AS (
                SELECT id, level, registered_at
                FROM patient
                WHERE registered_at >= :from AND registered_at < :to
            ),
            first_consultation AS (
                SELECT a.patient_id, MIN(a.occurred_at) AS consulted_at
                FROM audit_event a
                JOIN in_range p ON p.id = a.patient_id
                WHERE a.event_type = :consultationType
                GROUP BY a.patient_id
            ),
            waits AS (
                SELECT p.registered_at, p.level,
                       EXTRACT(EPOCH FROM (f.consulted_at - p.registered_at)) / 60.0 AS wait_minutes
                FROM in_range p
                LEFT JOIN first_consultation f ON f.patient_id = p.id
            )
            SELECT date_trunc('hour', registered_at, 'UTC') AS hour,
                   COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE level = 'CRITICO') AS critico,
                   COUNT(*) FILTER (WHERE level = 'URGENTE') AS urgente,
                   COUNT(*) FILTER (WHERE level = 'URGENTE_2') AS urgente_2,
                   COUNT(*) FILTER (WHERE level = 'NO_URGENTE') AS no_urgente,
                   COUNT(*) FILTER (WHERE level = 'NO_URGENTE_2') AS no_urgente_2,
                   ROUND(AVG(wait_minutes)::numeric, 2) AS avg_minutes,
                   ROUND((percentile_cont(0.95) WITHIN GROUP (ORDER BY wait_minutes))::numeric, 2) AS p95_minutes
            FROM waits
            GROUP BY 1
            ORDER BY 1
            """;

    private final DatabaseClient client;

    R2dbcOperationsReportRepository(DatabaseClient client) {
        this.client = client;
    }

    @Override
    public Flux<HourlyOperationsRow> hourlyReport(Instant from, Instant to) {
        return client.sql(REPORT)
                .bind("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .bind("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .bind("consultationType", AuditEvent.PATIENT_IN_CONSULTATION)
                .map((row, meta) -> toRow(row))
                .all();
    }

    private static HourlyOperationsRow toRow(Row row) {
        Map<TriageLevel, Long> byLevel = new EnumMap<>(TriageLevel.class);
        byLevel.put(TriageLevel.CRITICO, row.get("critico", Long.class));
        byLevel.put(TriageLevel.URGENTE, row.get("urgente", Long.class));
        byLevel.put(TriageLevel.URGENTE_2, row.get("urgente_2", Long.class));
        byLevel.put(TriageLevel.NO_URGENTE, row.get("no_urgente", Long.class));
        byLevel.put(TriageLevel.NO_URGENTE_2, row.get("no_urgente_2", Long.class));
        return new HourlyOperationsRow(
                row.get("hour", OffsetDateTime.class).toInstant(),
                row.get("total", Long.class),
                byLevel,
                row.get("avg_minutes", BigDecimal.class),
                row.get("p95_minutes", BigDecimal.class));
    }
}
