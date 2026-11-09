package com.vidacruz.clinical_assistant_service.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.vidacruz.clinical_assistant_service.domain.ProtocolChunk;

class ProtocolCorpusLoaderTest {

    private final List<ProtocolChunk> chunks =
            new ProtocolCorpusLoader().load("classpath:corpus/{version}/*.md", "v1");

    @Test
    void splitsCorpusByProtocolAndSectionWithMetadata() {
        assertThat(chunks).extracting(ProtocolChunk::protocolId).containsOnly("PROTO-001", "PROTO-002", "PROTO-003");
        assertThat(chunks).extracting(c -> c.protocolId() + "/" + c.sectionId()).containsExactlyInAnyOrder(
                "PROTO-001/definicion", "PROTO-001/medidas-iniciales", "PROTO-001/contraindicaciones",
                "PROTO-002/criterios", "PROTO-002/acciones-iniciales",
                "PROTO-003/clasificacion", "PROTO-003/rehidratacion");
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.corpusVersion()).isEqualTo("v1");
            assertThat(c.protocolTitle()).isNotBlank();
            assertThat(c.sectionTitle()).isNotBlank();
            assertThat(c.body()).isNotBlank();
        });
    }

    @Test
    void sectionTextMatchesBaseline() {
        ProtocolChunk medidas = chunks.stream()
                .filter(c -> c.sectionId().equals("medidas-iniciales")).findFirst().orElseThrow();
        assertThat(medidas.body()).contains("25%").contains("5 minutos");
        assertThat(medidas.protocolTitle()).isEqualTo("Crisis Hipertensiva, manejo inicial");
    }

    private List<ProtocolChunk> loadFrom(Path dir, String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
        return new ProtocolCorpusLoader().load("file:" + dir + "/*.md", "v1");
    }

    @Test
    void failsFastOnFileWithZeroChunks(@TempDir Path dir) {
        assertThatThrownBy(() -> loadFrom(dir, "PROTO-009.md", "# PROTO-009 — Solo titulo\n"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PROTO-009.md");
    }

    @Test
    void failsFastOnDuplicateSectionTitles(@TempDir Path dir) {
        String md = "# PROTO-009 — T\n\n## Medidas\ntexto uno\n\n## Medidas\ntexto dos\n";
        assertThatThrownBy(() -> loadFrom(dir, "PROTO-009.md", md))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicado");
    }

    @Test
    void failsFastOnStrayTextBetweenH1AndFirstSection(@TempDir Path dir) {
        String md = "# PROTO-009 — T\nTexto suelto\n\n## A\ncuerpo\n";
        assertThatThrownBy(() -> loadFrom(dir, "PROTO-009.md", md)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsFastOnSecondH1(@TempDir Path dir) {
        assertThatThrownBy(() -> loadFrom(dir, "PROTO-009.md",
                "# PROTO-009 — T\n## A\nx\n# PROTO-010 — U\n## B\ny\n"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsFastOnBlankSlug(@TempDir Path dir) {
        assertThatThrownBy(() -> loadFrom(dir, "PROTO-009.md", "# PROTO-009 — T\n## ???\nx\n"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsBomAndDashVariants(@TempDir Path dir) throws IOException {
        List<ProtocolChunk> out = loadFrom(dir, "PROTO-009.md", "\uFEFF# PROTO-009 – Titulo\n\n## Seccion\ncuerpo\n");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).protocolTitle()).isEqualTo("Titulo");
    }
}
