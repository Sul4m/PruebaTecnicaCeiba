package com.vidacruz.clinical_assistant_service.infrastructure.ai.dev;

import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

/**
 * DOBLE DE DESARROLLO/PRUEBAS (no es un modelo real, no usar en produccion).
 * Devuelve en streaming, por palabras, los fragmentos recibidos como contexto con un prefijo explicito.
 */
public class DevChatModel implements ChatModel {

    static final String PREFIX = "[DOBLE DE DESARROLLO] ";

    @Override
    public ChatResponse call(Prompt prompt) {
        return response(PREFIX + extractContext(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        String text = PREFIX + extractContext(prompt);
        return Flux.fromArray(text.split("(?<= )")).map(DevChatModel::response);
    }

    private static String extractContext(Prompt prompt) {
        String user = prompt.getUserMessage().getText();
        int start = user.indexOf("FRAGMENTOS:");
        int end = user.indexOf("PREGUNTA:");
        String ctx = start >= 0 && end > start ? user.substring(start + "FRAGMENTOS:".length(), end) : user;
        return ctx.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip();
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
