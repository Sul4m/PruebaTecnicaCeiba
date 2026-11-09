package com.vidacruz.triage_service.application;

import java.time.Instant;
import reactor.core.publisher.Flux;

public class GetOperationsReportUseCase {

    private final OperationsReportPort report;

    public GetOperationsReportUseCase(OperationsReportPort report) {
        this.report = report;
    }

    public Flux<HourlyOperationsRow> report(Instant from, Instant to) {
        return Flux.defer(() -> {
            if (!from.isBefore(to)) {
                return Flux.error(new InvalidReportParameterException("desde",
                        "debe ser anterior a 'hasta'"));
            }
            return report.hourlyReport(from, to);
        });
    }
}
