package com.vidacruz.triage_service.application;

import java.time.Instant;
import reactor.core.publisher.Flux;

public interface OperationsReportPort {

    /** Una fila por hora UTC con pacientes registrados en {@code [from, to)}, ordenada por hora. */
    Flux<HourlyOperationsRow> hourlyReport(Instant from, Instant to);
}
