package com.vidacruz.clinical_assistant_service.domain;

/**
 * Fragmento inmutable del corpus: una seccion de un protocolo con su metadata.
 * Es la unica fuente de verdad para construir las citas ({@code fuentes}).
 */
public record ProtocolChunk(
        String protocolId,
        String protocolTitle,
        String sectionId,
        String sectionTitle,
        String corpusVersion,
        String body) {

    /** Texto que se embebe: titulo del protocolo + seccion + cuerpo. */
    public String embeddingText() {
        return protocolTitle + ". " + sectionTitle + ". " + body;
    }
}
