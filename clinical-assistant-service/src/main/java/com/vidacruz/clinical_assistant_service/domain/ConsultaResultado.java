package com.vidacruz.clinical_assistant_service.domain;

import java.util.List;

public record ConsultaResultado(String respuesta, List<FuenteProtocolo> fuentes) {

    /** Aviso fijo de abstencion (RN-05): sin contenido clinico y sin fuentes. */
    public static final String AVISO_SIN_PROTOCOLO = "No existe un protocolo disponible para responder esta consulta.";

    /** Token que el modelo debe emitir (y nada mas) cuando los fragmentos no responden directamente a la pregunta. */
    public static final String TOKEN_SIN_PROTOCOLO = "SIN_PROTOCOLO";

    public static ConsultaResultado sinProtocolo() {
        return new ConsultaResultado(AVISO_SIN_PROTOCOLO, List.of());
    }
}
