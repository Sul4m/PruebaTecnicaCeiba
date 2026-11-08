package com.vidacruz.clinical_assistant_service.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest(properties = "assistant.security.api-key=test-key")
class KafkaHealthIndicatorTest {

    @Autowired
    Environment env;

    @Test
    void failingAdminReportsDownWithoutExposingExceptionNames() {
        Admin admin = mock(Admin.class);
        DescribeClusterResult result = mock(DescribeClusterResult.class);
        var failed = new org.apache.kafka.common.internals.KafkaFutureImpl<java.util.Collection<org.apache.kafka.common.Node>>();
        failed.completeExceptionally(new IllegalStateException("secreto"));
        when(result.nodes()).thenReturn(failed);
        when(admin.describeCluster()).thenReturn(result);

        var health = new KafkaHealthIndicator(() -> admin, true).health().block();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().toString()).doesNotContain("IllegalState").doesNotContain("secreto");
    }

    @Test
    void disabledProjectionReportsUpWithoutTouchingKafka() {
        var health = new KafkaHealthIndicator(() -> {
            throw new AssertionError("no debe crear el cliente");
        }, false).health().block();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("kafka", "deshabilitado");
    }

    @Test
    void readinessGroupIncludesRedisAndKafkaButNotTheLlmProvider() {
        String include = env.getProperty("management.endpoint.health.group.readiness.include");
        assertThat(include.split(",")).contains("redis", "kafka").doesNotContain("azureOpenAi", "llm");
    }
}
