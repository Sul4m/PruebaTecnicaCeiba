package com.vidacruz.clinical_assistant_service.infrastructure.ai.dev;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * DOBLE DE DESARROLLO/PRUEBAS (no es un modelo real, no usar en produccion).
 * Embedding determinista tipo "bolsa de palabras" con hashing (sin red ni credenciales) para poder
 * ejercitar retriever, umbral y citas localmente. Reemplazado por Azure OpenAI con el perfil {@code azure}.
 */
public class DevEmbeddingModel implements EmbeddingModel {

    static final int DIMENSIONS = 512;
    /** Dimension constante extra: el vector nunca tiene norma cero (SimpleVectorStore lo rechaza). */
    static final int VECTOR_SIZE = DIMENSIONS + 1;
    private static final int STEM_LENGTH = 5;
    private static final Set<String> STOPWORDS = Set.of(
            "de", "la", "el", "los", "las", "un", "una", "unos", "unas", "en", "y", "o", "a", "al", "del", "se",
            "que", "es", "son", "por", "para", "con", "sin", "su", "sus", "lo", "ante", "si", "cual", "cuales",
            "cuando", "como", "ser", "hay", "u");

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> out = new ArrayList<>();
        int i = 0;
        for (String text : request.getInstructions()) {
            out.add(new Embedding(embed(text), i++));
        }
        return new EmbeddingResponse(out);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[VECTOR_SIZE];
        v[DIMENSIONS] = 0.01f;
        for (String token : tokens(text)) {
            v[Math.floorMod(token.hashCode(), DIMENSIONS)] += 1f;
        }
        return v;
    }

    @Override
    public int dimensions() {
        return VECTOR_SIZE;
    }

    static List<String> tokens(String text) {
        String ascii = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        List<String> out = new ArrayList<>();
        for (String raw : ascii.split("[^a-z0-9]+")) {
            if (raw.isEmpty() || STOPWORDS.contains(raw)) {
                continue;
            }
            out.add(raw.length() > STEM_LENGTH ? raw.substring(0, STEM_LENGTH) : raw);
        }
        return out;
    }
}
