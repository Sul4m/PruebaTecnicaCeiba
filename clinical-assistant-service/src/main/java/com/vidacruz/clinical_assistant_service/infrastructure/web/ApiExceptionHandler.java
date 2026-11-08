package com.vidacruz.clinical_assistant_service.infrastructure.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

import com.vidacruz.clinical_assistant_service.domain.AssistantUnavailableException;
import com.vidacruz.clinical_assistant_service.domain.ProyeccionNoDisponibleException;

/**
 * Errores como ProblemDetail. El detalle nunca incluye el texto de la pregunta (PII/clinico) ni se loguea.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(WebExchangeBindException.class)
    ResponseEntity<ProblemDetail> onValidation(WebExchangeBindException ex) {
        FieldError first = ex.getFieldErrors().stream().findFirst().orElse(null);
        String campo = first != null ? first.getField() : "pregunta";
        String detail = first != null && first.getDefaultMessage() != null
                ? first.getDefaultMessage() : "Solicitud invalida";
        return badRequest(campo, detail);
    }

    @ExceptionHandler(ServerWebInputException.class)
    ResponseEntity<ProblemDetail> onUnreadable(ServerWebInputException ex) {
        return badRequest("pregunta", "Cuerpo de la solicitud invalido: se espera {\"pregunta\": \"...\"}");
    }

    @ExceptionHandler(AssistantUnavailableException.class)
    ResponseEntity<ProblemDetail> onUnavailable(AssistantUnavailableException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "El asistente no esta disponible temporalmente. Intente nuevamente mas tarde.");
        pd.setTitle("Servicio no disponible");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(pd);
    }

    @ExceptionHandler(ProyeccionNoDisponibleException.class)
    ResponseEntity<ProblemDetail> onProjectionUnavailable(ProyeccionNoDisponibleException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "La vista de urgencias no esta disponible temporalmente. Intente nuevamente mas tarde.");
        pd.setTitle("Servicio no disponible");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(pd);
    }

    private static ResponseEntity<ProblemDetail> badRequest(String campo, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setTitle("Solicitud invalida");
        pd.setProperty("campo", campo);
        return ResponseEntity.badRequest().body(pd);
    }
}
