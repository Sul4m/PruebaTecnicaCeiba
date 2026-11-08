package com.vidacruz.triage_service.infrastructure.messaging;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("triage.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1s") @NotNull Duration interval,
        @DefaultValue("100") @Positive int batchSize,
        @DefaultValue("5") @Positive int maxAttempts,
        @DefaultValue("30s") @NotNull Duration maxBackoff) {

    @AssertTrue(message = "interval debe ser positivo")
    boolean isIntervalPositive() {
        return interval == null || (!interval.isNegative() && !interval.isZero());
    }

    @AssertTrue(message = "maxBackoff debe ser positivo")
    boolean isMaxBackoffPositive() {
        return maxBackoff == null || (!maxBackoff.isNegative() && !maxBackoff.isZero());
    }
}
