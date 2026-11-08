package com.vidacruz.clinical_assistant_service.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import com.vidacruz.clinical_assistant_service.domain.AssistantUnavailableException;
import com.vidacruz.clinical_assistant_service.domain.ProtocolChunk;
import com.vidacruz.clinical_assistant_service.domain.RetrievedChunk;
import com.vidacruz.clinical_assistant_service.infrastructure.ai.dev.DevEmbeddingModel;
import com.vidacruz.clinical_assistant_service.infrastructure.config.AssistantRagProperties;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class SpringAiProtocolRetrieverTest {

    static final String Q1 = "¿Cuáles son las medidas iniciales ante una crisis hipertensiva?";
    static final String Q2 = "¿Cuándo se administra el primer antibiótico en sepsis?";
    static final String Q3 = "¿Cuál es el tratamiento de primera línea para la diabetes tipo 2?";

    private final List<ProtocolChunk> corpus =
            new ProtocolCorpusLoader().load("classpath:corpus/{version}/*.md", "v1");
    private Scheduler scheduler;

    private AssistantRagProperties props(double threshold, int threads, int queue) {
        return new AssistantRagProperties("v1", "classpath:corpus/{version}/*.md", 3, threshold, 400, 2000,
                Duration.ofSeconds(5), Duration.ofSeconds(5), new AssistantRagProperties.Scheduler(threads, queue));
    }

    @BeforeEach
    void setUp() {
        scheduler = Schedulers.newBoundedElastic(2, 10, "rag-embedding-test", 10, true);
    }

    @AfterEach
    void tearDown() {
        scheduler.dispose();
    }

    private SpringAiProtocolRetriever retriever(SimpleVectorStore store, AssistantRagProperties p) {
        return new SpringAiProtocolRetriever(store, scheduler, p, corpus);
    }

    @Test
    void retrievesExpectedProtocolsAndAbstainsOnUnrelatedQuestion() {
        var r = retriever(SimpleVectorStore.builder(new DevEmbeddingModel()).build(), props(0.30, 2, 10));
        r.indexCorpus().block();

        List<RetrievedChunk> q1 = r.retrieve(Q1).block();
        List<RetrievedChunk> q2 = r.retrieve(Q2).block();
        List<RetrievedChunk> q3 = r.retrieve(Q3).block();

        assertThat(q1).isNotEmpty();
        assertThat(q1.get(0).chunk().protocolId()).isEqualTo("PROTO-001");
        assertThat(q1.get(0).chunk().sectionId()).isEqualTo("medidas-iniciales");
        assertThat(q2).isNotEmpty();
        assertThat(q2.get(0).chunk().protocolId()).isEqualTo("PROTO-002");
        assertThat(q2.get(0).chunk().sectionId()).isEqualTo("acciones-iniciales");
        assertThat(q3).isEmpty();
    }

    @Test
    void thresholdBoundaryIsInclusive() {
        var open = retriever(SimpleVectorStore.builder(new DevEmbeddingModel()).build(), props(0.0, 2, 10));
        double top = open.retrieve(Q1).block().get(0).score();

        var atScore = retriever(SimpleVectorStore.builder(new DevEmbeddingModel()).build(), props(top, 2, 10));
        var above = retriever(SimpleVectorStore.builder(new DevEmbeddingModel()).build(),
                props(Math.nextUp(top), 2, 10));
        assertThat(atScore.retrieve(Q1).block()).isNotEmpty();
        assertThat(above.retrieve(Q1).block()).isEmpty();
    }

    @Test
    void configuredThresholdSeparatesCoveredFromUncoveredBaselineQuestions() {
        var r = retriever(SimpleVectorStore.builder(new DevEmbeddingModel()).build(), props(0.0, 2, 10));
        double q1 = r.retrieve(Q1).block().get(0).score();
        double q2 = r.retrieve(Q2).block().get(0).score();
        double q3 = r.retrieve(Q3).block().stream().mapToDouble(RetrievedChunk::score).max().orElse(0.0);
        assertThat(q3).isLessThan(0.30).isLessThan(Math.min(q1, q2));
        assertThat(Math.min(q1, q2)).isGreaterThanOrEqualTo(0.30);
    }

    @Test
    void embeddingRunsOnDedicatedSchedulerNotCallerThread() {
        List<String> threads = new CopyOnWriteArrayList<>();
        var model = new DevEmbeddingModel() {
            @Override
            public float[] embed(String text) {
                threads.add(Thread.currentThread().getName());
                return super.embed(text);
            }
        };
        var r = retriever(SimpleVectorStore.builder(model).build(), props(0.30, 2, 10));
        r.indexCorpus().block();
        r.retrieve(Q1).block();

        assertThat(threads).isNotEmpty().allMatch(n -> n.startsWith("rag-embedding-test"));
    }

    @Test
    void saturatedSchedulerFailsFastWithUnavailable() throws Exception {
        scheduler.dispose();
        scheduler = Schedulers.newBoundedElastic(1, 1, "rag-embedding-test", 10, true);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        var blocking = new DevEmbeddingModel() {
            @Override
            public float[] embed(Document document) {
                return super.embed(document.getText()); // el indexado no bloquea; solo la consulta
            }

            @Override
            public float[] embed(String text) {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.embed(text);
            }
        };
        var r = retriever(SimpleVectorStore.builder(blocking).build(), props(0.30, 1, 1));
        r.indexCorpus().block();

        // Ocupa el unico hilo y la unica plaza de cola
        r.retrieve(Q1).subscribe(v -> { }, e -> { });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        r.retrieve(Q1).subscribe(v -> { }, e -> { });

        StepVerifier.create(r.retrieve(Q1))
                .expectError(AssistantUnavailableException.class)
                .verify(Duration.ofSeconds(5));
        release.countDown();
    }

    @Test
    void providerFailureMapsToUnavailable() {
        var failing = new DevEmbeddingModel() {
            @Override
            public float[] embed(String text) {
                throw new IllegalStateException("boom con texto sensible");
            }

            @Override
            public float[] embed(Document document) {
                throw new IllegalStateException("boom");
            }
        };
        var r = retriever(SimpleVectorStore.builder(failing).build(), props(0.30, 1, 5));

        StepVerifier.create(r.retrieve(Q1)).expectError(AssistantUnavailableException.class).verify();
    }

    @Test
    void failedIndexingIsRetriedByLaterQuery() {
        AtomicInteger calls = new AtomicInteger();
        var flaky = new DevEmbeddingModel() {
            @Override
            public float[] embed(Document document) {
                if (calls.getAndIncrement() == 0) {
                    throw new IllegalStateException("fallo inicial");
                }
                return super.embed(document);
            }
        };
        var r = retriever(SimpleVectorStore.builder(flaky).build(), props(0.30, 2, 10));

        StepVerifier.create(r.retrieve(Q1)).expectError(AssistantUnavailableException.class).verify();
        List<RetrievedChunk> second = r.retrieve(Q1).block();

        assertThat(second).isNotEmpty();
        assertThat(second.get(0).chunk().protocolId()).isEqualTo("PROTO-001");
        assertThat(r.isIndexed()).isTrue();
    }

    @Test
    void concurrentQueriesTriggerSingleIndexing() {
        AtomicInteger docEmbeds = new AtomicInteger();
        var slow = new DevEmbeddingModel() {
            @Override
            public float[] embed(Document document) {
                docEmbeds.incrementAndGet();
                try {
                    Thread.sleep(30);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.embed(document);
            }
        };
        var r = retriever(SimpleVectorStore.builder(slow).build(), props(0.30, 4, 20));

        List<List<RetrievedChunk>> results = Flux.range(0, 5).flatMap(i -> r.retrieve(Q1)).collectList().block();

        assertThat(results).hasSize(5).allSatisfy(l -> assertThat(l).isNotEmpty());
        assertThat(docEmbeds.get()).isEqualTo(corpus.size());
    }

    @Test
    void stalledEmbeddingTimesOutToUnavailable() {
        var stalled = new DevEmbeddingModel() {
            @Override
            public float[] embed(String text) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.embed(text);
            }
        };
        var p = new AssistantRagProperties("v1", "x", 3, 0.30, 400, 2000, Duration.ofSeconds(5),
                Duration.ofMillis(200), new AssistantRagProperties.Scheduler(2, 10));
        var r = retriever(SimpleVectorStore.builder(stalled).build(), p);

        StepVerifier.create(r.retrieve(Q1)).expectError(AssistantUnavailableException.class)
                .verify(Duration.ofSeconds(5));
    }
}
