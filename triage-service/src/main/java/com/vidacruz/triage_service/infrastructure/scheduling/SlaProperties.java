package com.vidacruz.triage_service.infrastructure.scheduling;

import com.vidacruz.triage_service.application.SlaPolicy;
import com.vidacruz.triage_service.domain.TriageLevel;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SLA por nivel ({@code triage.sla.minutes.<NIVEL>}). Los niveles no configurados usan el valor por defecto
 * del enum (5/10/30/60/120). Un valor no positivo impide el arranque.
 */
@ConfigurationProperties("triage.sla")
public record SlaProperties(Map<TriageLevel, Integer> minutes) implements SlaPolicy {

    public SlaProperties {
        Map<TriageLevel, Integer> merged = new EnumMap<>(TriageLevel.class);
        for (TriageLevel level : TriageLevel.values()) {
            merged.put(level, level.slaMinutes());
        }
        if (minutes != null) {
            merged.putAll(minutes);
        }
        merged.forEach((level, value) -> {
            if (value == null || value <= 0) {
                throw new IllegalArgumentException("triage.sla.minutes." + level + " debe ser positivo");
            }
        });
        minutes = Map.copyOf(merged);
    }

    @Override
    public int slaMinutes(TriageLevel level) {
        return minutes.get(level);
    }
}
