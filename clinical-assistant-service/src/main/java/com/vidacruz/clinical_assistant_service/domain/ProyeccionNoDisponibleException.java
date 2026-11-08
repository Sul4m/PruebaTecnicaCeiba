package com.vidacruz.clinical_assistant_service.domain;

public class ProyeccionNoDisponibleException extends RuntimeException {

    public ProyeccionNoDisponibleException(Throwable cause) {
        super("Proyeccion de urgencias no disponible", cause);
    }
}
