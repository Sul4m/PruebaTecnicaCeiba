package com.vidacruz.clinical_assistant_service.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KafkaProjectionConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KafkaProjectionConfig.class)
            .withPropertyValues(
                    "assistant.projection.topic=triaje.eventos",
                    "assistant.projection.dlt-topic=triaje.eventos.dlt",
                    "assistant.projection.group-id=g",
                    "assistant.projection.max-attempts=3",
                    "assistant.projection.retry-backoff=100ms",
                    "assistant.projection.retry-max-backoff=1s");

    @Test
    void createsTopicsByDefault() {
        runner.run(ctx -> assertThat(ctx.getBeansOfType(NewTopic.class)).hasSize(2));
    }

    @Test
    void doesNotCreateTopicsWhenDisabledForEventHubs() {
        runner.withPropertyValues("assistant.projection.create-topics=false")
                .run(ctx -> assertThat(ctx.getBeansOfType(NewTopic.class)).isEmpty());
    }
}
