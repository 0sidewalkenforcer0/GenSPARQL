package org.gensparql.llm.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JsonResponseParser.
 */
class JsonResponseParserTest {

    private JsonResponseParser parser;

    @BeforeEach
    void setUp() {
        parser = new JsonResponseParser();
    }

    // ========== Basic JSON Parsing ==========

    @Test
    void testParseJsonArray() {
        String content = "[{\"scientist\": \"Einstein\", \"award\": \"1921 Physics\"}, " +
                "{\"scientist\": \"Curie\", \"award\": \"1903 Physics\"}]";

        List<Map<String, String>> bindings = parser.parse(content, List.of("scientist", "award"));

        assertEquals(2, bindings.size());
        assertEquals("Einstein", bindings.get(0).get("scientist"));
        assertEquals("1921 Physics", bindings.get(0).get("award"));
        assertEquals("Curie", bindings.get(1).get("scientist"));
        assertEquals("1903 Physics", bindings.get(1).get("award"));
    }

    @Test
    void testParseSingleJsonObject() {
        String content = "{\"name\": \"Alice\", \"city\": \"Paris\"}";

        List<Map<String, String>> bindings = parser.parse(content, List.of("name", "city"));

        assertEquals(1, bindings.size());
        assertEquals("Alice", bindings.get(0).get("name"));
        assertEquals("Paris", bindings.get(0).get("city"));
    }

    // ========== Markdown Code Block Handling ==========

    @Test
    void testParseJsonWithMarkdownCodeBlock() {
        String content = "```json\n" +
                "[{\"scientist\": \"Einstein\", \"award\": \"1921 Physics\"}]\n" +
                "```";

        List<Map<String, String>> bindings = parser.parse(content, List.of("scientist", "award"));

        assertEquals(1, bindings.size());
        assertEquals("Einstein", bindings.get(0).get("scientist"));
        assertEquals("1921 Physics", bindings.get(0).get("award"));
    }

    @Test
    void testParseJsonWithGenericCodeBlock() {
        String content = "```\n" +
                "[{\"scientist\": \"Einstein\", \"award\": \"1921 Physics\"}]\n" +
                "```";

        List<Map<String, String>> bindings = parser.parse(content, List.of("scientist", "award"));

        assertEquals(1, bindings.size());
        assertEquals("Einstein", bindings.get(0).get("scientist"));
    }

    @Test
    void testParseMultipleCodeBlocks_UsesLastValid() {
        // LLM might output multiple attempts, we should use the last valid one
        String content = "Here's my first attempt:\n" +
                "```json\n[{\"scientist\": \"Wrong\", \"award\": \"Bad\"}]\n```\n" +
                "Let me correct that:\n" +
                "```json\n[{\"scientist\": \"Einstein\", \"award\": \"1921 Physics\"}]\n```";

        List<Map<String, String>> bindings = parser.parse(content, List.of("scientist", "award"));

        assertEquals(1, bindings.size());
        assertEquals("Einstein", bindings.get(0).get("scientist"));
        assertEquals("1921 Physics", bindings.get(0).get("award"));
    }

    // ========== Edge Cases ==========

    @Test
    void testParseSingleVariable_ReturnsRawContent() {
        String content = "This is a plain text response.";

        List<Map<String, String>> bindings = parser.parse(content, List.of("result"));

        assertEquals(1, bindings.size());
        assertEquals("This is a plain text response.", bindings.get(0).get("result"));
    }

    @Test
    void testParseEmptyContent() {
        List<Map<String, String>> bindings = parser.parse("", List.of("var1", "var2"));
        assertTrue(bindings.isEmpty());
    }

    @Test
    void testParseNullContent() {
        List<Map<String, String>> bindings = parser.parse(null, List.of("var1", "var2"));
        assertTrue(bindings.isEmpty());
    }

    @Test
    void testParseNoOutputVars_UsesDefaultResult() {
        String content = "Some result";
        List<Map<String, String>> bindings = parser.parse(content, null);

        assertEquals(1, bindings.size());
        assertEquals("Some result", bindings.get(0).get("result"));
    }

