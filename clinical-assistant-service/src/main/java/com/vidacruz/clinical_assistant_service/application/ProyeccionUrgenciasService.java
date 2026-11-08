package com.vidacruz.clinical_assistant_service.application;

import java.util.EnumMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.vidacruz.clinical_assistant_service.application.port.out.ProyeccionUrgenciasPort;
import com.vidacruz.clinical_assistant_service.domain.EstadoUrgencias;
import com.vidacruz.clinical_assistant_service.domain.EventoTriaje;
import com.vidacruz.clinical_assistant_service.domain.NivelTriaje;
import com.vidacruz.clinical_assistant_service.domain.ResultadoProyeccion;
import reactor.core.publisher.Mono;

@Service
public class ProyeccionUrgenciasService {

    private static final Logger log = LoggerFactory.getLogger(ProyeccionUrgenciasService.class);

    private final ProyeccionUrgenciasPort port;

    public ProyeccionUrgenciasService(ProyeccionUrgenciasPort port) {
        this.port = port;
    }

    public Mono<ResultadoProyeccion> aplicar(EventoTriaje evento) {
        return port.aplicar(evento);
    }

    /** Siempre incluye los cinco niveles; un conteo negativo (imposible por diseno) se acota a cero. */
    public Mono<EstadoUrgencias> estado() {
        return port.conteos().map(conteos -> {
            Map<NivelTriaje, Long> completo = new EnumMap<>(NivelTriaje.class);
            for (NivelTriaje nivel : NivelTriaje.values()) {
                long valor = conteos.getOrDefault(nivel, 0L);
                if (valor < 0) {
                    log.warn("Conteo negativo en la proyeccion (nivel={}): se acota a 0; posible corrupcion", nivel);
                }
                completo.put(nivel, Math.max(0L, valor));
            }
            return new EstadoUrgencias(completo);
        });
    }
}
