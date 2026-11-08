package com.vidacruz.triage_service.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KafkaTopicsConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(KafkaTopicsConfig.class);

    @Test
    void createsTopicsByDefault() {
        runner.run(ctx -> assertThat(ctx.getBeansOfType(NewTopic.class)).hasSize(2));
    }

    @Test
    void doesNotCreateTopicsWhenDisabledForEventHubs() {
        runner.withPropertyValues("triage.kafka.create-topics=false")
                .run(ctx -> assertThat(ctx.getBeansOfType(NewTopic.class)).isEmpty());
    }
}
