package com.vidacruz.triage_service.application;

import com.vidacruz.triage_service.domain.TriageLevel;

public interface ClassificationMetricsPort {
    void recordClassification(TriageLevel level);
}
