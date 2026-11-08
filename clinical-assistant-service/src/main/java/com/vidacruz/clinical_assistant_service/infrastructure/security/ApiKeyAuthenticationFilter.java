package com.vidacruz.clinical_assistant_service.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Autentica por cabecera {@code X-API-Key}. Si coincide, establece una autenticacion; si no, deja pasar sin
 * autenticar y el entry point responde 401 con cuerpo vacio. Comparacion en tiempo constante (digest SHA-256 de
 * ambos lados + {@link MessageDigest#isEqual}), sin filtrar la longitud de la clave. Nunca se loguea la clave.
 */
public class ApiKeyAuthenticationFilter implements WebFilter {

    public static final String HEADER = "X-API-Key";

    private final byte[] expectedDigest;

    public ApiKeyAuthenticationFilter(String expectedKey) {
        this.expectedDigest = sha256(expectedKey);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String provided = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (provided != null && MessageDigest.isEqual(expectedDigest, sha256(provided))) {
            var auth = UsernamePasswordAuthenticationToken.authenticated("assistant-client", null,
                    List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT")));
            return chain.filter(exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
        }
        return chain.filter(exchange);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
