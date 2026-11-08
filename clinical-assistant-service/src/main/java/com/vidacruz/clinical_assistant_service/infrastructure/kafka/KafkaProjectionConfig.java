package com.vidacruz.clinical_assistant_service.infrastructure.kafka;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import com.vidacruz.clinical_assistant_service.infrastructure.config.ProjectionProperties;
import org.apache.kafka.clients.admin.NewTopic;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProjectionProperties.class)
@ConditionalOnProperty(prefix = "assistant.projection", name = "enabled", havingValue = "true", matchIfMissing = true)
class KafkaProjectionConfig {

    /** Mismos topics y particiones que Triage; la creacion es idempotente y evita carreras en el arranque. */
    @Bean
    NewTopic eventsTopic(ProjectionProperties props) {
        return TopicBuilder.name(props.topic()).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic deadLetterTopic(ProjectionProperties props) {
        return TopicBuilder.name(props.dltTopic()).partitions(3).replicas(1).build();
    }

    /**
     * Red de seguridad: el listener ya resuelve sus errores (DLT). Si aun asi falla (p. ej. el DLT no responde),
     * se reintenta sin limite cada 2 s en lugar de saltar el offset y perder el mensaje.
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(2000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
