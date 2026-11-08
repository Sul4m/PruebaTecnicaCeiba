package com.vidacruz.triage_service.infrastructure.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = DiastolicaMenorQueSistolicaValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DiastolicaMenorQueSistolica {
    String message() default "debe ser menor que la presión sistólica";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
