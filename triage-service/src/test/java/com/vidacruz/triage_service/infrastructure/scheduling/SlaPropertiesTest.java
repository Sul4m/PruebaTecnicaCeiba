package com.vidacruz.triage_service.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vidacruz.triage_service.domain.TriageLevel;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class SlaPropertiesTest {

    private static SlaProperties bind(Map<String, String> props) {
        return new Binder(new MapConfigurationPropertySource(props)).bind("triage.sla", SlaProperties.class)
                .orElseGet(() -> new SlaProperties(null));
    }

    @Test
    void defaultsMatchTheEnum() {
        SlaProperties sla = bind(Map.of());
        assertThat(sla.slaMinutes(TriageLevel.CRITICO)).isEqualTo(5);
        assertThat(sla.slaMinutes(TriageLevel.URGENTE)).isEqualTo(10);
        assertThat(sla.slaMinutes(TriageLevel.URGENTE_2)).isEqualTo(30);
        assertThat(sla.slaMinutes(TriageLevel.NO_URGENTE)).isEqualTo(60);
        assertThat(sla.slaMinutes(TriageLevel.NO_URGENTE_2)).isEqualTo(120);
    }

    @Test
    void overridesAreBoundIncludingUnderscoreLevels() {
        SlaProperties sla = bind(Map.of("triage.sla.minutes.CRITICO", "1", "triage.sla.minutes.URGENTE_2", "7"));
        assertThat(sla.slaMinutes(TriageLevel.CRITICO)).isEqualTo(1);
        assertThat(sla.slaMinutes(TriageLevel.URGENTE_2)).isEqualTo(7);
        assertThat(sla.slaMinutes(TriageLevel.URGENTE)).isEqualTo(10);
    }

    @Test
    void nonPositiveValueIsRejected() {
        assertThatThrownBy(() -> bind(Map.of("triage.sla.minutes.CRITICO", "0")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause().hasMessageContaining("debe ser positivo");
        assertThatThrownBy(() -> bind(Map.of("triage.sla.minutes.NO_URGENTE", "-3")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause().hasMessageContaining("debe ser positivo");
    }

    @Test
    void nonNumericValueIsRejected() {
        assertThatThrownBy(() -> bind(Map.of("triage.sla.minutes.CRITICO", "abc")))
                .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class);
    }

    @Test
    void escalationPropertiesRejectNonPositive() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var v = factory.getValidator();
            assertThat(v.validate(new EscalationProperties(true, Duration.ofSeconds(30), 100))).isEmpty();
            assertThat(v.validate(new EscalationProperties(true, Duration.ZERO, 100))).hasSize(1);
            assertThat(v.validate(new EscalationProperties(true, Duration.ofSeconds(30), 0))).hasSize(1);
        }
    }
}
