package com.vidacruz.clinical_assistant_service.infrastructure.ai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import com.vidacruz.clinical_assistant_service.domain.ProtocolChunk;

/**
 * Lee el corpus versionado ({@code corpus/<version>/PROTO-XXX.md}) y lo divide por protocolo y seccion.
 * Formato: {@code # PROTO-XXX — Titulo} y secciones {@code ## Nombre}.
 */
public class ProtocolCorpusLoader {

    private static final Pattern H1 = Pattern.compile("^#\\s+(PROTO-\\d{3})\\s+[—–-]\\s+(.+)$");
    private static final Pattern H2 = Pattern.compile("^##\\s+(.+)$");

    private final ResourcePatternResolver resolver;

    public ProtocolCorpusLoader() {
        this(new PathMatchingResourcePatternResolver());
    }

    public ProtocolCorpusLoader(ResourcePatternResolver resolver) {
        this.resolver = resolver;
    }

    public List<ProtocolChunk> load(String locationPattern, String corpusVersion) {
        String pattern = locationPattern.replace("{version}", corpusVersion);
        try {
            Resource[] resources = resolver.getResources(pattern);
            List<ProtocolChunk> chunks = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (Resource r : Arrays.stream(resources)
                    .sorted(Comparator.comparing(x -> String.valueOf(x.getFilename()))).toList()) {
                List<ProtocolChunk> parsed = parse(read(r), corpusVersion);
                if (parsed.isEmpty()) {
                    throw new IllegalStateException("Archivo de corpus sin fragmentos: " + r.getFilename());
                }
                for (ProtocolChunk c : parsed) {
                    if (!ids.add(c.protocolId() + "#" + c.sectionId() + "@" + c.corpusVersion())) {
                        throw new IllegalStateException("Fragmento duplicado en el corpus: " + c.protocolId()
                                + "#" + c.sectionId());
                    }
                }
                chunks.addAll(parsed);
            }
            if (chunks.isEmpty()) {
                throw new IllegalStateException("Corpus vacio o no encontrado: " + pattern);
            }
            return List.copyOf(chunks);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el corpus " + pattern, e);
        }
    }

    static List<ProtocolChunk> parse(String markdown, String corpusVersion) {
        if (markdown.startsWith("\uFEFF")) {
            markdown = markdown.substring(1);
        }
        List<ProtocolChunk> chunks = new ArrayList<>();
        String protocolId = null;
        String protocolTitle = null;
        String sectionTitle = null;
        StringBuilder body = new StringBuilder();
        for (String line : markdown.split("\\R")) {
            var h1 = H1.matcher(line);
            var h2 = H2.matcher(line);
            if (h1.matches()) {
                if (protocolId != null) {
                    throw new IllegalStateException("Mas de un protocolo (H1) en un mismo archivo: " + h1.group(1));
                }
                requireNoStray(body, "antes del primer H1");
                protocolId = h1.group(1);
                protocolTitle = h1.group(2).strip();
            } else if (h2.matches()) {
                if (protocolId == null) {
                    throw new IllegalStateException("Seccion sin protocolo (falta H1): " + h2.group(1));
                }
                flush(chunks, protocolId, protocolTitle, sectionTitle, body, corpusVersion);
                sectionTitle = h2.group(1).strip();
                body.setLength(0);
            } else if (!line.isBlank()) {
                if (sectionTitle == null) {
                    throw new IllegalStateException("Texto fuera de una seccion (H2) en " + protocolId);
                }
                body.append(body.length() == 0 ? "" : " ").append(line.strip());
            }
        }
        flush(chunks, protocolId, protocolTitle, sectionTitle, body, corpusVersion);
        return chunks;
    }

    private static void requireNoStray(StringBuilder body, String where) {
        if (body.length() > 0) {
            throw new IllegalStateException("Texto inesperado " + where);
        }
    }

    private static void flush(List<ProtocolChunk> out, String protocolId, String protocolTitle,
            String sectionTitle, StringBuilder body, String version) {
        if (sectionTitle == null) {
            return;
        }
        String sectionId = slug(sectionTitle);
        if (sectionId.isEmpty()) {
            throw new IllegalStateException("Seccion con identificador vacio en " + protocolId);
        }
        if (body.length() == 0) {
            throw new IllegalStateException("Seccion vacia: " + protocolId + "#" + sectionId);
        }
        out.add(new ProtocolChunk(protocolId, protocolTitle, sectionId, sectionTitle, version, body.toString()));
    }

    static String slug(String text) {
        String ascii = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private static String read(Resource resource) {
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