    @Test
    void testParseEmptyOutputVars_UsesDefaultResult() {
        String content = "Some result";
        List<Map<String, String>> bindings = parser.parse(content, List.of());

        assertEquals(1, bindings.size());
        assertEquals("Some result", bindings.get(0).get("result"));
    }

    // ========== Partial Field Matching ==========

    @Test
    void testParseMissingFields() {
        String content = "[{\"scientist\": \"Einstein\"}]";  // missing 'award' field

        List<Map<String, String>> bindings = parser.parse(content, List.of("scientist", "award"));

        assertEquals(1, bindings.size());
        assertEquals("Einstein", bindings.get(0).get("scientist"));
        assertNull(bindings.get(0).get("award"));
    }

    @Test
    void testParseExtraFieldsIgnored() {
        String content = "[{\"scientist\": \"Einstein\", \"award\": \"Physics\", \"extra\": \"ignored\"}]";

        List<Map<String, String>> bindings = parser.parse(content, List.of("scientist", "award"));

        assertEquals(1, bindings.size());
        assertEquals("Einstein", bindings.get(0).get("scientist"));
        assertEquals("Physics", bindings.get(0).get("award"));
        assertNull(bindings.get(0).get("extra"));
    }

    // ========== Data Type Handling ==========

    @Test
    void testParseNumericValues() {
        String content = "[{\"name\": \"Item\", \"count\": 42}]";

        List<Map<String, String>> bindings = parser.parse(content, List.of("name", "count"));

        assertEquals(1, bindings.size());
        assertEquals("42", bindings.get(0).get("count"));
    }

    @Test
    void testParseBooleanValues() {
        String content = "[{\"name\": \"Test\", \"active\": true}]";

        List<Map<String, String>> bindings = parser.parse(content, List.of("name", "active"));

        assertEquals(1, bindings.size());
        assertEquals("true", bindings.get(0).get("active"));
    }

    @Test
    void testParseNullValues() {
        String content = "[{\"name\": \"Test\", \"value\": null}]";

        List<Map<String, String>> bindings = parser.parse(content, List.of("name", "value"));

        assertEquals(1, bindings.size());
        assertEquals("", bindings.get(0).get("value"));
    }

    // ========== Invalid JSON Fallback ==========

    @Test
    void testParseInvalidJson_FallsBackToRawText() {
        String content = "This is not valid JSON at all";

        List<Map<String, String>> bindings = parser.parse(content, List.of("var1", "var2"));

        assertEquals(1, bindings.size());
        assertEquals(content, bindings.get(0).get("var1"));
    }

    // ========== canParse Tests ==========

    @Test
    void testCanParse_ValidJsonArray() {
        assertTrue(parser.canParse("[{\"a\": 1}]"));
    }

    @Test
    void testCanParse_ValidJsonObject() {
        assertTrue(parser.canParse("{\"a\": 1}"));
    }

    @Test
    void testCanParse_JsonInMarkdown() {
        assertTrue(parser.canParse("```json\n[{\"a\": 1}]\n```"));
    }

    @Test
    void testCanParse_PlainText() {
        assertFalse(parser.canParse("This is plain text"));
    }

    @Test
    void testCanParse_EmptyContent() {
        assertFalse(parser.canParse(""));
    }

    @Test
    void testCanParse_NullContent() {
        assertFalse(parser.canParse(null));
    }

    // ========== Complex Scenarios ==========

    @Test
    void testParseJsonWithExplanatoryText() {
        String content = "Here are the Nobel Prize winners:\n\n" +
                "[{\"scientist\": \"Einstein\", \"award\": \"1921 Physics\"}]\n\n" +
                "I hope this helps!";

        List<Map<String, String>> bindings = parser.parse(content, List.of("scientist", "award"));

        assertEquals(1, bindings.size());
        assertEquals("Einstein", bindings.get(0).get("scientist"));
    }

    @Test
    void testParseLargeJsonArray() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 10; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"name\": \"Person").append(i).append("\", \"id\": \"").append(i).append("\"}");
        }
        sb.append("]");

        List<Map<String, String>> bindings = parser.parse(sb.toString(), List.of("name", "id"));

        assertEquals(10, bindings.size());
        assertEquals("Person0", bindings.get(0).get("name"));
        assertEquals("Person9", bindings.get(9).get("name"));
    }
}
