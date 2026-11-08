package com.vidacruz.triage_service.infrastructure.config;

import com.vidacruz.triage_service.application.AuditRepositoryPort;
import com.vidacruz.triage_service.application.ChangePatientStatusUseCase;
import com.vidacruz.triage_service.application.ClassificationMetricsPort;
import com.vidacruz.triage_service.application.GetPatientUseCase;
import com.vidacruz.triage_service.application.PatientRepositoryPort;
import com.vidacruz.triage_service.application.RegisterPatientUseCase;
import com.vidacruz.triage_service.infrastructure.security.SecurityProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
class BeanConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TransactionalOperator transactionalOperator(ReactiveTransactionManager manager) {
        return TransactionalOperator.create(manager);
    }

    @Bean
    RegisterPatientUseCase registerPatientUseCase(PatientRepositoryPort patients, AuditRepositoryPort audits,
            ClassificationMetricsPort metrics, TransactionalOperator tx, Clock clock) {
        return new RegisterPatientUseCase(patients, audits, metrics, tx, clock);
    }

    @Bean
    ChangePatientStatusUseCase changePatientStatusUseCase(PatientRepositoryPort patients,
            AuditRepositoryPort audits, TransactionalOperator tx, Clock clock) {
        return new ChangePatientStatusUseCase(patients, audits, tx, clock);
    }

    @Bean
    GetPatientUseCase getPatientUseCase(PatientRepositoryPort patients) {
        return new GetPatientUseCase(patients);
    }
}
