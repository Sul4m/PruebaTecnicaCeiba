package com.vidacruz.triage_service.infrastructure.web;

import com.vidacruz.triage_service.application.GetOperationsReportUseCase;
import com.vidacruz.triage_service.application.InvalidReportParameterException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/triage/reportes")
class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final GetOperationsReportUseCase operationsReport;

    ReportController(GetOperationsReportUseCase operationsReport) {
        this.operationsReport = operationsReport;
    }

    @GetMapping("/operacion")
    Mono<List<ReporteOperacionHoraResponse>> operations(
            @RequestParam(name = "desde", required = false) String desde,
            @RequestParam(name = "hasta", required = false) String hasta) {
        return Mono.defer(() -> {
            Instant from = parse("desde", desde);
            Instant to = parse("hasta", hasta);
            return operationsReport.report(from, to)
                    .map(ReporteOperacionHoraResponse::from)
                    .collectList()
                    // Solo el rango y la cantidad de filas: sin datos de pacientes.
                    .doOnNext(rows -> log.info("Reporte de operación desde={} hasta={} filas={}", from, to,
                            rows.size()));
        });
    }

    /** ISO 8601 con offset ("Z" o "+hh:mm"); sin offset se interpreta como UTC. */
    private static Instant parse(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidReportParameterException(name, "es obligatorio");
        }
        // Un '+' sin codificar en la query ("+05:00") llega decodificado como espacio; ISO 8601 no admite
        // espacios, así que se restituye antes de parsear.
        value = value.strip().replace(' ', '+');
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException e) {
                throw new InvalidReportParameterException(name, "debe ser una fecha ISO 8601 válida");
            }
        }
    }
}
