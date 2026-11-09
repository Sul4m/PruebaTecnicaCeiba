package com.vidacruz.triage_service.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage_service.infrastructure.messaging.OutboxProperties;
import jakarta.validation.Validation;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OutboxPropertiesTest {

    private static int violations(OutboxProperties p) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(p).size();
        }
    }

    @Test
    void validValuesPass() {
        assertThat(violations(new OutboxProperties(true, Duration.ofSeconds(1), 100, 5, Duration.ofSeconds(30))))
                .isZero();
    }

    @Test
    void nonPositiveValuesAreRejected() {
        assertThat(violations(new OutboxProperties(true, Duration.ofSeconds(1), 0, 5, Duration.ofSeconds(30))))
                .isEqualTo(1);
        assertThat(violations(new OutboxProperties(true, Duration.ofSeconds(1), 100, -1, Duration.ofSeconds(30))))
                .isEqualTo(1);
        assertThat(violations(new OutboxProperties(true, Duration.ZERO, 100, 5, Duration.ofSeconds(30))))
                .isEqualTo(1);
        assertThat(violations(new OutboxProperties(true, Duration.ofSeconds(1), 100, 5, Duration.ofSeconds(-1))))
                .isEqualTo(1);
    }
}
