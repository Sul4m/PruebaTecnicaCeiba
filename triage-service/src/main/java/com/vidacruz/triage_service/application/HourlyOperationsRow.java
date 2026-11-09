package com.vidacruz.triage_service.application;

import com.vidacruz.triage_service.domain.TriageLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Fila ya agrupada por hora UTC. Los promedios son {@code null} cuando ningún paciente del bucket
 * entró a consulta.
 */
public record HourlyOperationsRow(
        Instant hour,
        long total,
        Map<TriageLevel, Long> countByLevel,
        BigDecimal averageMinutes,
        BigDecimal p95Minutes) {
}
