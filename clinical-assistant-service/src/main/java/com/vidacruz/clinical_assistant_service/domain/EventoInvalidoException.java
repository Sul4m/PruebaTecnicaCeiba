package com.vidacruz.clinical_assistant_service.domain;

/** Mensaje no procesable (JSON invalido, campos faltantes, tipo o schemaVersion no soportados). Va al DLT. */
public class EventoInvalidoException extends RuntimeException {

    private final String motivo;

    public EventoInvalidoException(String motivo, String detalle) {
        super(detalle);
        this.motivo = motivo;
    }

    /** Clasificador de baja cardinalidad: {@code invalido}, {@code tipo_no_soportado}, {@code schema_no_soportado}. */
    public String motivo() {
        return motivo;
    }
}
