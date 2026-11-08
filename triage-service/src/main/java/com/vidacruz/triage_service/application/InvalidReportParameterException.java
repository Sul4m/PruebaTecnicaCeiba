package com.vidacruz.triage_service.application;

public class InvalidReportParameterException extends RuntimeException {

    private final String parameter;

    public InvalidReportParameterException(String parameter, String reason) {
        super("El parámetro '" + parameter + "' es inválido: " + reason);
        this.parameter = parameter;
    }

    public String parameter() {
        return parameter;
    }
}
