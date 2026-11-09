package com.vidacruz.clinical_assistant_service.infrastructure.config;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.vidacruz.clinical_assistant_service.domain.ProtocolChunk;
import com.vidacruz.clinical_assistant_service.infrastructure.ai.CorpusIndexRunner;
import com.vidacruz.clinical_assistant_service.infrastructure.ai.LlmGuard;
import com.vidacruz.clinical_assistant_service.infrastructure.ai.ProtocolCorpusLoader;
import com.vidacruz.clinical_assistant_service.infrastructure.ai.SpringAiAnswerGenerator;
import com.vidacruz.clinical_assistant_service.infrastructure.ai.SpringAiProtocolRetriever;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ AssistantRagProperties.class, AssistantSecurityProperties.class,
        AssistantLlmProperties.class })
public class RagConfig {

    /** Falla el arranque, antes de crear beans, si no se eligio proveedor de IA de forma explicita. */
    @Bean
    static BeanFactoryPostProcessor aiProviderGuard() {
        return beanFactory -> {
            String provider = beanFactory.getBean(Environment.class).getProperty("assistant.ai.provider");
            if (!"dev-double".equals(provider) && !"azure".equals(provider)) {
                throw new IllegalStateException("Proveedor de IA no configurado: defina assistant.ai.provider="
                        + "dev-double (solo desarrollo/pruebas) o azure (perfil 'azure')");
            }
        };
    }

    /** Scheduler dedicado con hilos y cola acotados; al saturarse rechaza (-> 503), nunca encola sin limite. */
    @Bean(destroyMethod = "dispose")
    Scheduler ragScheduler(AssistantRagProperties props) {
        return Schedulers.newBoundedElastic(props.scheduler().threads(), props.scheduler().queueCapacity(),
                "rag-embedding", 60, true);
    }

    @Bean
    List<ProtocolChunk> protocolCorpus(AssistantRagProperties props) {
        return new ProtocolCorpusLoader().load(props.corpusLocation(), props.corpusVersion());
    }

    @Bean
    SimpleVectorStore protocolVectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    @Bean
    LlmGuard llmGuard(AssistantLlmProperties props, MeterRegistry registry) {
        return new LlmGuard(props, registry);
    }

    @Bean
    SpringAiProtocolRetriever protocolRetriever(SimpleVectorStore store, Scheduler ragScheduler,
            AssistantRagProperties props, List<ProtocolChunk> protocolCorpus, LlmGuard llmGuard) {
        return new SpringAiProtocolRetriever(store, ragScheduler, props, protocolCorpus, llmGuard);
    }

    @Bean
    CorpusIndexRunner corpusIndexRunner(SpringAiProtocolRetriever retriever) {
        return new CorpusIndexRunner(retriever);
    }

    /** Sin tools/functions registradas: el modelo solo redacta con el contexto recuperado. */
    @Bean
    SpringAiAnswerGenerator answerGenerator(ChatClient.Builder builder, AssistantRagProperties props,
            LlmGuard llmGuard) {
        return new SpringAiAnswerGenerator(builder.build(), props, llmGuard);
    }
}
