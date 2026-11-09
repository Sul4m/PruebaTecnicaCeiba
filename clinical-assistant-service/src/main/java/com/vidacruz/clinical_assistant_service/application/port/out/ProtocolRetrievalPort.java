package com.vidacruz.clinical_assistant_service.application.port.out;

import java.util.List;

import com.vidacruz.clinical_assistant_service.domain.RetrievedChunk;
import reactor.core.publisher.Mono;

/** Puerto de salida: busqueda semantica en el corpus (ya filtrada por umbral de relevancia). */
public interface ProtocolRetrievalPort {

    Mono<List<RetrievedChunk>> retrieve(String question);
}
