package com.vidacruz.triage_service.infrastructure.web;

import com.vidacruz.triage_service.application.HourlyOperationsRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

record ReporteOperacionHoraResponse(
        Instant hora,
        long totalRegistrados,
        Map<String, Long> porNivel,
        BigDecimal promedioMinutosHastaConsulta,
        BigDecimal p95MinutosHastaConsulta) {

    static ReporteOperacionHoraResponse from(HourlyOperationsRow row) {
        Map<String, Long> byLevel = new LinkedHashMap<>();
        row.countByLevel().forEach((level, count) -> byLevel.put(level.name(), count));
        return new ReporteOperacionHoraResponse(row.hour(), row.total(), byLevel, row.averageMinutes(),
                row.p95Minutes());
    }
}
