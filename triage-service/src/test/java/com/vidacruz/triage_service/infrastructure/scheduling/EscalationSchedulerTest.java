package com.vidacruz.triage_service.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.awaitility.Awaitility.await;

import com.vidacruz.triage_service.application.EscalateOverduePatientsUseCase;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class EscalationSchedulerTest {

    private final EscalateOverduePatientsUseCase useCase = mock(EscalateOverduePatientsUseCase.class);

    private EscalationScheduler scheduler(Duration interval) {
        return new EscalationScheduler(useCase, new EscalationProperties(true, interval, 100));
    }

    @Test
    void failedCycleReturnsFalseWithoutPropagating() {
        when(useCase.escalateAll()).thenReturn(Mono.error(new IllegalStateException("boom")));
        StepVerifier.create(scheduler(Duration.ofSeconds(1)).runCycle()).expectNext(false).verifyComplete();
    }

    @Test
    void backoffGrowsAndClampsToMaxOfIntervalAndFiveMinutes() {
        EscalationScheduler s = scheduler(Duration.ofSeconds(30));
        assertThat(s.nextDelay(false)).isEqualTo(Duration.ofSeconds(30));
        assertThat(s.nextDelay(false)).isEqualTo(Duration.ofSeconds(60));
        assertThat(s.nextDelay(false)).isEqualTo(Duration.ofSeconds(120));
        assertThat(s.nextDelay(false)).isEqualTo(Duration.ofSeconds(240));
        assertThat(s.nextDelay(false)).isEqualTo(Duration.ofMinutes(5));
        for (int i = 0; i < 100; i++) {
            assertThat(s.nextDelay(false)).isEqualTo(Duration.ofMinutes(5));
        }
        EscalationScheduler big = scheduler(Duration.ofMinutes(10));
        assertThat(big.nextDelay(false)).isEqualTo(Duration.ofMinutes(10));
        assertThat(big.nextDelay(false)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void successResetsTheStreak() {
        EscalationScheduler s = scheduler(Duration.ofSeconds(30));
        s.nextDelay(false);
        s.nextDelay(false);
        assertThat(s.nextDelay(true)).isEqualTo(Duration.ofSeconds(30));
        assertThat(s.nextDelay(false)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void loopKeepsRunningAfterAFailedCycle() {
        AtomicInteger calls = new AtomicInteger();
        when(useCase.escalateAll()).thenAnswer(i -> calls.getAndIncrement() == 0
                ? Mono.error(new IllegalStateException("boom"))
                : Mono.just(0L));
        EscalationScheduler s = scheduler(Duration.ofMillis(20));
        s.start();
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> calls.get() >= 3);
        } finally {
            s.stop();
        }
    }
}
