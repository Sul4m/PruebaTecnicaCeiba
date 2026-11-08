package com.vidacruz.clinical_assistant_service.infrastructure.projection;

import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.vidacruz.clinical_assistant_service.application.port.out.ProyeccionUrgenciasPort;
import com.vidacruz.clinical_assistant_service.domain.EventoTriaje;
import com.vidacruz.clinical_assistant_service.domain.NivelTriaje;
import com.vidacruz.clinical_assistant_service.domain.ProyeccionNoDisponibleException;
import com.vidacruz.clinical_assistant_service.domain.ResultadoProyeccion;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import reactor.core.publisher.Mono;

/**
 * Proyeccion en Redis. Toda mutacion pasa por un unico script Lua (atomico en el servidor): guarda de version,
 * idempotencia por eventId y ajuste de conteos por diferencia de snapshot. Las claves comparten el hash tag
 * {@code {urgencias}} para que el script sea valido tambien en Redis Cluster.
 */
@Component
public class RedisProyeccionUrgencias implements ProyeccionUrgenciasPort {

    private static final Logger log = LoggerFactory.getLogger(RedisProyeccionUrgencias.class);

    static final String COUNTS_KEY = "{urgencias}:counts";

    private static final RedisScript<Long> APPLY_SCRIPT =
            RedisScript.of(new ClassPathResource("lua/apply-triage-event.lua"), Long.class);

    private final ReactiveStringRedisTemplate redis;

    public RedisProyeccionUrgencias(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<ResultadoProyeccion> aplicar(EventoTriaje e) {
        List<String> keys = List.of("{urgencias}:patient:" + e.patientId(),
                "{urgencias}:events:" + e.patientId(), COUNTS_KEY);
        List<String> args = List.of(e.eventId(), Long.toString(e.aggregateVersion()), e.nivel().name(),
                e.estado().name());
        return redis.execute(APPLY_SCRIPT, keys, args)
                .next()
                .map(RedisProyeccionUrgencias::resultado)
                .onErrorMap(RedisProyeccionUrgencias::noDisponible);
    }

    @Override
    public Mono<Map<NivelTriaje, Long>> conteos() {
        return redis.<String, String>opsForHash().entries(COUNTS_KEY)
                .collectList()
                .map(entries -> {
                    Map<NivelTriaje, Long> out = new EnumMap<>(NivelTriaje.class);
                    for (var entry : entries) {
                        try {
                            out.put(NivelTriaje.valueOf(entry.getKey()), Long.parseLong(entry.getValue()));
                        } catch (IllegalArgumentException ignored) {
                            // campo ajeno al contrato: se omite, nunca se expone
                            log.warn("Campo ajeno o no numerico en los conteos de la proyeccion; se omite");
                        }
                    }
                    return out;
                })
                .onErrorMap(RedisProyeccionUrgencias::noDisponible);
    }

    static ResultadoProyeccion resultado(Long code) {
        return switch (code.intValue()) {
            case 0 -> ResultadoProyeccion.APLICADO;
            case 1 -> ResultadoProyeccion.DUPLICADO;
            case 2 -> ResultadoProyeccion.VERSION_ANTIGUA;
            case 3 -> ResultadoProyeccion.APLICADO_CON_SALTO;
            default -> throw new IllegalStateException("Codigo de script desconocido: " + code);
        };
    }

    /** Solo conectividad/timeouts reales son "no disponible" (transitorio); lo demas es deterministico y se propaga. */
    private static Throwable noDisponible(Throwable t) {
        return esTransitorio(t) ? new ProyeccionNoDisponibleException(t) : t;
    }

    static boolean esTransitorio(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof ProyeccionNoDisponibleException || c instanceof RedisConnectionFailureException
                    || c instanceof QueryTimeoutException || c instanceof TransientDataAccessException
                    || c instanceof RedisConnectionException || c instanceof RedisCommandTimeoutException
                    || c instanceof TimeoutException || c instanceof IOException) {
                return true;
            }
        }
        return false;
    }
}
