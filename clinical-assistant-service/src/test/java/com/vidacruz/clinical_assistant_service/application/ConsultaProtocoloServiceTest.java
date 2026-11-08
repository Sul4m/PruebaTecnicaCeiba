package com.vidacruz.clinical_assistant_service.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.vidacruz.clinical_assistant_service.application.port.out.AnswerGenerationPort;
import com.vidacruz.clinical_assistant_service.application.port.out.ProtocolRetrievalPort;
import com.vidacruz.clinical_assistant_service.domain.ConsultaResultado;
import com.vidacruz.clinical_assistant_service.domain.FuenteProtocolo;
import com.vidacruz.clinical_assistant_service.domain.ProtocolChunk;
import com.vidacruz.clinical_assistant_service.domain.RetrievedChunk;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ConsultaProtocoloServiceTest {

    private final ProtocolRetrievalPort retrieval = mock(ProtocolRetrievalPort.class);
    private final AnswerGenerationPort generation = mock(AnswerGenerationPort.class);
    private final ConsultaProtocoloService service = new ConsultaProtocoloService(retrieval, generation);

    private static RetrievedChunk chunk(String proto, String section) {
        return new RetrievedChunk(new ProtocolChunk(proto, "t", section.toLowerCase(), section, "v1", "cuerpo"), 0.9);
    }

    @Test
    void withoutRelevantContextAbstainsWithoutCallingModel() {
        when(retrieval.retrieve(any())).thenReturn(Mono.just(List.of()));

        StepVerifier.create(service.consultar("diabetes"))
                .assertNext(r -> {
                    assertThat(r.respuesta()).isEqualTo(ConsultaResultado.AVISO_SIN_PROTOCOLO);
                    assertThat(r.fuentes()).isEmpty();
                })
                .verifyComplete();
        verify(generation, never()).generate(any(), any());
    }

    @Test
    void sourcesComeFromRetrievedMetadataNotFromModelText() {
        when(retrieval.retrieve(any())).thenReturn(Mono.just(List.of(
                chunk("PROTO-001", "Medidas iniciales"), chunk("PROTO-001", "Medidas iniciales"),
                chunk("PROTO-001", "Definición"))));
        when(generation.generate(any(), any())).thenReturn(Mono.just("Segun PROTO-999 seccion inventada"));

        StepVerifier.create(service.consultar("q"))
                .assertNext(r -> {
                    assertThat(r.respuesta()).contains("PROTO-999");
                    assertThat(r.fuentes()).containsExactly(
                            new FuenteProtocolo("PROTO-001", "Medidas iniciales"),
                            new FuenteProtocolo("PROTO-001", "Definición"));
                })
                .verifyComplete();
    }
}
