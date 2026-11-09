package com.vidacruz.clinical_assistant_service.infrastructure.kafka;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.apache.kafka.clients.admin.Admin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Parte de la readiness: el Asistente necesita Kafka para mantener la vista de urgencias. Cliente admin unico,
 * creado perezosamente; la consulta bloqueante se aisla en {@code boundedElastic} con timeout.
 */
@Component("kafka")
public class KafkaHealthIndicator implements ReactiveHealthIndicator {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final Supplier<Admin> adminFactory;
    private final boolean enabled;
    private volatile Admin admin;

    @Autowired
    public KafkaHealthIndicator(KafkaAdmin kafkaAdmin,
            @Value("${assistant.projection.enabled:true}") boolean enabled) {
        this(() -> Admin.create(kafkaAdmin.getConfigurationProperties()), enabled);
    }

    KafkaHealthIndicator(Supplier<Admin> adminFactory, boolean enabled) {
        this.adminFactory = adminFactory;
        this.enabled = enabled;
    }

    @Override
    public Mono<Health> health() {
        if (!enabled) {
            return Mono.just(Health.up().withDetail("kafka", "deshabilitado").build());
        }
        return Mono.fromCallable(() -> {
            int nodes = client().describeCluster().nodes().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).size();
            return Health.up().withDetail("nodes", nodes).build();
        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> Mono.just(Health.down().withDetail("error", "kafka no disponible").build()));
    }

    private Admin client() {
        Admin local = admin;
        if (local == null) {
            synchronized (this) {
                if (admin == null) {
                    admin = adminFactory.get();
                }
                local = admin;
            }
        }
        return local;
    }

    @PreDestroy
    void close() {
        Admin local = admin;
        if (local != null) {
            local.close(Duration.ofSeconds(2));
        }
    }
}
