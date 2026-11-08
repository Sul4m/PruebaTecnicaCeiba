package com.vidacruz.clinical_assistant_service.infrastructure.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import com.vidacruz.clinical_assistant_service.application.port.out.ProtocolRetrievalPort;
import com.vidacruz.clinical_assistant_service.domain.AssistantUnavailableException;
import com.vidacruz.clinical_assistant_service.domain.ProtocolChunk;
import com.vidacruz.clinical_assistant_service.domain.RetrievedChunk;
import com.vidacruz.clinical_assistant_service.infrastructure.config.AssistantRagProperties;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Adaptador de salida: {@link SimpleVectorStore} de Spring AI. Embedding y busqueda son sincronos en Spring AI 1.0,
 * por lo que SIEMPRE se ejecutan en un scheduler dedicado y acotado (hilos y cola), nunca en el event loop.
 * Si el scheduler se satura o el proveedor falla se emite {@link AssistantUnavailableException} (HTTP 503).
 */
public class SpringAiProtocolRetriever implements ProtocolRetrievalPort {

    static final String PROTOCOL_ID = "protocolId";
    static final String PROTOCOL_TITLE = "protocolTitle";
    static final String SECTION_ID = "sectionId";
    static final String SECTION_TITLE = "sectionTitle";
    static final String CORPUS_VERSION = "corpusVersion";
    static final String BODY = "body";

    private static final Logger log = LoggerFactory.getLogger(SpringAiProtocolRetriever.class);

    private final SimpleVectorStore vectorStore;
    private final Scheduler scheduler;
    private final AssistantRagProperties props;
    private final List<ProtocolChunk> corpus;
    private final Mono<Void> indexing;
    private final LlmGuard guard;
    private volatile boolean indexed;

    public SpringAiProtocolRetriever(SimpleVectorStore vectorStore, Scheduler scheduler,
            AssistantRagProperties props, List<ProtocolChunk> corpus) {
        this(vectorStore, scheduler, props, corpus, LlmGuard.disabled());
    }

    public SpringAiProtocolRetriever(SimpleVectorStore vectorStore, Scheduler scheduler,
            AssistantRagProperties props, List<ProtocolChunk> corpus, LlmGuard guard) {
        this.guard = guard;
        this.vectorStore = vectorStore;
        this.scheduler = scheduler;
        this.props = props;
        this.corpus = corpus;
        // Indexacion compartida y perezosa: los llamadores concurrentes se suscriben al mismo Mono; el exito se
        // cachea y el error se descarta (TTL cero) para que una consulta posterior reintente. Timeout propio.
        this.indexing = Mono.<Void>fromRunnable(this::indexBlocking)
                .subscribeOn(scheduler)
                .timeout(props.embeddingTimeout().multipliedBy(Math.max(1, corpus.size())))
                .onErrorMap(this::toUnavailable)
                .cache(v -> Duration.ofMillis(Long.MAX_VALUE), t -> Duration.ZERO,
                        () -> Duration.ofMillis(Long.MAX_VALUE));
    }

    public boolean isIndexed() {
        return indexed;
    }

    public List<ProtocolChunk> corpus() {
        return corpus;
    }

    /** Calcula embeddings del corpus y los carga en el vector store (usado al arranque y como reintento). */
    public Mono<Void> indexCorpus() {
        return indexing;
    }

    @Override
    public Mono<List<RetrievedChunk>> retrieve(String question) {
        Mono<List<RetrievedChunk>> search = Mono.fromCallable(() -> searchBlocking(question))
                .subscribeOn(scheduler)
                .timeout(props.embeddingTimeout());
        // Indexacion pendiente (reintento) y busqueda van protegidas: con circuito abierto no se contacta al proveedor.
        // Ya indexado, la indexacion es un no-op y no se cuenta en el circuito.
        Mono<Void> ensureIndexed = Mono.defer(() -> indexed ? Mono.<Void>empty()
                : guard.protect(LlmGuard.EMBEDDINGS, indexing));
        return ensureIndexed.then(guard.protect(LlmGuard.EMBEDDINGS, search)).onErrorMap(this::toUnavailable);
    }

    private void indexBlocking() {
        List<Document> docs = corpus.stream().map(SpringAiProtocolRetriever::toDocument).toList();
        vectorStore.add(docs);
        indexed = true;
        log.info("Corpus indexado: {} fragmentos", docs.size());
    }

    private List<RetrievedChunk> searchBlocking(String question) {
        SearchRequest request = SearchRequest.builder()
                .query(question)
                .topK(props.topK())
                .similarityThreshold(props.similarityThreshold())
                .build();
        return vectorStore.similaritySearch(request).stream()
                .map(d -> new RetrievedChunk(fromDocument(d), d.getScore() == null ? 0.0 : d.getScore()))
                .toList();
    }

    private Throwable toUnavailable(Throwable t) {
        if (t instanceof AssistantUnavailableException) {
            return t;
        }
        String reason = t instanceof RejectedExecutionException ? "scheduler saturado"
                : t instanceof TimeoutException ? "timeout de embeddings" : "fallo del proveedor de embeddings";
        // Solo la clase de la excepcion: el mensaje podria contener texto de la pregunta.
        log.warn("Recuperacion no disponible: {} ({})", reason, t.getClass().getSimpleName());
        return new AssistantUnavailableException(reason, t);
    }

    private static Document toDocument(ProtocolChunk c) {
        return Document.builder()
                .id(c.protocolId() + "#" + c.sectionId() + "@" + c.corpusVersion())
                .text(c.embeddingText())
                .metadata(Map.of(
                        PROTOCOL_ID, c.protocolId(),
                        PROTOCOL_TITLE, c.protocolTitle(),
                        SECTION_ID, c.sectionId(),
                        SECTION_TITLE, c.sectionTitle(),
                        CORPUS_VERSION, c.corpusVersion(),
                        BODY, c.body()))
                .build();
    }

    private static ProtocolChunk fromDocument(Document d) {
        Map<String, Object> m = d.getMetadata();
        return new ProtocolChunk(
                (String) m.get(PROTOCOL_ID), (String) m.get(PROTOCOL_TITLE),
                (String) m.get(SECTION_ID), (String) m.get(SECTION_TITLE),
                (String) m.get(CORPUS_VERSION), (String) m.get(BODY));
    }
}
