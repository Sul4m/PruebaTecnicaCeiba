package com.vidacruz.triage_service.infrastructure.scheduling;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("triage.escalation")
public record EscalationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30s") @NotNull Duration interval,
        @DefaultValue("100") @Positive int batchSize) {

    @AssertTrue(message = "interval debe ser positivo")
    boolean isIntervalPositive() {
        return interval == null || (!interval.isNegative() && !interval.isZero());
    }
}
