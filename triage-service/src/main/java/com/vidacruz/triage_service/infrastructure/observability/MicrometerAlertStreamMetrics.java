package com.vidacruz.triage_service.infrastructure.observability;

import com.vidacruz.triage_service.application.AlertStreamMetricsPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
class MicrometerAlertStreamMetrics implements AlertStreamMetricsPort {

    static final String METRIC = "triage.alertas.sse.conexiones";

    private final AtomicInteger active = new AtomicInteger();

    MicrometerAlertStreamMetrics(MeterRegistry registry) {
        Gauge.builder(METRIC, active, AtomicInteger::get)
                .description("Conexiones SSE de alertas activas")
                .register(registry);
    }

    @Override
    public void connectionOpened() {
        active.incrementAndGet();
    }

    @Override
    public void connectionClosed() {
        active.decrementAndGet();
    }
}
