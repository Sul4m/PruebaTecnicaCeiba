package com.vidacruz.clinical_assistant_service.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.clinical_assistant_service.domain.EstadoPaciente;
import com.vidacruz.clinical_assistant_service.domain.EventoInvalidoException;
import com.vidacruz.clinical_assistant_service.domain.EventoTriaje;
import com.vidacruz.clinical_assistant_service.domain.NivelTriaje;
import com.vidacruz.clinical_assistant_service.support.TriageEvents;

class TriageEventParserTest {

    private final TriageEventParser parser = new TriageEventParser(new ObjectMapper());
    private final String patient = UUID.randomUUID().toString();
    private final String eventId = UUID.randomUUID().toString();

    private String ev(String type, int schema, String estado, long version, String nivel) {
        return TriageEvents.json(eventId, type, schema, patient, version, nivel, estado, false);
    }

    @Test
    void parsesEveryV1EventType() {
        EventoTriaje e = parser.parse(ev("paciente_registrado", 1, "REGISTRADO", 1, "CRITICO"));
        assertThat(e.eventId()).isEqualTo(eventId);
        assertThat(e.patientId()).isEqualTo(patient);
        assertThat(e.aggregateVersion()).isEqualTo(1);
        assertThat(e.nivel()).isEqualTo(NivelTriaje.CRITICO);
        assertThat(e.estado()).isEqualTo(EstadoPaciente.REGISTRADO);

        assertThat(parser.parse(TriageEvents.escalado(patient, 2, "URGENTE")).escalated()).isTrue();
        assertThat(parser.parse(TriageEvents.enConsulta(patient, 2, "URGENTE")).estado())
                .isEqualTo(EstadoPaciente.EN_CONSULTA);
        assertThat(parser.parse(TriageEvents.alta(patient, 3, "URGENTE")).estado()).isEqualTo(EstadoPaciente.ALTA);
    }

    @Test
    void ignoresUnknownFieldsInsideTheV1Envelope() {
        String json = ev("paciente_registrado", 1, "REGISTRADO", 1, "URGENTE_2")
                .replace("\"escalated\":false", "\"escalated\":false,\"extra\":1");
        assertThat(parser.parse(json).nivel()).isEqualTo(NivelTriaje.URGENTE_2);
    }

    @Test
    void unsupportedEventTypeIsRejectedWithItsOwnReason() {
        assertThatThrownBy(() -> parser.parse(ev("paciente_trasladado", 1, "REGISTRADO", 1, "CRITICO")))
                .isInstanceOfSatisfying(EventoInvalidoException.class,
                        e -> assertThat(e.motivo()).isEqualTo("tipo_no_soportado"));
    }

    @Test
    void unsupportedSchemaVersionIsRejectedWithItsOwnReason() {
        assertThatThrownBy(() -> parser.parse(ev("paciente_registrado", 2, "REGISTRADO", 1, "CRITICO")))
                .isInstanceOfSatisfying(EventoInvalidoException.class,
                        e -> assertThat(e.motivo()).isEqualTo("schema_no_soportado"));
    }

    @Test
    void malformedMessagesAreInvalid() {
        for (String bad : new String[] { null, "", "  ", "no es json", "[1,2]", "{}", "\"texto\"",
                ev("paciente_registrado", 1, "REGISTRADO", 0, "CRITICO"),
                ev("paciente_registrado", 1, "REGISTRADO", 1, "DESCONOCIDO"),
                ev("paciente_registrado", 1, "OTRO", 1, "CRITICO"),
                // estado incoherente con el tipo de evento
                ev("paciente_alta", 1, "REGISTRADO", 3, "CRITICO"),
                // identificadores que no son UUID (evita claves Redis arbitrarias)
                TriageEvents.json("no-uuid", "paciente_registrado", 1, patient, 1, "CRITICO", "REGISTRADO", false),
                TriageEvents.json(eventId, "paciente_registrado", 1, "x:y", 1, "CRITICO", "REGISTRADO", false),
                ev("paciente_registrado", 1, "REGISTRADO", 1, "CRITICO").replace("\"payload\"", "\"otro\""),
                ev("paciente_registrado", 1, "REGISTRADO", 1, "CRITICO").replace("\"escalated\":false", "\"escalated\":1")
        }) {
            assertThatThrownBy(() -> parser.parse(bad)).as("mensaje: %s", bad)
                    .isInstanceOfSatisfying(EventoInvalidoException.class,
                            e -> assertThat(e.motivo()).isEqualTo("invalido"));
        }
    }

    private void assertMotivo(String json, String motivo) {
        assertThatThrownBy(() -> parser.parse(json)).as(json).isInstanceOfSatisfying(EventoInvalidoException.class,
                e -> assertThat(e.motivo()).isEqualTo(motivo));
    }

    @Test
    void futureSchemaWithAnotherShapeIsSchemaNotSupportedNotInvalid() {
        assertMotivo("{\"schemaVersion\":2,\"tipo\":\"otro\",\"datos\":[1]}", "schema_no_soportado");
        assertMotivo("{\"schemaVersion\":2,\"eventType\":\"nuevo_tipo\"}", "schema_no_soportado");
    }

    @Test
    void schemaVersionNotFittingAnIntIsInvalid() {
        assertMotivo(ev("paciente_registrado", 1, "REGISTRADO", 1, "CRITICO")
                .replace("\"schemaVersion\":1", "\"schemaVersion\":4294967297"), "invalido");
    }

    @Test
    void aggregateVersionOutsideSafeRangeIsInvalid() {
        for (String v : new String[] { "0", "-1", "9007199254740993", "99999999999999999999" }) {
            assertMotivo(ev("paciente_registrado", 1, "REGISTRADO", 1, "CRITICO")
                    .replace("\"aggregateVersion\":1", "\"aggregateVersion\":" + v), "invalido");
        }
        assertThat(parser.parse(ev("paciente_registrado", 1, "REGISTRADO", 9007199254740992L, "CRITICO"))
                .aggregateVersion()).isEqualTo(9007199254740992L);
    }

    @Test
    void aggregateIdMustEqualPayloadPatientId() {
        String other = UUID.randomUUID().toString();
        assertMotivo(ev("paciente_registrado", 1, "REGISTRADO", 1, "CRITICO")
                .replace("\"aggregateId\":\"" + patient + "\"", "\"aggregateId\":\"" + other + "\""), "invalido");
    }

    @Test
    void uuidsMustBeCanonicalLowercaseHyphenated() {
        assertMotivo(TriageEvents.json(eventId.toUpperCase(), "paciente_registrado", 1, patient, 1, "CRITICO",
                "REGISTRADO", false), "invalido");
        String noHyphens = patient.replace("-", "");
        assertMotivo(TriageEvents.json(eventId, "paciente_registrado", 1, noHyphens, 1, "CRITICO", "REGISTRADO",
                false), "invalido");
        // UUID.fromString acepta segmentos cortos ("1-1-1-1-1"); el formato canonico no.
        assertMotivo(TriageEvents.json(eventId, "paciente_registrado", 1, "1-1-1-1-1", 1, "CRITICO", "REGISTRADO",
                false), "invalido");
    }

    @Test
    void errorMessagesNeverEchoTheReceivedContent() {
        assertThatThrownBy(() -> parser.parse("{\"secreto\": \"dato-clinico\""))
                .hasMessageNotContaining("dato-clinico");
    }
}
