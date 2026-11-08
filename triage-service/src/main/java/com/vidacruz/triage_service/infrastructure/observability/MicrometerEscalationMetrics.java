package com.vidacruz.triage_service.infrastructure.observability;

import com.vidacruz.triage_service.application.EscalationMetricsPort;
import com.vidacruz.triage_service.domain.TriageLevel;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
class MicrometerEscalationMetrics implements EscalationMetricsPort {

    static final String METRIC = "triage.escalamientos";

    private final MeterRegistry registry;

    MicrometerEscalationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordEscalation(TriageLevel level) {
        registry.counter(METRIC, "nivel", level.name()).increment();
    }
}
