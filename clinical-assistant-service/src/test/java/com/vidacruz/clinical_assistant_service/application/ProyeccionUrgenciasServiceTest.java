package com.vidacruz.clinical_assistant_service.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vidacruz.clinical_assistant_service.application.port.out.ProyeccionUrgenciasPort;
import com.vidacruz.clinical_assistant_service.domain.NivelTriaje;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ProyeccionUrgenciasServiceTest {

    private final ProyeccionUrgenciasPort port = mock(ProyeccionUrgenciasPort.class);
    private final ProyeccionUrgenciasService service = new ProyeccionUrgenciasService(port);

    @Test
    void includesEveryLevelWithZerosWhenProjectionIsEmpty() {
        when(port.conteos()).thenReturn(Mono.just(Map.of()));
        StepVerifier.create(service.estado()).assertNext(e -> {
            assertThat(e.pacientesActivos()).containsOnlyKeys(NivelTriaje.values());
            assertThat(e.pacientesActivos().values()).containsOnly(0L);
            assertThat(e.totalActivos()).isZero();
        }).verifyComplete();
    }

    @Test
    void fillsMissingLevelsAndClampsNegativeCounts() {
        when(port.conteos()).thenReturn(Mono.just(Map.of(NivelTriaje.CRITICO, 2L, NivelTriaje.URGENTE, -1L)));
        StepVerifier.create(service.estado()).assertNext(e -> {
            assertThat(e.pacientesActivos().get(NivelTriaje.CRITICO)).isEqualTo(2L);
            assertThat(e.pacientesActivos().get(NivelTriaje.URGENTE)).isZero();
            assertThat(e.pacientesActivos().get(NivelTriaje.NO_URGENTE_2)).isZero();
            assertThat(e.totalActivos()).isEqualTo(2L);
        }).verifyComplete();
    }
}
