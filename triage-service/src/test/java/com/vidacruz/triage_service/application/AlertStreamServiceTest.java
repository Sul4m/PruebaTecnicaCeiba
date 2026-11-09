package com.vidacruz.triage_service.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage_service.domain.MedicalAlert;
import com.vidacruz.triage_service.domain.TriageLevel;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AlertStreamServiceTest {

    static class FakeAlerts implements AlertRepositoryPort {
        final List<MedicalAlert> rows = new CopyOnWriteArrayList<>();
        final List<Long> queriedCursors = new CopyOnWriteArrayList<>();
        volatile RuntimeException failure;

        void add(long... cursors) {
            for (long c : cursors) {
                rows.add(new MedicalAlert(UUID.randomUUID(), c, UUID.randomUUID(), TriageLevel.CRITICO,
                        Instant.EPOCH));
            }
        }

        @Override
        public Flux<MedicalAlert> appendAll(List<PatientRepositoryPort.EscalatedPatient> e, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<MedicalAlert> findAfter(long cursor, int limit) {
            return Flux.defer(() -> {
                queriedCursors.add(cursor);
                if (failure != null) {
                    return Flux.error(failure);
                }
                return Flux.fromIterable(new ArrayList<>(rows)).filter(a -> a.deliveryCursor() > cursor)
                        .take(limit);
            });
        }

        @Override
        public Mono<Long> findMaxCursor() {
            return Mono.fromSupplier(() -> rows.stream().mapToLong(MedicalAlert::deliveryCursor).max().orElse(0));
        }
    }

    private static List<Long> cursors(List<MedicalAlert> list) {
        return list.stream().map(MedicalAlert::deliveryCursor).collect(Collectors.toList());
    }

    @Test
    void fullBatchesAreDrainedWithoutWaiting() {
        FakeAlerts fake = new FakeAlerts();
        fake.add(LongStream.rangeClosed(1, 6).toArray());
        // Poll de 1 hora: si esperara entre lotes llenos, el test no terminaría.
        AlertStreamService service = new AlertStreamService(fake, Duration.ofHours(1), 2);

        StepVerifier.create(service.stream(0L).take(6).map(MedicalAlert::deliveryCursor))
                .expectNext(1L, 2L, 3L, 4L, 5L, 6L)
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        assertThat(fake.queriedCursors).startsWith(0L, 2L, 4L);
    }

    @Test
    void cursorAdvancesAndNoRowIsRepeated() {
        FakeAlerts fake = new FakeAlerts();
        fake.add(1, 2, 3);
        AlertStreamService service = new AlertStreamService(fake, Duration.ofMillis(20), 100);

        StepVerifier.create(service.stream(1L).map(MedicalAlert::deliveryCursor))
                .expectNext(2L, 3L)
                .then(() -> fake.add(4))
                .expectNext(4L)
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertThat(fake.queriedCursors).first().isEqualTo(1L);
        assertThat(fake.queriedCursors).contains(3L);
    }

    @Test
    void withoutLastEventIdOnlyNewAlertsAreDelivered() {
        FakeAlerts fake = new FakeAlerts();
        fake.add(1, 2, 3);
        AlertStreamService service = new AlertStreamService(fake, Duration.ofMillis(20), 100);

        StepVerifier.create(service.stream(null).map(MedicalAlert::deliveryCursor))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(150))
                .then(() -> fake.add(4))
                .expectNext(4L)
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void eachConnectionHasItsOwnCursor() {
        FakeAlerts fake = new FakeAlerts();
        fake.add(1, 2, 3);
        AlertStreamService service = new AlertStreamService(fake, Duration.ofMillis(20), 100);

        List<MedicalAlert> a = service.stream(0L).take(3).collectList().block(Duration.ofSeconds(5));
        List<MedicalAlert> b = service.stream(1L).take(2).collectList().block(Duration.ofSeconds(5));

        assertThat(cursors(a)).containsExactly(1L, 2L, 3L);
        assertThat(cursors(b)).containsExactly(2L, 3L);
    }

    @Test
    void databaseErrorPropagatesAndEndsTheStream() {
        FakeAlerts fake = new FakeAlerts();
        fake.failure = new IllegalStateException("db down");
        AlertStreamService service = new AlertStreamService(fake, Duration.ofMillis(20), 100);

        StepVerifier.create(service.stream(0L))
                .expectErrorMessage("db down")
                .verify(Duration.ofSeconds(5));
    }
}
