package com.vidacruz.triage_service.application;

import com.vidacruz.triage_service.domain.MedicalAlert;
import reactor.core.publisher.Flux;

public class GetAlertsUseCase {

    public static final int MAX_LIMIT = 200;

    private final AlertRepositoryPort alerts;

    public GetAlertsUseCase(AlertRepositoryPort alerts) {
        this.alerts = alerts;
    }

    public Flux<MedicalAlert> findAfter(long cursor, long limit) {
        return Flux.defer(() -> {
            if (cursor < 0) {
                throw new InvalidReportParameterException("despuesDe", "debe ser un entero mayor o igual a 0");
            }
            if (limit < 1 || limit > MAX_LIMIT) {
                throw new InvalidReportParameterException("limite", "debe estar entre 1 y " + MAX_LIMIT);
            }
            return alerts.findAfter(cursor, (int) limit);
        });
    }
}
