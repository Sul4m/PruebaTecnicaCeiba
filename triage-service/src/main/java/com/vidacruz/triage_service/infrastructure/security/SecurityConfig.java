package com.vidacruz.triage_service.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    static final String API_KEY_HEADER = "X-API-Key";

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, SecurityProperties props) {
        HttpStatusServerEntryPoint entryPoint = new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED);

        AuthenticationWebFilter apiKeyFilter = new AuthenticationWebFilter(apiKeyAuthenticationManager(props));
        apiKeyFilter.setServerAuthenticationConverter(exchange -> {
            List<String> values = exchange.getRequest().getHeaders().get(API_KEY_HEADER);
            if (values == null || values.size() != 1) {
                return Mono.empty();
            }
            return Mono.just(new UsernamePasswordAuthenticationToken("api-key", values.get(0)));
        });
        apiKeyFilter.setRequiresAuthenticationMatcher(new NegatedServerWebExchangeMatcher(
                ServerWebExchangeMatchers.pathMatchers("/actuator/health/**")));
        apiKeyFilter.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());
        apiKeyFilter.setAuthenticationFailureHandler(new ServerAuthenticationEntryPointFailureHandler(entryPoint));

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(c -> c.requestCache(
                        org.springframework.security.web.server.savedrequest.NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .addFilterAt(apiKeyFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(auth -> auth
                        .pathMatchers("/actuator/health/**").permitAll()
                        .anyExchange().authenticated())
                .build();
    }

    private ReactiveAuthenticationManager apiKeyAuthenticationManager(SecurityProperties props) {
        byte[] expected = props.apiKey().getBytes(StandardCharsets.UTF_8);
        return authentication -> {
            byte[] provided = String.valueOf(authentication.getCredentials()).getBytes(StandardCharsets.UTF_8);
            // Comparación en tiempo constante (MessageDigest.isEqual).
            if (!MessageDigest.isEqual(expected, provided)) {
                return Mono.error(new BadCredentialsException("Invalid API key"));
            }
            AbstractAuthenticationToken ok = UsernamePasswordAuthenticationToken.authenticated(
                    "api-key", null, AuthorityUtils.createAuthorityList("ROLE_API"));
            return Mono.just(ok);
        };
    }
}
