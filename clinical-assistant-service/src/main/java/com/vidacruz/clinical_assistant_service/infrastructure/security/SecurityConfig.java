package com.vidacruz.clinical_assistant_service.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.server.ServerWebExchange;

import com.vidacruz.clinical_assistant_service.infrastructure.config.AssistantSecurityProperties;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, AssistantSecurityProperties props) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .addFilterAt(new ApiKeyAuthenticationFilter(props.apiKey()), SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(ex -> ex
                        // Solo sondas de salud (no revelan datos). Metricas y demas, autenticados.
                        .pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyExchange().authenticated())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((exchange, e) -> status(exchange, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((exchange, e) -> status(exchange, HttpStatus.FORBIDDEN)))
                .build();
    }

    /** Respuesta con cuerpo vacio (no filtra informacion sobre la autenticacion). */
    private static Mono<Void> status(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }
}
