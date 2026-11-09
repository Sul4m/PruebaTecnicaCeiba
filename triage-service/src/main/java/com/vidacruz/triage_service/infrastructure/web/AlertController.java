package com.vidacruz.triage_service.infrastructure.web;

import com.vidacruz.triage_service.application.GetAlertsUseCase;
import com.vidacruz.triage_service.application.InvalidReportParameterException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/triage/alertas")
class AlertController {

    private static final Logger log = LoggerFactory.getLogger(AlertController.class);

    private static final int DEFAULT_LIMIT = 50;

    private final GetAlertsUseCase getAlerts;

    AlertController(GetAlertsUseCase getAlerts) {
        this.getAlerts = getAlerts;
    }

    @GetMapping
    Mono<List<AlertaResponse>> list(
            @RequestParam(name = "despuesDe", required = false) String despuesDe,
            @RequestParam(name = "limite", required = false) String limite) {
        return Mono.defer(() -> {
            long after = parse("despuesDe", despuesDe, 0L);
            long limit = parse("limite", limite, DEFAULT_LIMIT);
            return getAlerts.findAfter(after, limit)
                    .map(AlertaResponse::from)
                    .collectList()
                    .doOnNext(rows -> log.info("Consulta de alertas despuesDe={} limite={} filas={}", after,
                            limit, rows.size()));
        });
    }

    private static long parse(String name, String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            throw new InvalidReportParameterException(name, "debe ser un entero");
        }
    }
}
