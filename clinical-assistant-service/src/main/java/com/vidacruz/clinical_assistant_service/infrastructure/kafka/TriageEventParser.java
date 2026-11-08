package com.vidacruz.clinical_assistant_service.infrastructure.kafka;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.clinical_assistant_service.domain.EstadoPaciente;
import com.vidacruz.clinical_assistant_service.domain.EventoInvalidoException;
import com.vidacruz.clinical_assistant_service.domain.EventoTriaje;
import com.vidacruz.clinical_assistant_service.domain.NivelTriaje;

/**
 * Valida el envelope v1 y lo convierte a {@link EventoTriaje}. Estricto: cualquier desviacion lanza
 * {@link EventoInvalidoException} (va al DLT). Los mensajes de error nunca incluyen el contenido recibido.
 */
@Component
public class TriageEventParser {

    static final int SUPPORTED_SCHEMA = 1;
    static final long MAX_VERSION = 1L << 53;

    /** Tipo soportado -> estado que su snapshot debe llevar. */
    private static final Map<String, EstadoPaciente> TIPOS = Map.of(
            "paciente_registrado", EstadoPaciente.REGISTRADO,
            "paciente_escalado", EstadoPaciente.REGISTRADO,
            "paciente_en_consulta", EstadoPaciente.EN_CONSULTA,
            "paciente_alta", EstadoPaciente.ALTA);

    private final ObjectMapper mapper;

    public TriageEventParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public EventoTriaje parse(String json) {
        if (json == null || json.isBlank()) {
            throw invalido("mensaje vacio");
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw invalido("JSON invalido");
        }
        if (root == null || !root.isObject()) {
            throw invalido("el mensaje no es un objeto JSON");
        }

        JsonNode schema = root.get("schemaVersion");
        if (schema == null || !schema.isIntegralNumber() || !schema.canConvertToInt()) {
            throw invalido("schemaVersion ausente o no entera");
        }
        if (schema.asInt() != SUPPORTED_SCHEMA) {
            throw new EventoInvalidoException("schema_no_soportado", "schemaVersion no soportada");
        }
        String eventType = text(root, "eventType");
        EstadoPaciente estadoEsperado = TIPOS.get(eventType);
        if (estadoEsperado == null) {
            throw new EventoInvalidoException("tipo_no_soportado", "eventType no soportado");
        }

        String eventId = uuid(root, "eventId");
        String aggregateId = uuid(root, "aggregateId");
        JsonNode version = root.get("aggregateVersion");
        // Lua usa doubles: por encima de 2^53 la comparacion de versiones deja de ser exacta.
        if (version == null || !version.isIntegralNumber() || !version.canConvertToLong()
                || version.asLong() < 1 || version.asLong() > MAX_VERSION) {
            throw invalido("aggregateVersion ausente o fuera de 1..2^53");
        }
        JsonNode payload = root.get("payload");
        if (payload == null || !payload.isObject()) {
            throw invalido("payload ausente");
        }
        String patientId = uuid(payload, "patientId");
        if (!patientId.equals(aggregateId)) {
            throw invalido("aggregateId distinto de payload.patientId");
        }
        NivelTriaje nivel = enumValue(NivelTriaje.class, text(payload, "nivel"), "nivel");
        EstadoPaciente estado = enumValue(EstadoPaciente.class, text(payload, "estado"), "estado");
        if (estado != estadoEsperado) {
            throw invalido("estado incoherente con eventType");
        }
        JsonNode escalated = payload.get("escalated");
        if (escalated == null || !escalated.isBoolean()) {
            throw invalido("escalated ausente o no booleano");
        }
        return new EventoTriaje(eventId, eventType, patientId, version.asLong(), nivel, estado, escalated.asBoolean());
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw invalido("campo " + field + " ausente o no textual");
        }
        return v.asText();
    }

    /** Se exige UUID canonico (minusculas, con guiones): ademas valida el formato, evita claves Redis arbitrarias. */
    private static String uuid(JsonNode node, String field) {
        String raw = text(node, field);
        try {
            String canonical = UUID.fromString(raw).toString();
            if (!canonical.equals(raw)) {
                throw invalido("campo " + field + " no es UUID canonico");
            }
            return canonical;
        } catch (IllegalArgumentException e) {
            throw invalido("campo " + field + " no es UUID");
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String raw, String field) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw invalido("valor de " + field + " desconocido");
        }
    }

    private static EventoInvalidoException invalido(String detalle) {
        return new EventoInvalidoException("invalido", detalle);
    }
}
