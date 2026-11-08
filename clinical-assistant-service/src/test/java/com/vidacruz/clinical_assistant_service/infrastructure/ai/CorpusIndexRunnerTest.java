package com.vidacruz.clinical_assistant_service.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.vidacruz.clinical_assistant_service.domain.AssistantUnavailableException;
import reactor.core.publisher.Mono;

class CorpusIndexRunnerTest {

    @Test
    void runDoesNotThrowWhenIndexingFails() {
        var retriever = mock(SpringAiProtocolRetriever.class);
        when(retriever.indexCorpus()).thenReturn(Mono.error(new AssistantUnavailableException("x", null)));

        assertThatCode(() -> new CorpusIndexRunner(retriever).run(null)).doesNotThrowAnyException();
    }
}
