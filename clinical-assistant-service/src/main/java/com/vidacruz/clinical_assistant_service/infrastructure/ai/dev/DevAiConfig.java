package com.vidacruz.clinical_assistant_service.infrastructure.ai.dev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Dobles de desarrollo, activos solo con {@code assistant.ai.provider=dev-double} (la define application-dev.yaml). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "assistant.ai.provider", havingValue = "dev-double", matchIfMissing = false)
public class DevAiConfig {

    private static final Logger log = LoggerFactory.getLogger(DevAiConfig.class);

    @Bean
    EmbeddingModel devEmbeddingModel() {
        log.warn("Usando DevEmbeddingModel (DOBLE DE DESARROLLO). No usar en produccion: active el perfil 'azure'.");
        return new DevEmbeddingModel();
    }

    @Bean
    ChatModel devChatModel() {
        log.warn("Usando DevChatModel (DOBLE DE DESARROLLO). No usar en produccion: active el perfil 'azure'.");
        return new DevChatModel();
    }
}
