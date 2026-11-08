package com.vidacruz.triage_service.infrastructure.web;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.vidacruz.triage_service.application.InvalidReportParameterException;
import com.vidacruz.triage_service.application.InvalidStatusTransitionException;
import com.vidacruz.triage_service.application.PatientNotFoundException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected Mono<ResponseEntity<Object>> handleWebExchangeBindException(WebExchangeBindException ex,
            HttpHeaders headers, HttpStatusCode status, ServerWebExchange exchange) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator
                        // Errores de campo antes que el de la restricción cruzada de clase.
                        .comparingInt((FieldError e) -> "DiastolicaMenorQueSistolica".equals(e.getCode()) ? 1 : 0)
                        .thenComparing(FieldError::getField)
                        .thenComparing(e -> String.valueOf(e.getCode()))
                        .thenComparing(e -> String.valueOf(e.getDefaultMessage())))
                .map(e -> Map.of("campo", e.getField(),
                        "mensaje", messageFor(e.getField(), e.getDefaultMessage())))
                .collect(Collectors.toList());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Solicitud inválida");
        if (errors.isEmpty()) {
            problem.setDetail("La solicitud no es válida");
        } else {
            problem.setDetail(errors.get(0).get("mensaje"));
            problem.setProperty("campo", errors.get(0).get("campo"));
            problem.setProperty("errores", errors);
        }
        return createResponseEntity(problem, headers, HttpStatus.BAD_REQUEST, exchange);
    }

    @Override
    protected Mono<ResponseEntity<Object>> handleServerWebInputException(ServerWebInputException ex,
            HttpHeaders headers, HttpStatusCode status, ServerWebExchange exchange) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Solicitud inválida");
        String pathVariable = pathVariableFrom(ex);
        if (pathVariable != null) {
            problem.setDetail(messageFor(pathVariable, "tiene un formato inválido"));
            problem.setProperty("campo", pathVariable);
            return createResponseEntity(problem, headers, HttpStatus.BAD_REQUEST, exchange);
        }
        String campo = pathFrom(ex);
        if (campo != null) {
            problem.setDetail(messageFor(campo, "tiene un formato o tipo de dato inválido"));
            problem.setProperty("campo", campo);
        } else {
            problem.setDetail("El cuerpo de la solicitud está ausente o tiene un JSON mal formado");
        }
        return createResponseEntity(problem, headers, HttpStatus.BAD_REQUEST, exchange);
    }

    @ExceptionHandler(InvalidReportParameterException.class)
    ResponseEntity<ProblemDetail> handleInvalidReportParameter(InvalidReportParameterException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Solicitud inválida");
        problem.setProperty("campo", ex.parameter());
        // Explícito: el stream SSE produce text/event-stream y el error no debe codificarse como evento.
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    @ExceptionHandler(PatientNotFoundException.class)
    ProblemDetail handlePatientNotFound(PatientNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Paciente no encontrado");
        return problem;
    }

    @ExceptionHandler(InvalidStatusTransitionException.class)
    ProblemDetail handleInvalidTransition(InvalidStatusTransitionException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Transición de estado inválida");
        problem.setProperty("estadoActual", ex.current());
        problem.setProperty("estadoSolicitado", ex.requested());
        return problem;
    }

    private static String pathVariableFrom(ServerWebInputException ex) {
        MethodParameter parameter = ex.getMethodParameter();
        if (parameter == null) {
            return null;
        }
        PathVariable annotation = parameter.getParameterAnnotation(PathVariable.class);
        if (annotation == null) {
            return null;
        }
        return annotation.value().isEmpty() ? parameter.getParameterName() : annotation.value();
    }

    private static String messageFor(String campo, String reason) {
        return "El campo '" + campo + "' es inválido: " + reason;
    }

    private static String pathFrom(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof JsonMappingException jme && !jme.getPath().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonMappingException.Reference ref : jme.getPath()) {
                    if (ref.getFieldName() != null) {
                        if (sb.length() > 0) {
                            sb.append('.');
                        }
                        sb.append(ref.getFieldName());
                    }
                }
                return sb.length() > 0 ? sb.toString() : null;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }
}
