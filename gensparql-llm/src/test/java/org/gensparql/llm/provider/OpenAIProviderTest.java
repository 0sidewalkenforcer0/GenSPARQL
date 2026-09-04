package org.gensparql.llm.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAIProviderTest {

    @Test
    void keepsApiRoot() {
        assertEquals("https://api.groq.com/openai/v1",
                OpenAIProvider.normalizeBaseUrl("https://api.groq.com/openai/v1/"));
    }

    @Test
    void acceptsFullEndpointWithoutDuplicatingItsPath() {
        assertEquals("https://api.groq.com/openai/v1",
                OpenAIProvider.normalizeBaseUrl(
                        "https://api.groq.com/openai/v1/chat/completions"));
    }

    @Test
    void rejectsNonHttpBaseUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> OpenAIProvider.normalizeBaseUrl("api.groq.com/openai/v1"));
    }
}
