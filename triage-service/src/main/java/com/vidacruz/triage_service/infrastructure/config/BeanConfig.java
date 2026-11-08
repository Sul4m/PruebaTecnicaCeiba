package com.vidacruz.triage_service.infrastructure.config;

import com.vidacruz.triage_service.application.AuditRepositoryPort;
import com.vidacruz.triage_service.application.ChangePatientStatusUseCase;
import com.vidacruz.triage_service.application.ClassificationMetricsPort;
import com.vidacruz.triage_service.application.EscalateOverduePatientsUseCase;
import com.vidacruz.triage_service.application.EscalationMetricsPort;
import com.vidacruz.triage_service.application.GetOperationsReportUseCase;
import com.vidacruz.triage_service.application.GetPatientUseCase;
import com.vidacruz.triage_service.application.OperationsReportPort;
import com.vidacruz.triage_service.application.OutboxPort;
import com.vidacruz.triage_service.application.PatientRepositoryPort;
import com.vidacruz.triage_service.application.RegisterPatientUseCase;
import com.vidacruz.triage_service.application.SlaPolicy;
import com.vidacruz.triage_service.infrastructure.scheduling.EscalationProperties;
import com.vidacruz.triage_service.infrastructure.scheduling.SlaProperties;
import com.vidacruz.triage_service.infrastructure.security.SecurityProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration
@EnableConfigurationProperties({SecurityProperties.class, SlaProperties.class, EscalationProperties.class})
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
            OutboxPort outbox, ClassificationMetricsPort metrics, TransactionalOperator tx, Clock clock,
            SlaPolicy sla) {
        return new RegisterPatientUseCase(patients, audits, outbox, metrics, tx, clock, sla);
    }

    @Bean
    ChangePatientStatusUseCase changePatientStatusUseCase(PatientRepositoryPort patients,
            AuditRepositoryPort audits, OutboxPort outbox, TransactionalOperator tx, Clock clock) {
        return new ChangePatientStatusUseCase(patients, audits, outbox, tx, clock);
    }

    @Bean
    EscalateOverduePatientsUseCase escalateOverduePatientsUseCase(PatientRepositoryPort patients,
            AuditRepositoryPort audits, OutboxPort outbox, EscalationMetricsPort metrics, TransactionalOperator tx,
            Clock clock, EscalationProperties props) {
        return new EscalateOverduePatientsUseCase(patients, audits, outbox, metrics, tx, clock, props.batchSize());
    }

    @Bean
    GetOperationsReportUseCase getOperationsReportUseCase(OperationsReportPort report) {
        return new GetOperationsReportUseCase(report);
    }

    @Bean
    GetPatientUseCase getPatientUseCase(PatientRepositoryPort patients) {
        return new GetPatientUseCase(patients);
    }
}
