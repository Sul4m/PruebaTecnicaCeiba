package com.vidacruz.clinical_assistant_service.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.vidacruz.clinical_assistant_service.application.port.out.AnswerGenerationPort;
import com.vidacruz.clinical_assistant_service.application.port.out.ProtocolRetrievalPort;
import com.vidacruz.clinical_assistant_service.domain.ConsultaResultado;
import com.vidacruz.clinical_assistant_service.domain.FuenteProtocolo;
import com.vidacruz.clinical_assistant_service.domain.RetrievedChunk;
import reactor.core.publisher.Mono;

/**
 * Caso de uso RF-04A. Sin coincidencia relevante se abstiene sin invocar al modelo.
 * Las fuentes se derivan de la metadata de los fragmentos recuperados, jamas del texto generado.
 */
@Service
public class ConsultaProtocoloService {

    private final ProtocolRetrievalPort retrieval;
    private final AnswerGenerationPort generation;

    public ConsultaProtocoloService(ProtocolRetrievalPort retrieval, AnswerGenerationPort generation) {
        this.retrieval = retrieval;
        this.generation = generation;
    }

    public Mono<ConsultaResultado> consultar(String pregunta) {
        return retrieval.retrieve(pregunta).flatMap(context -> {
            if (context.isEmpty()) {
                return Mono.just(ConsultaResultado.sinProtocolo());
            }
            List<FuenteProtocolo> fuentes = fuentesDe(context);
            return generation.generate(pregunta, context)
                    .map(respuesta -> new ConsultaResultado(respuesta, fuentes));
        });
    }

    private static List<FuenteProtocolo> fuentesDe(List<RetrievedChunk> context) {
        Set<FuenteProtocolo> unicas = new LinkedHashSet<>();
        for (RetrievedChunk rc : context) {
            unicas.add(new FuenteProtocolo(rc.chunk().protocolId(), rc.chunk().sectionTitle()));
        }
        return List.copyOf(unicas);
    }
}
