package org.gensparql.llm.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PromptBuilder.
 */
class PromptBuilderTest {

    // ========== buildStructuredPrompt Tests ==========

    @Test
    void testBuildStructuredPrompt_SingleVariable_RequestsConciseJsonScalar() {
        String original = "Translate this to Japanese";
        String result = PromptBuilder.buildStructuredPrompt(original, List.of("translation"));

        assertTrue(result.startsWith(original));
        assertTrue(result.contains("{\"translation\": \"value\"}"));
        assertTrue(result.contains("not a sentence"));
    }

    @Test
    void testBuildStructuredPrompt_NullOutputVars_NoChange() {
        String original = "Translate this to Japanese";
        String result = PromptBuilder.buildStructuredPrompt(original, null);

        assertEquals(original, result);
    }

    @Test
    void testBuildStructuredPrompt_EmptyOutputVars_NoChange() {
        String original = "Translate this to Japanese";
        String result = PromptBuilder.buildStructuredPrompt(original, List.of());

        assertEquals(original, result);
    }

    @Test
    void testBuildStructuredPrompt_NullPrompt_ReturnsEmpty() {
        String result = PromptBuilder.buildStructuredPrompt(null, List.of("var1", "var2"));

        assertEquals("", result);
    }

    @Test
    void testBuildStructuredPrompt_MultipleVariables_AddsInstructions() {
        String original = "List scientists and their awards";
        String result = PromptBuilder.buildStructuredPrompt(original, List.of("scientist", "award"));

        assertTrue(result.startsWith(original));
        assertTrue(result.contains("[OUTPUT FORMAT REQUIREMENT]"));
        assertTrue(result.contains("JSON array"));
        assertTrue(result.contains("scientist"));
        assertTrue(result.contains("award"));
        assertTrue(result.contains("Do NOT include any markdown formatting"));
    }

    @Test
    void testBuildStructuredPrompt_ContainsExampleFormat() {
        String result = PromptBuilder.buildStructuredPrompt("Test", List.of("name", "city"));

        // New format uses value1, value2, value3 to show proper array with multiple objects
        assertTrue(result.contains("\"name\": \"value1\""));
        assertTrue(result.contains("\"city\": \"value1\""));
        assertTrue(result.contains("\"name\": \"value2\""));
        assertTrue(result.contains("\"city\": \"value2\""));
    }

    @Test
    void testBuildStructuredPrompt_ThreeVariables() {
        String result = PromptBuilder.buildStructuredPrompt("Test", List.of("a", "b", "c"));

        assertTrue(result.contains("a, b, c"));
        // New format uses value1, value2, value3 to show proper array with multiple objects
        assertTrue(result.contains("\"a\": \"value1\""));
        assertTrue(result.contains("\"b\": \"value1\""));
        assertTrue(result.contains("\"c\": \"value1\""));
    }

    // ========== buildSingleRowPrompt Tests ==========

    @Test
    void testBuildSingleRowPrompt_SingleVariable_NoChange() {
        String original = "Get info";
        String result = PromptBuilder.buildSingleRowPrompt(original, List.of("info"));

        assertEquals(original, result);
    }

    @Test
    void testBuildSingleRowPrompt_MultipleVariables_AddsObjectFormat() {
        String original = "Get user info";
        String result = PromptBuilder.buildSingleRowPrompt(original, List.of("name", "email"));

        assertTrue(result.startsWith(original));
        assertTrue(result.contains("[OUTPUT FORMAT REQUIREMENT]"));
        assertTrue(result.contains("JSON object"));
        assertFalse(result.contains("JSON array"));
        assertTrue(result.contains("\"name\": \"value\""));
        assertTrue(result.contains("\"email\": \"value\""));
    }

    @Test
    void testBuildSingleRowPrompt_NullPrompt_ReturnsEmpty() {
        String result = PromptBuilder.buildSingleRowPrompt(null, List.of("var1", "var2"));

        assertEquals("", result);
    }

    // ========== Consistency Tests ==========

    @Test
    void testBuildersDontModifyOriginalPrompt() {
        String original = "My original prompt";

        PromptBuilder.buildStructuredPrompt(original, List.of("a", "b"));
        PromptBuilder.buildSingleRowPrompt(original, List.of("a", "b"));

        // Original string should not be modified
        assertEquals("My original prompt", original);
    }
}
