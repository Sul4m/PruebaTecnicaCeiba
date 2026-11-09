package com.vidacruz.clinical_assistant_service.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Contenedores compartidos por las pruebas *ContainersIT. REQUIEREN Docker: sin demonio, el primer acceso falla
 * con la excepcion de Testcontainers ("Could not find a valid Docker environment"). Las pruebas ordinarias no los usan.
 */
public final class ProjectionContainers {

    // 3.9.0 no arranca con el KafkaContainer de Testcontainers; 3.9.1 si (igual que en Triage).
    public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");
    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

    static {
        KAFKA.start();
        REDIS.start();
    }

    private ProjectionContainers() {
    }
}
