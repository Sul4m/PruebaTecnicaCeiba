package com.vidacruz.clinical_assistant_service.infrastructure.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import com.vidacruz.clinical_assistant_service.domain.EstadoPaciente;
import com.vidacruz.clinical_assistant_service.domain.EventoTriaje;
import com.vidacruz.clinical_assistant_service.domain.NivelTriaje;
import com.vidacruz.clinical_assistant_service.domain.ResultadoProyeccion;
import com.vidacruz.clinical_assistant_service.support.ProjectionContainers;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/** Reglas de la proyeccion contra un Redis real (script Lua). REQUIERE Docker. */
@Testcontainers(disabledWithoutDocker = true)
class RedisProyeccionContainersIT {

    static LettuceConnectionFactory factory;
    static ReactiveStringRedisTemplate redis;
    RedisProyeccionUrgencias projection;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                ProjectionContainers.REDIS.getHost(), ProjectionContainers.REDIS.getMappedPort(6379)));
        factory.setDatabase(3); // base propia: aislada de los ITs con contexto Spring (1 y 2)
        factory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(factory);
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    @BeforeEach
    void clean() {
        redis.getConnectionFactory().getReactiveConnection().serverCommands().flushDb().block();
        projection = new RedisProyeccionUrgencias(redis);
    }

    private static EventoTriaje ev(String patient, long version, NivelTriaje nivel, EstadoPaciente estado) {
        return new EventoTriaje(UUID.randomUUID().toString(), "x", patient, version, nivel, estado, false);
    }

    private ResultadoProyeccion apply(EventoTriaje e) {
        return projection.aplicar(e).block();
    }

    private long count(NivelTriaje n) {
        return projection.conteos().block().getOrDefault(n, 0L);
    }

    @Test
    void registeredAddsEscalatedAndInConsultationDoNotChangeAndDischargeSubtractsOnce() {
        String p = UUID.randomUUID().toString();
        assertThat(apply(ev(p, 1, NivelTriaje.CRITICO, EstadoPaciente.REGISTRADO))).isEqualTo(ResultadoProyeccion.APLICADO);
        assertThat(count(NivelTriaje.CRITICO)).isEqualTo(1);

        apply(ev(p, 2, NivelTriaje.CRITICO, EstadoPaciente.REGISTRADO)); // escalado
        assertThat(count(NivelTriaje.CRITICO)).isEqualTo(1);
        apply(ev(p, 3, NivelTriaje.CRITICO, EstadoPaciente.EN_CONSULTA));
        assertThat(count(NivelTriaje.CRITICO)).isEqualTo(1);

        EventoTriaje alta = ev(p, 4, NivelTriaje.CRITICO, EstadoPaciente.ALTA);
        assertThat(apply(alta)).isEqualTo(ResultadoProyeccion.APLICADO);
        assertThat(count(NivelTriaje.CRITICO)).isZero();

        // La misma alta reentregada y un alta distinta con version ya vista no vuelven a restar.
        assertThat(apply(alta)).isEqualTo(ResultadoProyeccion.DUPLICADO);
        assertThat(apply(ev(p, 4, NivelTriaje.CRITICO, EstadoPaciente.ALTA))).isEqualTo(ResultadoProyeccion.VERSION_ANTIGUA);
        assertThat(count(NivelTriaje.CRITICO)).isZero();
    }

    @Test
    void duplicateAndOldVersionsDoNotAlterCountsOrState() {
        String p = UUID.randomUUID().toString();
        EventoTriaje v1 = ev(p, 1, NivelTriaje.URGENTE, EstadoPaciente.REGISTRADO);
        apply(v1);
        apply(ev(p, 2, NivelTriaje.URGENTE, EstadoPaciente.EN_CONSULTA));

        assertThat(apply(v1)).isEqualTo(ResultadoProyeccion.DUPLICADO);
        // v1 "distinto" (otro eventId, otro nivel) llega tarde: se ignora por version.
        assertThat(apply(ev(p, 1, NivelTriaje.CRITICO, EstadoPaciente.REGISTRADO)))
                .isEqualTo(ResultadoProyeccion.VERSION_ANTIGUA);
        assertThat(count(NivelTriaje.URGENTE)).isEqualTo(1);
        assertThat(count(NivelTriaje.CRITICO)).isZero();
        assertThat(redis.opsForHash().get("{urgencias}:patient:" + p, "version").block()).isEqualTo("2");
    }

    @Test
    void skippedVersionAppliesFullSnapshotAndIsFlagged() {
        String p = UUID.randomUUID().toString();
        apply(ev(p, 1, NivelTriaje.CRITICO, EstadoPaciente.REGISTRADO));
        // Se pierden v2 y v3; llega v4 con otro nivel: resta del previo y suma al nuevo.
        assertThat(apply(ev(p, 4, NivelTriaje.URGENTE, EstadoPaciente.EN_CONSULTA)))
                .isEqualTo(ResultadoProyeccion.APLICADO_CON_SALTO);
        assertThat(count(NivelTriaje.CRITICO)).isZero();
        assertThat(count(NivelTriaje.URGENTE)).isEqualTo(1);

        // Paciente visto por primera vez con version > 1 (v1 perdida): tambien es salto.
        String q = UUID.randomUUID().toString();
        assertThat(apply(ev(q, 3, NivelTriaje.URGENTE_2, EstadoPaciente.REGISTRADO)))
                .isEqualTo(ResultadoProyeccion.APLICADO_CON_SALTO);
        assertThat(count(NivelTriaje.URGENTE_2)).isEqualTo(1);
    }

    @Test
    void dischargeOfUnseenPatientNeverGoesNegative() {
        String p = UUID.randomUUID().toString();
        assertThat(apply(ev(p, 3, NivelTriaje.CRITICO, EstadoPaciente.ALTA)))
                .isEqualTo(ResultadoProyeccion.APLICADO_CON_SALTO);
        assertThat(count(NivelTriaje.CRITICO)).isZero();
        // Un registro tardio del mismo paciente (v1) no lo "revive".
        assertThat(apply(ev(p, 1, NivelTriaje.CRITICO, EstadoPaciente.REGISTRADO)))
                .isEqualTo(ResultadoProyeccion.VERSION_ANTIGUA);
        assertThat(count(NivelTriaje.CRITICO)).isZero();
    }

    @Test
    void concurrentOutOfOrderEventsConvergeToTheLatestSnapshot() {
        String p = UUID.randomUUID().toString();
        List<EventoTriaje> events = new ArrayList<>();
        events.add(ev(p, 1, NivelTriaje.NO_URGENTE, EstadoPaciente.REGISTRADO));
        for (int v = 2; v <= 39; v++) {
            events.add(ev(p, v, NivelTriaje.NO_URGENTE, EstadoPaciente.EN_CONSULTA));
        }
        events.add(ev(p, 40, NivelTriaje.NO_URGENTE, EstadoPaciente.ALTA));
        Collections.shuffle(events, new Random(42));

        Flux.fromIterable(events).flatMap(e -> projection.aplicar(e).subscribeOn(Schedulers.parallel()), 16)
                .blockLast();

        Map<NivelTriaje, Long> counts = projection.conteos().block();
        assertThat(counts.getOrDefault(NivelTriaje.NO_URGENTE, 0L)).isZero();
        assertThat(redis.opsForHash().get("{urgencias}:patient:" + p, "estado").block()).isEqualTo("ALTA");
        assertThat(redis.opsForHash().get("{urgencias}:patient:" + p, "version").block()).isEqualTo("40");
    }

    @Test
    void severalPatientsAreCountedPerLevel() {
        for (int i = 0; i < 3; i++) {
            apply(ev(UUID.randomUUID().toString(), 1, NivelTriaje.CRITICO, EstadoPaciente.REGISTRADO));
        }
        apply(ev(UUID.randomUUID().toString(), 1, NivelTriaje.NO_URGENTE_2, EstadoPaciente.REGISTRADO));
        assertThat(count(NivelTriaje.CRITICO)).isEqualTo(3);
        assertThat(count(NivelTriaje.NO_URGENTE_2)).isEqualTo(1);
        assertThat(count(NivelTriaje.URGENTE)).isZero();
    }
}
