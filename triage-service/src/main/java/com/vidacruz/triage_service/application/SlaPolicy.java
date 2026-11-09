package com.vidacruz.triage_service.application;

import com.vidacruz.triage_service.domain.TriageLevel;

/** SLA vigente (en minutos) para cada nivel de triaje. */
@FunctionalInterface
public interface SlaPolicy {
    int slaMinutes(TriageLevel level);
}
