package com.vidacruz.clinical_assistant_service.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.vidacruz.clinical_assistant_service.domain.AssistantUnavailableException;
import com.vidacruz.clinical_assistant_service.infrastructure.config.AssistantLlmProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class LlmGuardTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private LlmGuard guard(int minCalls, int chatBulkhead) {
        return new LlmGuard(new AssistantLlmProperties(
                new AssistantLlmProperties.CircuitBreaker(50, minCalls, minCalls, Duration.ofSeconds(30), 1),
                new AssistantLlmProperties.Bulkhead(chatBulkhead, 8, Duration.ZERO)), registry);
    }

    private double count(String name, String tagKey, String tagValue) {
        var c = registry.find(name).tag(tagKey, tagValue).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void providerErrorBecomesUnavailableAndIsCounted() {
        var guard = guard(5, 4);
        StepVerifier.create(guard.protect(LlmGuard.CHAT, Mono.<String>error(new IllegalStateException("boom"))))
                .expectError(AssistantUnavailableException.class).verify();
        assertThat(count("assistant.llm.failures", "reason", "error")).isEqualTo(1);
        assertThat(registry.find("assistant.llm.calls").tag("outcome", "error").timer().count()).isEqualTo(1);
    }

    @Test
    void timeoutIsCountedAsTimeoutFailure() {
        var guard = guard(5, 4);
        StepVerifier.create(guard.protect(LlmGuard.CHAT, Mono.<String>error(new TimeoutException("t"))))
                .expectError(AssistantUnavailableException.class).verify();
        assertThat(count("assistant.llm.failures", "reason", "timeout")).isEqualTo(1);
    }

    @Test
    void successRecordsLatencyTimer() {
        var guard = guard(5, 4);
        StepVerifier.create(guard.protect(LlmGuard.CHAT, Mono.just("ok"))).expectNext("ok").verifyComplete();
        assertThat(registry.find("assistant.llm.calls").tag("operation", "chat").tag("outcome", "success")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void circuitOpensAfterFailuresAndRejectsFastWithoutCallingProvider() {
        var guard = guard(3, 4);
        var calls = new AtomicInteger();
        Mono<String> failing = Mono.defer(() -> {
            calls.incrementAndGet();
            return Mono.error(new IllegalStateException("down"));
        });
        for (int i = 0; i < 3; i++) {
            StepVerifier.create(guard.protect(LlmGuard.CHAT, failing))
                    .expectError(AssistantUnavailableException.class).verify();
        }
        assertThat(guard.circuitBreaker(LlmGuard.CHAT).getState()).isEqualTo(CircuitBreaker.State.OPEN);

        StepVerifier.create(guard.protect(LlmGuard.CHAT, failing))
                .expectError(AssistantUnavailableException.class).verify();
        assertThat(calls).hasValue(3);
        assertThat(count("assistant.llm.rejections", "reason", "circuit_open")).isEqualTo(1);
        // El circuito de embeddings es independiente del de chat.
        assertThat(guard.circuitBreaker(LlmGuard.EMBEDDINGS).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void saturatedBulkheadRejectsImmediatelyWithoutQueueing() {
        var guard = guard(5, 1);
        var calls = new AtomicInteger();
        Mono<String> hanging = Mono.defer(() -> {
            calls.incrementAndGet();
            return Mono.never();
        });
        var first = guard.protect(LlmGuard.CHAT, hanging).subscribe();

        StepVerifier.create(guard.protect(LlmGuard.CHAT, hanging))
                .expectError(AssistantUnavailableException.class).verify(Duration.ofSeconds(1));
        assertThat(calls).hasValue(1);
        assertThat(count("assistant.llm.rejections", "reason", "bulkhead_full")).isEqualTo(1);
        first.dispose();
    }

    @Test
    void emptyResultIsNotAFailure() {
        var guard = guard(5, 4);
        StepVerifier.create(guard.protect(LlmGuard.CHAT, Mono.<String>empty())).verifyComplete();
        assertThat(registry.find("assistant.llm.failures").counters()).isEmpty();
    }

    private static Mono<String> http(int status) {
        return Mono.error(org.springframework.web.reactive.function.client.WebClientResponseException
                .create(status, "x", org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));
    }

    @Test
    void clientErrors4xxStill503ButDoNotOpenTheCircuit() {
        var guard = guard(3, 4);
        for (int i = 0; i < 6; i++) {
            StepVerifier.create(guard.protect(LlmGuard.CHAT, http(i % 2 == 0 ? 400 : 403)))
                    .expectError(AssistantUnavailableException.class).verify();
        }
        assertThat(guard.circuitBreaker(LlmGuard.CHAT).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void status429And5xxCountAsCircuitFailures() {
        var guard = guard(3, 4);
        StepVerifier.create(guard.protect(LlmGuard.CHAT, http(429))).expectError().verify();
        StepVerifier.create(guard.protect(LlmGuard.CHAT, http(503))).expectError().verify();
        StepVerifier.create(guard.protect(LlmGuard.CHAT, http(500))).expectError().verify();
        assertThat(guard.circuitBreaker(LlmGuard.CHAT).getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void cancellationIsRecordedInTimerAsCancelled() {
        var guard = guard(5, 4);
        guard.protect(LlmGuard.CHAT, Mono.<String>never()).subscribe().dispose();
        assertThat(registry.find("assistant.llm.calls").tag("outcome", "cancelled").timer().count()).isEqualTo(1);
    }

    @Test
    void emptyResultIsTaggedEmptyNotSuccess() {
        var guard = guard(5, 4);
        StepVerifier.create(guard.protect(LlmGuard.CHAT, Mono.<String>empty())).verifyComplete();
        assertThat(registry.find("assistant.llm.calls").tag("outcome", "empty").timer().count()).isEqualTo(1);
        assertThat(registry.find("assistant.llm.calls").tag("outcome", "success").timer()).isNull();
    }

    @Test
    void schedulerSaturationIs503WithSchedulerFullCounterAndCircuitStaysClosed() {
        var guard = guard(1, 4);
        for (int i = 0; i < 3; i++) {
            StepVerifier.create(guard.protect(LlmGuard.EMBEDDINGS,
                    Mono.<String>error(new java.util.concurrent.RejectedExecutionException("full"))))
                    .expectError(AssistantUnavailableException.class).verify();
        }
        assertThat(count("assistant.llm.rejections", "reason", "scheduler_full")).isEqualTo(3);
        assertThat(guard.circuitBreaker(LlmGuard.EMBEDDINGS).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void embeddingsBulkheadSaturationRejectsImmediately() {
        var guard = new LlmGuard(new AssistantLlmProperties(
                new AssistantLlmProperties.CircuitBreaker(50, 5, 5, Duration.ofSeconds(30), 1),
                new AssistantLlmProperties.Bulkhead(4, 1, Duration.ZERO)), registry);
        var first = guard.protect(LlmGuard.EMBEDDINGS, Mono.never()).subscribe();
        StepVerifier.create(guard.protect(LlmGuard.EMBEDDINGS, Mono.never()))
                .expectError(AssistantUnavailableException.class).verify(Duration.ofSeconds(1));
        assertThat(registry.find("assistant.llm.rejections").tag("operation", "embeddings")
                .tag("reason", "bulkhead_full").counter().count()).isEqualTo(1);
        first.dispose();
    }
}
