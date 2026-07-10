package org.gensparql.test;

import org.gensparql.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ModelSpec parsing and creation.
 */
public class ModelSpecTest {

    @Test
    void testParseSimpleURI() {
        ModelSpec spec = ModelSpec.fromURI("model:openai:gpt-4");

        assertEquals("openai", spec.getProvider());
        assertEquals("gpt-4", spec.getModel());
        assertTrue(spec.getParameters().isEmpty());
    }

    @Test
    void testParseURIWithAngleBrackets() {
        ModelSpec spec = ModelSpec.fromURI("<model:anthropic:claude-3-opus>");

        assertEquals("anthropic", spec.getProvider());
        assertEquals("claude-3-opus", spec.getModel());
    }

    @Test
    void testParseURIWithParameters() {
        ModelSpec spec = ModelSpec.fromURI("model:openai:gpt-4?temperature=0.7&maxTokens=500");

        assertEquals("openai", spec.getProvider());
        assertEquals("gpt-4", spec.getModel());
        assertEquals(0.7, spec.getParameters().get("temperature"));
        assertEquals(500, spec.getParameters().get("maxTokens"));
    }

    @Test
    void testParseSlashedModelName() {
        ModelSpec spec = ModelSpec.fromURI("model:openrouter:deepseek/deepseek-chat");

        assertEquals("openrouter", spec.getProvider());
        assertEquals("deepseek/deepseek-chat", spec.getModel());
    }

    @Test
    void testBuilder() {
        ModelSpec spec = ModelSpec.builder()
                .provider("openai")
                .model("gpt-4-turbo")
                .temperature(0.8)
                .maxTokens(1000)
                .parameter("topP", 0.9)
                .build();

        assertEquals("openai", spec.getProvider());
        assertEquals("gpt-4-turbo", spec.getModel());
        assertEquals(0.8, spec.getTemperature());
        assertEquals(1000, spec.getMaxTokens());
        assertEquals(0.9, spec.getParameters().get("topP"));
    }

    @Test
    void testToURI() {
        ModelSpec spec = ModelSpec.builder()
                .provider("openai")
                .model("gpt-4")
                .temperature(0.7)
                .build();

        String uri = spec.toURI();

        assertTrue(uri.startsWith("model:openai:gpt-4"));
        assertTrue(uri.contains("temperature=0.7"));
    }

    @Test
    void testEquality() {
        ModelSpec spec1 = ModelSpec.fromURI("model:openai:gpt-4");
        ModelSpec spec2 = ModelSpec.fromURI("model:openai:gpt-4");
        ModelSpec spec3 = ModelSpec.fromURI("model:anthropic:claude-3");

        assertEquals(spec1, spec2);
        assertNotEquals(spec1, spec3);
        assertEquals(spec1.hashCode(), spec2.hashCode());
    }

    @Test
    void testInvalidURI() {
        assertThrows(IllegalArgumentException.class, () ->
                ModelSpec.fromURI("invalid-uri"));

        assertThrows(IllegalArgumentException.class, () ->
                ModelSpec.fromURI("model:openai"));  // Missing model name
    }

    @Test
    void testBooleanParameters() {
        ModelSpec spec = ModelSpec.fromURI("model:openai:gpt-4?stream=true&logprobs=false");

        assertEquals(true, spec.getParameters().get("stream"));
        assertEquals(false, spec.getParameters().get("logprobs"));
    }
}
