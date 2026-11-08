package com.vidacruz.clinical_assistant_service.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.vidacruz.clinical_assistant_service.domain.FuenteProtocolo;
import com.vidacruz.clinical_assistant_service.domain.ProtocolChunk;
import com.vidacruz.clinical_assistant_service.domain.RetrievedChunk;

/** Fixtures: respuestas reales del modelo (Azure) contra los chunks reales del corpus v1. */
class RespuestaSustentadaTest {

    private static final Path CORPUS = Path.of("src/main/resources/corpus/v1");
    private static final Pattern H1 = Pattern.compile("^#\\s+(PROTO-\\d{3})\\s+[—–-]\\s+(.+)$");
    private static final Pattern H2 = Pattern.compile("^##\\s+(.+)$");

    private static List<RetrievedChunk> corpus(String... protocolos) throws IOException {
        List<RetrievedChunk> out = new ArrayList<>();
        for (String file : protocolos) {
            String id = null;
            String title = null;
            String section = null;
            StringBuilder body = new StringBuilder();
            for (String line : Files.readString(CORPUS.resolve(file + ".md"), StandardCharsets.UTF_8).split("\\R")) {
                Matcher h1 = H1.matcher(line);
                Matcher h2 = H2.matcher(line);
                if (h1.matches()) {
                    id = h1.group(1);
                    title = h1.group(2);
                } else if (h2.matches()) {
                    if (section != null) {
                        out.add(chunk(id, title, section, body));
                    }
                    section = h2.group(1).strip();
                    body.setLength(0);
                } else if (!line.isBlank()) {
                    body.append(body.length() == 0 ? "" : " ").append(line.strip());
                }
            }
            out.add(chunk(id, title, section, body));
        }
        return out;
    }

    private static RetrievedChunk chunk(String id, String title, String section, StringBuilder body) {
        return new RetrievedChunk(new ProtocolChunk(id, title, section.toLowerCase(), section, "v1", body.toString()), 0.9);
    }

    private static Arguments positivo(String respuesta, String proto, String seccion) {
        return Arguments.of(respuesta, new FuenteProtocolo(proto, seccion));
    }

    static Stream<Arguments> positivos() {
        return Stream.of(
                positivo("Reconfirmar la presión tras 5 minutos de reposo, no reducir la presión más de un 25% en la primera hora y vigilar signos de encefalopatía.",
                        "PROTO-001", "Medidas iniciales"),
                positivo("Antibiótico de amplio espectro en la primera hora.", "PROTO-002", "Acciones iniciales"),
                positivo("Leve: pérdida 3-5% del peso.  \nModerada: 5-8%.  \nSevera: >8% o alteración del estado mental.",
                        "PROTO-003", "Clasificación"),
                positivo("Según el protocolo **PROTO-002**, se identifica por **2 o más** de los siguientes criterios: **frecuencia respiratoria ≥ 22**, **alteración del estado mental** y **presión sistólica ≤ 100 mmHg**.",
                        "PROTO-002", "Criterios"),
                positivo("Leve o moderada sin vómito: solución oral 50 ml/kg en 4 horas. Severa: vía intravenosa, bolo de 20 ml/kg.",
                        "PROTO-003", "Rehidratación"),
                positivo("Contraindicaciones: nifedipino sublingual y reducción intravenosa rápida sin monitorización en cuidados intensivos.",
                        "PROTO-001", "Contraindicaciones"));
    }

    @ParameterizedTest
    @MethodSource("positivos")
    void respuestasRealesSustentadasConservanSoloSuSeccion(String respuesta, FuenteProtocolo esperada) throws IOException {
        assertThat(RespuestaSustentada.fuentesQueSustentan(respuesta, corpus("PROTO-001", "PROTO-002", "PROTO-003")))
                .containsExactly(esperada);
    }

    @Test
    void citaCorrectaDeProtocoloRecuperadoPasa() throws IOException {
        assertThat(RespuestaSustentada.fuentesQueSustentan(
                "Según PROTO-001: vigilar signos de encefalopatía.", corpus("PROTO-001")))
                .containsExactly(new FuenteProtocolo("PROTO-001", "Medidas iniciales"));
    }

    @Test
    void ordenEstableSinDuplicadosCuandoVariosChunksRepiten() throws IOException {
        List<RetrievedChunk> ctx = new ArrayList<>(corpus("PROTO-001"));
        ctx.addAll(corpus("PROTO-001"));
        assertThat(RespuestaSustentada.fuentesQueSustentan(
                "Reconfirmar la presión tras 5 minutos de reposo y vigilar signos de encefalopatía.", ctx))
                .containsExactly(new FuenteProtocolo("PROTO-001", "Medidas iniciales"));
    }

    @Test
    void negativoAbstencionEnProsaConChunksDeProto001() throws IOException {
        assertThat(RespuestaSustentada.fuentesQueSustentan(
                "No hay información en los fragmentos sobre el manejo de un infarto agudo de miocardio.",
                corpus("PROTO-001"))).isEmpty();
    }

    @Test
    void negativoDosisInventadaConNumeroAusente() throws IOException {
        assertThat(RespuestaSustentada.fuentesQueSustentan(
                "Administrar 300 mg de aspirina masticable y trasladar a hemodinamia.",
                corpus("PROTO-001", "PROTO-002", "PROTO-003"))).isEmpty();
    }

    @Test
    void numeroInventadoRechazaAunqueElRestoSeaLexicamenteFiel() throws IOException {
        assertThat(RespuestaSustentada.fuentesQueSustentan(
                "Reconfirmar la presión tras 7 minutos de reposo y vigilar signos de encefalopatía.",
                corpus("PROTO-001"))).isEmpty();
    }

    @Test
    void negativoSinInformacionRelevante() throws IOException {
        assertThat(RespuestaSustentada.fuentesQueSustentan("No se encontró información relevante.",
                corpus("PROTO-001", "PROTO-002", "PROTO-003"))).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\n", "PROTO-001", "de la en"})
    void vacioONadaDeContenidoAbstiene(String respuesta) throws IOException {
        assertThat(RespuestaSustentada.fuentesQueSustentan(respuesta, corpus("PROTO-001"))).isEmpty();
    }
}
