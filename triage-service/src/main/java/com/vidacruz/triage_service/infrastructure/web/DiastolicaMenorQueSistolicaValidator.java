package com.vidacruz.triage_service.infrastructure.web;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

class DiastolicaMenorQueSistolicaValidator
        implements ConstraintValidator<DiastolicaMenorQueSistolica, SignosVitalesRequest> {

    @Override
    public boolean isValid(SignosVitalesRequest value, ConstraintValidatorContext context) {
        if (value == null || value.presionSistolica() == null || value.presionDiastolica() == null) {
            return true; // los nulos los reporta @NotNull en cada campo
        }
        if (value.presionDiastolica() < value.presionSistolica()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("presionDiastolica")
                .addConstraintViolation();
        return false;
    }
}
