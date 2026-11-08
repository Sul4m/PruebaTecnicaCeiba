package com.vidacruz.triage_service.infrastructure.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
class KafkaTopicsConfig {

    static final String EVENTS_TOPIC = "triaje.eventos";
    static final String DEAD_LETTER_TOPIC = "triaje.eventos.dlt";

    // En Azure (Event Hubs) los tópicos son event hubs creados por Terraform: la API Kafka
    // no puede crearlos. El perfil `azure` fija triage.kafka.create-topics=false.
    @Bean
    @ConditionalOnProperty(name = "triage.kafka.create-topics", havingValue = "true", matchIfMissing = true)
    NewTopic eventsTopic() {
        return TopicBuilder.name(EVENTS_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    @ConditionalOnProperty(name = "triage.kafka.create-topics", havingValue = "true", matchIfMissing = true)
    NewTopic deadLetterTopic() {
        return TopicBuilder.name(DEAD_LETTER_TOPIC).partitions(3).replicas(1).build();
    }
}
