package com.vidacruz.clinical_assistant_service.application.port.out;

import java.util.List;

import com.vidacruz.clinical_assistant_service.domain.RetrievedChunk;
import reactor.core.publisher.Mono;

/** Puerto de salida: redaccion de la respuesta usando unicamente el contexto recuperado. */
public interface AnswerGenerationPort {

    Mono<String> generate(String question, List<RetrievedChunk> context);
}
