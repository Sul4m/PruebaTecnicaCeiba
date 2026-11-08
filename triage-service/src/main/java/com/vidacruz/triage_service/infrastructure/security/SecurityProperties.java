package com.vidacruz.triage_service.infrastructure.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "triage.security")
public record SecurityProperties(@NotBlank String apiKey) {

    @Override
    public String toString() {
        return "SecurityProperties[apiKey=****]";
    }
}
