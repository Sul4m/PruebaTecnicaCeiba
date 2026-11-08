package com.vidacruz.clinical_assistant_service.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern PROTOCOL_ID = Pattern.compile("PROTO[\\s_\\-\\u2010-\\u2015]*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGITS = Pattern.compile("\\d+");

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
            Set<String> recuperados = new LinkedHashSet<>();
            fuentes.forEach(f -> recuperados.add(f.protocolo()));
            return generation.generate(pregunta, context)
                    .map(respuesta -> citaSoloRecuperados(respuesta, recuperados)
                            ? new ConsultaResultado(respuesta, fuentes)
                            : ConsultaResultado.sinProtocolo());
        });
    }

    /**
     * RN-05: la comprobacion cubre unicamente tokens {@code PROTO-n} (con separadores y guiones alternativos) del
     * texto generado; se comparan por valor numerico con los protocolos recuperados. Las fuentes siempre salen de
     * la metadata, nunca del texto.
     */
    static boolean citaSoloRecuperados(String respuesta, Set<String> recuperados) {
        Set<BigInteger> permitidos = new HashSet<>();
        for (String id : recuperados) {
            Matcher d = DIGITS.matcher(id);
            if (d.find()) {
                permitidos.add(new BigInteger(d.group()));
            }
        }
        Matcher m = PROTOCOL_ID.matcher(respuesta);
        while (m.find()) {
            if (!permitidos.contains(new BigInteger(m.group(1)))) {
                return false;
            }
        }
        return true;
    }

    private static List<FuenteProtocolo> fuentesDe(List<RetrievedChunk> context) {
        Set<FuenteProtocolo> unicas = new LinkedHashSet<>();
        for (RetrievedChunk rc : context) {
            unicas.add(new FuenteProtocolo(rc.chunk().protocolId(), rc.chunk().sectionTitle()));
        }
        return List.copyOf(unicas);
    }
}
