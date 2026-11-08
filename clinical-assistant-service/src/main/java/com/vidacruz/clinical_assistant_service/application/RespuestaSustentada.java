package com.vidacruz.clinical_assistant_service.application;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.vidacruz.clinical_assistant_service.domain.FuenteProtocolo;
import com.vidacruz.clinical_assistant_service.domain.RetrievedChunk;

/**
 * RN-05 determinista: comprueba que la respuesta generada esta sustentada lexicamente por los fragmentos
 * recuperados, sin depender de que el LLM obedezca el prompt. Pura, sin Spring.
 *
 * <p>Reglas: (1) se extraen tokens de contenido (palabras de 4+ letras no vacias y TODOS los numeros);
 * (2) debe haber al menos uno; (3) todo numero de la respuesta debe existir en los fragmentos (dosis inventada
 * = abstencion); (4) al menos {@link #UMBRAL_SUSTENTO} de los tokens de contenido debe aparecer en los
 * fragmentos. El umbral 0.6 se eligio porque las respuestas reales del modelo parafrasean ("Segun el protocolo",
 * "se identifica", "reducir" vs "reduccion") y aun asi lo superan, mientras que abstenciones en prosa
 * ("No hay informacion en los fragmentos sobre ...") quedan muy por debajo.
 *
 * <p>Limitacion: es lexica, no semantica; una respuesta que reordene palabras reales con otro sentido pasaria.
 */
public final class RespuestaSustentada {

    /** Fraccion minima de tokens de contenido de la respuesta presentes en los fragmentos. */
    static final double UMBRAL_SUSTENTO = 0.6;
    /** Un fragmento aporta si comparte al menos este numero de tokens con la respuesta... */
    static final int MIN_TOKENS_COMPARTIDOS = 2;
    /** ...y al menos esta fraccion de lo que comparte el mejor fragmento. */
    static final double FRACCION_DEL_MEJOR = 0.5;

    private static final Pattern CITA = Pattern.compile("proto[\\s_\\-\\u2010-\\u2015]*\\d+");
    private static final Pattern TOKEN = Pattern.compile("\\d+(?:[.,]\\d+)?|\\p{L}+");
    private static final int MIN_LETRAS = 4;

    private static final Set<String> STOPWORDS = Set.of(
            "para", "como", "pero", "porque", "cuando", "donde", "desde", "hasta", "entre", "sobre", "segun",
            "este", "esta", "estos", "estas", "esos", "esas", "aquel", "aquella", "tambien",
            "cada", "todo", "toda", "todos", "todas", "otro", "otra", "otros", "otras", "solo", "mismo", "misma",
            "ante", "bajo", "contra", "durante", "mediante", "hacia", "sino", "aunque",
            "cual", "cuales", "quien", "quienes", "tiene", "tienen", "puede", "pueden", "debe", "deben",
            "estan", "sera", "seran", "sido", "siendo", "siguiente", "siguientes", "protocolo", "menos",
            "algun", "alguna", "algunos", "dicho", "dicha", "unos", "unas");

    private RespuestaSustentada() {
    }

    /**
     * @return fuentes (protocolo, seccion) que sustentan la respuesta, en el orden de recuperacion y sin
     *         duplicados; lista vacia si la respuesta NO esta sustentada.
     */
    public static List<FuenteProtocolo> fuentesQueSustentan(String respuesta, List<RetrievedChunk> context) {
        if (respuesta == null || context == null || context.isEmpty()) {
            return List.of();
        }
        Set<String> tokensRespuesta = tokensDeContenido(respuesta);
        if (tokensRespuesta.isEmpty()) {
            return List.of();
        }
        Set<String> tokensFragmentos = new HashSet<>();
        for (RetrievedChunk rc : context) {
            tokensFragmentos.addAll(tokensCompletos(textoDe(rc)));
        }
        long presentes = 0;
        for (String t : tokensRespuesta) {
            if (tokensFragmentos.contains(t)) {
                presentes++;
            } else if (esNumero(t)) {
                return List.of(); // dosis/cifra inventada
            }
        }
        if ((double) presentes / tokensRespuesta.size() < UMBRAL_SUSTENTO) {
            return List.of();
        }
        return fuentesAportantes(tokensRespuesta, context);
    }

    private static List<FuenteProtocolo> fuentesAportantes(Set<String> tokensRespuesta, List<RetrievedChunk> context) {
        int[] compartidos = new int[context.size()];
        int mejor = 0;
        int idxMejor = 0;
        for (int i = 0; i < context.size(); i++) {
            Set<String> tokensChunk = tokensCompletos(textoDe(context.get(i)));
            for (String t : tokensRespuesta) {
                if (tokensChunk.contains(t)) {
                    compartidos[i]++;
                }
            }
            if (compartidos[i] > mejor) {
                mejor = compartidos[i];
                idxMejor = i;
            }
        }
        Set<FuenteProtocolo> fuentes = new LinkedHashSet<>();
        for (int i = 0; i < context.size(); i++) {
            if (compartidos[i] >= MIN_TOKENS_COMPARTIDOS && compartidos[i] >= FRACCION_DEL_MEJOR * mejor) {
                fuentes.add(fuenteDe(context.get(i)));
            }
        }
        if (fuentes.isEmpty()) {
            fuentes.add(fuenteDe(context.get(idxMejor)));
        }
        return List.copyOf(fuentes);
    }

    private static FuenteProtocolo fuenteDe(RetrievedChunk rc) {
        return new FuenteProtocolo(rc.chunk().protocolId(), rc.chunk().sectionTitle());
    }

    /** Texto del fragmento contra el que se compara: titulo de seccion + cuerpo (sin titulo de protocolo). */
    private static String textoDe(RetrievedChunk rc) {
        return rc.chunk().sectionTitle() + " " + rc.chunk().body();
    }

    private static boolean esNumero(String t) {
        return Character.isDigit(t.charAt(0));
    }

    private static String normalizar(String texto) {
        String sinDiacriticos = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinDiacriticos.toLowerCase(Locale.ROOT).replaceAll("[*_#]", " ");
    }

    /** Tokens de contenido de la respuesta: sin citas PROTO-n, sin stopwords ni palabras cortas; numeros siempre. */
    private static Set<String> tokensDeContenido(String texto) {
        String sinCitas = CITA.matcher(normalizar(texto)).replaceAll(" ");
        Set<String> out = new LinkedHashSet<>();
        Matcher m = TOKEN.matcher(sinCitas);
        while (m.find()) {
            String t = m.group().replace(',', '.');
            if (esNumero(t) || (t.length() >= MIN_LETRAS && !STOPWORDS.contains(t))) {
                out.add(t);
            }
        }
        return out;
    }

    /** Todos los tokens del fragmento (sin filtrar), para poder buscar en ellos. */
    private static Set<String> tokensCompletos(String texto) {
        Set<String> out = new HashSet<>();
        Matcher m = TOKEN.matcher(normalizar(texto));
        while (m.find()) {
            out.add(m.group().replace(',', '.'));
        }
        return out;
    }
}
