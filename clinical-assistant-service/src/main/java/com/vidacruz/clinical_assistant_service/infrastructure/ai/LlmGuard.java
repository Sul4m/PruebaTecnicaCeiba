package com.vidacruz.clinical_assistant_service.infrastructure.ai;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.azure.core.exception.HttpResponseException;
import com.vidacruz.clinical_assistant_service.domain.AssistantUnavailableException;
import com.vidacruz.clinical_assistant_service.infrastructure.config.AssistantLlmProperties;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import reactor.core.publisher.Mono;

/**
 * Protege las llamadas al proveedor LLM: bulkhead (sin cola: rechazo inmediato), circuit breaker, metricas y
 * traduccion de cualquier fallo a {@link AssistantUnavailableException} (HTTP 503). El timeout lo aplica el
 * llamador sobre el {@code Mono} antes de pasarlo aqui, de modo que cuenta como fallo del circuito.
 * Metricas: {@code assistant.llm.calls} (timer), {@code assistant.llm.failures} y {@code assistant.llm.rejections}.
 */
public class LlmGuard {

    public static final String CHAT = "chat";
    public static final String EMBEDDINGS = "embeddings";

    private static final Logger log = LoggerFactory.getLogger(LlmGuard.class);

    private final AssistantLlmProperties props;
    private final MeterRegistry registry;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Map<String, Bulkhead> bulkheads = new ConcurrentHashMap<>();
    private final boolean enabled;

    public LlmGuard(AssistantLlmProperties props, MeterRegistry registry) {
        this.props = props;
        this.registry = registry;
        this.enabled = true;
    }

    private LlmGuard() {
        this.props = null;
        this.registry = null;
        this.enabled = false;
    }

    /** Sin proteccion: solo para pruebas unitarias de adaptadores que no verifican resiliencia. */
    public static LlmGuard disabled() {
        return new LlmGuard();
    }

    public CircuitBreaker circuitBreaker(String operation) {
        return breakers.computeIfAbsent(operation, op -> {
            var c = props.circuitBreaker();
            return CircuitBreaker.of("llm-" + op, CircuitBreakerConfig.custom()
                    .failureRateThreshold(c.failureRateThreshold())
                    .slidingWindowSize(c.slidingWindowSize())
                    .minimumNumberOfCalls(c.minimumNumberOfCalls())
                    .waitDurationInOpenState(c.waitDurationInOpenState())
                    .permittedNumberOfCallsInHalfOpenState(c.permittedCallsInHalfOpenState())
                    // Saturacion local del scheduler y errores 4xx (salvo 429): no indican proveedor caido.
                    .ignoreExceptions(RejectedExecutionException.class)
                    .recordException(t -> !isClientError(t))
                    .build());
        });
    }

    private Bulkhead bulkhead(String operation) {
        return bulkheads.computeIfAbsent(operation, op -> {
            var b = props.bulkhead();
            int max = CHAT.equals(op) ? b.chatMaxConcurrentCalls() : b.embeddingMaxConcurrentCalls();
            return Bulkhead.of("llm-" + op, BulkheadConfig.custom()
                    .maxConcurrentCalls(max).maxWaitDuration(b.maxWait()).build());
        });
    }

    public <T> Mono<T> protect(String operation, Mono<T> call) {
        if (!enabled) {
            return call;
        }
        Mono<T> timed = Mono.defer(() -> {
            Timer.Sample sample = Timer.start(registry);
            return call
                    .doOnSuccess(v -> sample.stop(timer(operation, v == null ? "empty" : "success")))
                    .doOnError(e -> sample.stop(timer(operation, "error")))
                    .doOnCancel(() -> sample.stop(timer(operation, "cancelled")));
        });
        return timed
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker(operation)))
                .transformDeferred(BulkheadOperator.of(bulkhead(operation)))
                .onErrorMap(t -> translate(operation, t));
    }

    /** 4xx del proveedor (filtro de contenido, 401/403, prompt invalido), excepto 429 que si cuenta. */
    static boolean isClientError(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            int status = -1;
            if (c instanceof WebClientResponseException w) {
                status = w.getStatusCode().value();
            } else if (c instanceof HttpResponseException h && h.getResponse() != null) {
                status = h.getResponse().getStatusCode();
            } else if (c instanceof NonTransientAiException) {
                return true;
            }
            if (status >= 400) {
                return status < 500 && status != 429;
            }
        }
        return false;
    }

    private Timer timer(String operation, String outcome) {
        return Timer.builder("assistant.llm.calls").description("Latencia de llamadas al proveedor LLM")
                .tag("operation", operation).tag("outcome", outcome).register(registry);
    }

    private Throwable translate(String operation, Throwable t) {
        if (t instanceof AssistantUnavailableException) {
            return t;
        }
        String reason;
        if (t instanceof CallNotPermittedException) {
            reject(operation, "circuit_open");
            reason = "circuito abierto";
        } else if (t instanceof BulkheadFullException) {
            reject(operation, "bulkhead_full");
            reason = "bulkhead saturado";
        } else if (t instanceof RejectedExecutionException) {
            reject(operation, "scheduler_full");
            reason = "scheduler saturado";
        } else {
            boolean timeout = t instanceof TimeoutException;
            registry.counter("assistant.llm.failures", "operation", operation,
                    "reason", timeout ? "timeout" : "error").increment();
            reason = timeout ? "timeout del proveedor" : "fallo del proveedor";
        }
        // Solo la clase: el mensaje podria contener texto de la pregunta.
        log.warn("LLM {} no disponible: {} ({})", operation, reason, t.getClass().getSimpleName());
        return new AssistantUnavailableException(reason + " (" + operation + ")", t);
    }

    private void reject(String operation, String reason) {
        registry.counter("assistant.llm.rejections", "operation", operation, "reason", reason).increment();
    }
}
