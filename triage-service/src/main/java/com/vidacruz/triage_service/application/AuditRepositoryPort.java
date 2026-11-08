package com.vidacruz.triage_service.application;

import com.vidacruz.triage_service.domain.AuditEvent;
import reactor.core.publisher.Mono;

public interface AuditRepositoryPort {
    Mono<AuditEvent> save(AuditEvent event);
}
