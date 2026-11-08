package com.vidacruz.triage_service.infrastructure.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("triage.alertas.stream")
public record AlertStreamProperties(
        @DefaultValue("1s") @NotNull Duration pollInterval,
        @DefaultValue("15s") @NotNull Duration heartbeatInterval,
        @DefaultValue("100") @Positive int batchSize,
        @DefaultValue("256") @Positive int bufferSize) {

    @AssertTrue(message = "pollInterval debe ser positivo")
    boolean isPollIntervalPositive() {
        return pollInterval == null || (!pollInterval.isNegative() && !pollInterval.isZero());
    }

    @AssertTrue(message = "heartbeatInterval debe ser positivo")
    boolean isHeartbeatIntervalPositive() {
        return heartbeatInterval == null || (!heartbeatInterval.isNegative() && !heartbeatInterval.isZero());
    }

    @AssertTrue(message = "batchSize no puede superar bufferSize")
    boolean isBatchWithinBuffer() {
        return batchSize <= bufferSize;
    }
}
