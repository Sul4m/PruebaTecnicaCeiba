package com.vidacruz.triage_service.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage_service.infrastructure.config.AlertStreamProperties;
import jakarta.validation.Validation;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AlertStreamPropertiesTest {

    private static int violations(AlertStreamProperties p) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(p).size();
        }
    }

    private static final Duration S = Duration.ofSeconds(1);

    @Test
    void validValuesPass() {
        assertThat(violations(new AlertStreamProperties(S, S, 100, 256))).isZero();
        assertThat(violations(new AlertStreamProperties(S, S, 256, 256))).isZero();
    }

    @Test
    void nonPositiveValuesAreRejected() {
        assertThat(violations(new AlertStreamProperties(Duration.ZERO, S, 100, 256))).isEqualTo(1);
        assertThat(violations(new AlertStreamProperties(S, Duration.ofSeconds(-1), 100, 256))).isEqualTo(1);
        assertThat(violations(new AlertStreamProperties(S, S, 0, 256))).isPositive();
        assertThat(violations(new AlertStreamProperties(S, S, 100, -1))).isPositive();
    }

    @Test
    void batchLargerThanBufferIsRejected() {
        assertThat(violations(new AlertStreamProperties(S, S, 257, 256))).isEqualTo(1);
    }
}
