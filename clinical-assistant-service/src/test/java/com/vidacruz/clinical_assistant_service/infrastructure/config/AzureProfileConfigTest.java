package com.vidacruz.clinical_assistant_service.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * El perfil {@code azure} no se ejercita en el resto de pruebas (corren con {@code dev}). Esta carga
 * {@code application.yaml} + {@code application-azure.yaml} con solo las variables obligatorias del
 * despliegue y comprueba que las propiedades del RAG se resuelven: un valor vacio (como el que dejo
 * {@code similarity-threshold:} sin valor) haria fallar el arranque en AKS.
 */
class AzureProfileConfigTest {

    private static final Map<String, Object> ENTORNO_MINIMO_AKS = Map.of(
            "AZURE_OPENAI_ENDPOINT", "https://ejemplo.openai.azure.com/",
            "REDIS_HOST", "redis.ejemplo",
            "REDIS_PASSWORD", "x",
            "KAFKA_BOOTSTRAP_SERVERS", "kafka.ejemplo:9093",
            "EVENTHUBS_CONNECTION_STRING", "Endpoint=sb://ejemplo/",
            "ASISTENTE_API_KEY", "x");

    private static Binder binder() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("entorno-aks", ENTORNO_MINIMO_AKS));
        // application-azure.yaml tiene precedencia sobre application.yaml, como con el perfil activo.
        List<PropertySource<?>> azure = loader.load("azure", new ClassPathResource("application-azure.yaml"));
        List<PropertySource<?>> base = loader.load("base", new ClassPathResource("application.yaml"));
        azure.forEach(p -> env.getPropertySources().addLast(p));
        base.forEach(p -> env.getPropertySources().addLast(p));
        return new Binder(ConfigurationPropertySources.get(env),
                new PropertySourcesPlaceholdersResolver(env));
    }

    @Test
    void ragSeResuelveConElPerfilAzure() throws Exception {
        AssistantRagProperties rag = binder().bind("assistant.rag", Bindable.of(AssistantRagProperties.class))
                .get();

        assertThat(rag.similarityThreshold()).isEqualTo(0.55);
        assertThat(rag.topK()).isBetween(1, 10);
        assertThat(rag.corpusVersion()).isNotBlank();
        assertThat(rag.llmTimeout()).isNotNull();
    }

    @Test
    void ningunaPropiedadAssistantQuedaVaciaEnAzure() throws Exception {
        List<PropertySource<?>> azure = new YamlPropertySourceLoader().load("azure",
                new ClassPathResource("application-azure.yaml"));
        for (PropertySource<?> source : azure) {
            EnumerablePropertySource<?> props = (EnumerablePropertySource<?>) source;
            for (String name : props.getPropertyNames()) {
                if (name.startsWith("assistant.")) {
                    assertThat(String.valueOf(props.getProperty(name))).as("sin valor en azure: %s", name)
                            .isNotBlank();
                }
            }
        }
    }
}
