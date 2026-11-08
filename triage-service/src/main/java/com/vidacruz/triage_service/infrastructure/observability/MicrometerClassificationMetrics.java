package com.vidacruz.triage_service.infrastructure.observability;

import com.vidacruz.triage_service.application.ClassificationMetricsPort;
import com.vidacruz.triage_service.domain.TriageLevel;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
class MicrometerClassificationMetrics implements ClassificationMetricsPort {

    static final String METRIC = "triage.clasificaciones";

    private final MeterRegistry registry;

    MicrometerClassificationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordClassification(TriageLevel level) {
        registry.counter(METRIC, "nivel", level.name()).increment();
    }
}
