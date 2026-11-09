package com.vidacruz.clinical_assistant_service.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        return chunk(proto, section, "Medir la presion arterial en reposo. Administrar antibiotico en la primera hora.");
    }

    private static RetrievedChunk chunk(String proto, String section, String body) {
        return new RetrievedChunk(new ProtocolChunk(proto, "t", section.toLowerCase(), section, "v1", body), 0.9);
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
                chunk("PROTO-001", "Medidas iniciales", "Medir la presion arterial en reposo."),
                chunk("PROTO-001", "Medidas iniciales", "Medir la presion arterial en reposo."),
                chunk("PROTO-001", "Definición", "Presion arterial elevada con dano de organo."),
                chunk("PROTO-001", "Contraindicaciones", "Nifedipino sublingual."))));
        when(generation.generate(any(), any()))
                .thenReturn(Mono.just("Segun PROTO-001, medir la presion arterial elevada en reposo."));

        StepVerifier.create(service.consultar("q"))
                .assertNext(r -> {
                    assertThat(r.respuesta()).contains("PROTO-001");
                    assertThat(r.fuentes()).containsExactly(
                            new FuenteProtocolo("PROTO-001", "Medidas iniciales"),
                            new FuenteProtocolo("PROTO-001", "Definición"));
                })
                .verifyComplete();
    }

    @Test
    void answerWithoutProtocolMentionKeepsRetrievedSources() {
        when(retrieval.retrieve(any())).thenReturn(Mono.just(List.of(chunk("PROTO-002", "Acciones iniciales"))));
        when(generation.generate(any(), any())).thenReturn(Mono.just("Administrar antibiotico en la primera hora."));

        StepVerifier.create(service.consultar("q"))
                .assertNext(r -> assertThat(r.fuentes()).containsExactly(
                        new FuenteProtocolo("PROTO-002", "Acciones iniciales")))
                .verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Segun PROTO-999 seccion inventada",
            "Ver PROTO-001 y tambien PROTO-003.",
            "segun proto-777",
            "PROTO-0011 no existe",
            "Segun PROTO 999",
            "Segun PROTO_999",
            "Segun PROTO\u2013999",
            "Segun PROTO\u2011999"})
    void answerCitingProtocolNotRetrievedIsDiscardedAndAbstains(String modelText) {
        when(retrieval.retrieve(any())).thenReturn(Mono.just(List.of(chunk("PROTO-001", "Medidas iniciales"))));
        when(generation.generate(any(), any())).thenReturn(Mono.just(modelText));

        StepVerifier.create(service.consultar("q"))
                .assertNext(r -> {
                    assertThat(r.respuesta()).isEqualTo(ConsultaResultado.AVISO_SIN_PROTOCOLO);
                    assertThat(r.fuentes()).isEmpty();
                })
                .verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Segun PROTO-1, medir.", "Segun proto-001, medir.", "Segun PROTO 001, medir."})
    void equivalentWritingsOfRetrievedProtocolAreKept(String modelText) {
        when(retrieval.retrieve(any())).thenReturn(Mono.just(List.of(chunk("PROTO-001", "Medidas iniciales"))));
        when(generation.generate(any(), any())).thenReturn(Mono.just(modelText));

        StepVerifier.create(service.consultar("q"))
                .assertNext(r -> {
                    assertThat(r.respuesta()).isEqualTo(modelText);
                    assertThat(r.fuentes()).containsExactly(new FuenteProtocolo("PROTO-001", "Medidas iniciales"));
                })
                .verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SIN_PROTOCOLO", "  SIN_PROTOCOLO  ", "SIN_PROTOCOLO.", "sin_protocolo", "\nSIN_PROTOCOLO\n", "", "   "})
    void modelTokenOrEmptyOutputAbstainsWithExactNoticeAndNoSources(String modelText) {
        when(retrieval.retrieve(any())).thenReturn(Mono.just(List.of(chunk("PROTO-003", "Rehidratacion"))));
        when(generation.generate(any(), any())).thenReturn(Mono.just(modelText));

        StepVerifier.create(service.consultar("diarrea en adultos"))
                .assertNext(r -> {
                    assertThat(r.respuesta()).isEqualTo(ConsultaResultado.AVISO_SIN_PROTOCOLO);
                    assertThat(r.fuentes()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void emptyGenerationAbstains() {
        when(retrieval.retrieve(any())).thenReturn(Mono.just(List.of(chunk("PROTO-003", "Rehidratacion"))));
        when(generation.generate(any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(service.consultar("q"))
                .assertNext(r -> assertThat(r.fuentes()).isEmpty())
                .verifyComplete();
    }

    @Test
    void answerWithoutLexicalSupportAbstainsEvenWithSources() {
        when(retrieval.retrieve(any())).thenReturn(Mono.just(List.of(chunk("PROTO-001", "Medidas iniciales"))));
        when(generation.generate(any(), any())).thenReturn(Mono.just(
                "No hay información en los fragmentos sobre el manejo de un infarto agudo de miocardio."));

        StepVerifier.create(service.consultar("infarto"))
                .assertNext(r -> {
                    assertThat(r.respuesta()).isEqualTo(ConsultaResultado.AVISO_SIN_PROTOCOLO);
                    assertThat(r.fuentes()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void answerMerelyMentioningTokenInsideTextIsNotAbstention() {
        when(retrieval.retrieve(any())).thenReturn(Mono.just(List.of(chunk("PROTO-001", "Medidas iniciales"))));
        when(generation.generate(any(), any())).thenReturn(Mono.just("Medir la presion; no es un caso SIN_PROTOCOLO."));

        StepVerifier.create(service.consultar("q"))
                .assertNext(r -> assertThat(r.fuentes()).hasSize(1))
                .verifyComplete();
    }
}
