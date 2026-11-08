package com.vidacruz.clinical_assistant_service.infrastructure.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Carga el vector store al arrancar. Los {@link ApplicationRunner} corren en el hilo principal ANTES de que la
 * aplicacion publique readiness UP; el trabajo bloqueante ocurre en el scheduler dedicado, no en el event loop.
 * Si el proveedor de embeddings falla, el servicio arranca igual y la consulta responde 503 (reintenta al consultar).
 */
public class CorpusIndexRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CorpusIndexRunner.class);

    private final SpringAiProtocolRetriever retriever;

    public CorpusIndexRunner(SpringAiProtocolRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            retriever.indexCorpus().block();
        } catch (RuntimeException e) {
            log.error("No se pudo indexar el corpus al arranque ({}); la consulta respondera 503 hasta lograrlo",
                    e.getClass().getSimpleName());
        }
    }
}
