package com.vidacruz.clinical_assistant_service.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.vidacruz.clinical_assistant_service.infrastructure.ai.dev.DevAiConfig;

class StartupGuardsTest {

    @Configuration
    @EnableConfigurationProperties(AssistantSecurityProperties.class)
    static class SecurityPropsConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    @Test
    void startupFailsWhenApiKeyUnset() {
        runner.withUserConfiguration(SecurityPropsConfig.class)
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void startupFailsWhenApiKeyBlank() {
        runner.withUserConfiguration(SecurityPropsConfig.class)
                .withPropertyValues("assistant.security.api-key=  ")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void startupSucceedsWithApiKey() {
        runner.withUserConfiguration(SecurityPropsConfig.class)
                .withPropertyValues("assistant.security.api-key=k")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void startupFailsWithoutExplicitAiProvider() {
        runner.withUserConfiguration(RagConfig.class, DevAiConfig.class)
                .withPropertyValues("assistant.security.api-key=k")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasStackTraceContaining("assistant.ai.provider");
                });
    }

    @Test
    void devDoublesOnlyWhenProviderIsDevDouble() {
        runner.withUserConfiguration(DevAiConfig.class)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(EmbeddingModel.class).doesNotHaveBean(ChatModel.class));
        runner.withUserConfiguration(DevAiConfig.class)
                .withPropertyValues("assistant.ai.provider=azure")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(EmbeddingModel.class).doesNotHaveBean(ChatModel.class));
        runner.withUserConfiguration(DevAiConfig.class)
                .withPropertyValues("assistant.ai.provider=dev-double")
                .run(ctx -> assertThat(ctx).hasSingleBean(EmbeddingModel.class).hasSingleBean(ChatModel.class));
    }
}
