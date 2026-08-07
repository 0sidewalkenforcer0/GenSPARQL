package org.gensparql.parser.lang;

import org.apache.jena.query.Query;
import org.apache.jena.sparql.core.Var;
import org.gensparql.parser.element.ElementGenerate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GenSPARQLParser covering GENOP function parsing.
 *
 * Test cases cover all modes:
 * 1. Base Mode - No input variables
 * 2. Context Mode - Prompt with {?x} placeholders
 * 3. Multi-variable Output - Multiple output variables
 * 4. Model Specification - Explicit model URI
 * 5. Optional Threshold - Similarity threshold θ
 */
public class GenSPARQLParserTest {

    @Nested
    @DisplayName("GENOP Function Parsing")
    class GenOpFunctionTests {

        @Test
        @DisplayName("Parse standalone GENOP - single variable")
        void testParseGenOpSingleVar() {
            String genop = "GENOP(\"Tell a story\", ?story, <model:openai:gpt-4>)";

            ElementGenerate element = GenSPARQLParser.parseGenOp(genop);

            assertNotNull(element);
            assertEquals(1, element.getOutputVariables().size());
            assertEquals("story", element.getOutputVariables().get(0).getName());
            assertEquals("Tell a story", element.getPromptTemplate());
            assertEquals("model:openai:gpt-4", element.getModelNode().getURI());
            assertNull(element.getThreshold());
            assertTrue(element.isBaseMode());
        }

        @Test
        @DisplayName("Parse standalone GENOP - with threshold")
        void testParseGenOpWithThreshold() {
            String genop = "GENOP(\"Translate {?text}\", ?translation, <model:openai:gpt-4>, 0.85)";

            ElementGenerate element = GenSPARQLParser.parseGenOp(genop);

            assertNotNull(element);
            assertEquals(1, element.getOutputVariables().size());
            assertEquals("translation", element.getOutputVariables().get(0).getName());
            assertEquals("Translate {?text}", element.getPromptTemplate());
            assertEquals(0.85, element.getThreshold(), 0.001);
            assertFalse(element.isBaseMode());  // Has {?text} placeholder
        }

        @Test
        @DisplayName("Parse standalone GENOP - multiple output variables")
        void testParseGenOpMultipleVars() {
            String genop = "GENOP(\"Parse name and date\", (?name, ?date), <model:claude:claude-3>)";

            ElementGenerate element = GenSPARQLParser.parseGenOp(genop);

            assertNotNull(element);
            assertEquals(2, element.getOutputVariables().size());
            assertEquals("name", element.getOutputVariables().get(0).getName());
            assertEquals("date", element.getOutputVariables().get(1).getName());
            assertTrue(element.isMultiVariableOutput());
        }

        @Test
        @DisplayName("Parse standalone GENOP - multiple vars with threshold")
        void testParseGenOpMultipleVarsWithThreshold() {
            String genop = "GENOP(\"Extract {?text}\", (?entity, ?relation, ?target), <model:openai:gpt-4>, 0.7)";

            ElementGenerate element = GenSPARQLParser.parseGenOp(genop);

            assertNotNull(element);
            assertEquals(3, element.getOutputVariables().size());
            assertEquals(0.7, element.getThreshold(), 0.001);
            assertTrue(element.isMultiVariableOutput());
            assertFalse(element.isBaseMode());
        }

        @Test
        @DisplayName("Parse GENOP in SPARQL query - base mode")
        void testParseGenOpInQueryBaseMode() {
            String query = """
                SELECT ?story WHERE {
                  GENOP("Tell me a short story", ?story, <model:openai:gpt-4>)
                }
                """;

            Query q = GenSPARQLParser.parse(query);
            assertNotNull(q);
            assertTrue(q.isSelectType());
        }

        @Test
        @DisplayName("Parse GENOP in SPARQL query - context mode")
        void testParseGenOpInQueryContextMode() {
            String query = """
                SELECT ?name ?translation WHERE {
                  ?person <http://xmlns.com/foaf/0.1/name> ?name .
                  GENOP("Translate '{?name}' to Japanese", ?translation, <model:openai:gpt-4>)
                }
                """;

            Query q = GenSPARQLParser.parse(query);
            assertNotNull(q);
            assertTrue(q.isSelectType());
        }

        @Test
        @DisplayName("Parse GENOP in SPARQL query - with threshold")
        void testParseGenOpInQueryWithThreshold() {
            String query = """
                SELECT ?match WHERE {
                  ?item <http://example.org/label> ?label .
                  GENOP("Find similar items to: {?label}", ?match, <model:openai:gpt-4>, 0.85)
                }
                """;

            Query q = GenSPARQLParser.parse(query);
            assertNotNull(q);
        }

        @Test
        @DisplayName("Parse GENOP in SPARQL query - multi-variable output")
        void testParseGenOpInQueryMultiVar() {
            String query = """
                SELECT ?name ?date WHERE {
                  ?doc <http://example.org/content> ?content .
                  GENOP("Extract name and date from: {?content}", (?name, ?date), <model:claude:claude-3>)
                }
                """;

            Query q = GenSPARQLParser.parse(query);
            assertNotNull(q);
        }

        @Test
        @DisplayName("Standalone GENOP accepts key-value options, same as in a full query")
        void testParseGenOpKeyValueOptions() {
            // parseGenOp routes through the query grammar, so the fragment form and the
            // in-query form accept the same GENOP language. The retired fragment grammar
            // took only a bare threshold and would have rejected this.
            ElementGenerate element = GenSPARQLParser.parseGenOp(
                    "GENOP(\"q {?n}\", (?x), <model:openai:gpt-4>, "
                  + "grounding_relation: \"http://example.org/playsFor\", 0.6)");

            assertEquals("http://example.org/playsFor",
                    element.getOptions().get("grounding_relation"));
            assertEquals(0.6, element.getThreshold(), 0.001);
        }

        @Test
        @DisplayName("Standalone GENOP rejects trailing patterns instead of discarding them")
        void testParseGenOpRejectsTrailingPatterns() {
            assertThrows(org.gensparql.core.exception.ParseException.class,
                    () -> GenSPARQLParser.parseGenOp(
                            "GENOP(\"q\", ?x, <model:openai:gpt-4>) ?s ?p ?o"));
        }
    }

    @Nested
    @DisplayName("ElementGenerate Properties")
    class ElementGenerateTests {

        @Test
        @DisplayName("Base mode detection - empty input variables")
        void testBaseModeDetection() {
            ElementGenerate element = ElementGenerate.builder()
                    .addOutputVariable("result")
                    .promptTemplate("Generate a random fact")
                    .modelURI("model:openai:gpt-4")
                    .build();

            assertTrue(element.isBaseMode());
            assertTrue(element.getInputVariables().isEmpty());
        }

        @Test
        @DisplayName("Context mode detection - has input variables")
        void testContextModeDetection() {
            ElementGenerate element = ElementGenerate.builder()
                    .addOutputVariable("translation")
                    .promptTemplate("Translate '{?name}' to French")
                    .modelURI("model:openai:gpt-4")
                    .build();

            assertFalse(element.isBaseMode());
            assertFalse(element.getInputVariables().isEmpty());
            assertTrue(element.getInputVariables().contains(Var.alloc("name")));
        }

        @Test
        @DisplayName("Multi-variable output detection")
        void testMultiVariableDetection() {
            ElementGenerate singleVar = ElementGenerate.builder()
                    .addOutputVariable("result")
                    .promptTemplate("test")
                    .modelURI("model:test")
                    .build();

            ElementGenerate multiVar = ElementGenerate.builder()
                    .addOutputVariable("name")
                    .addOutputVariable("date")
                    .promptTemplate("test")
                    .modelURI("model:test")
                    .build();

            assertFalse(singleVar.isMultiVariableOutput());
            assertTrue(multiVar.isMultiVariableOutput());
        }

        @Test
        @DisplayName("Threshold setting and getting")
        void testThreshold() {
            ElementGenerate withThreshold = ElementGenerate.builder()
                    .addOutputVariable("result")
                    .promptTemplate("test")
                    .modelURI("model:test")
                    .threshold(0.85)
                    .build();

            ElementGenerate noThreshold = ElementGenerate.builder()
                    .addOutputVariable("result")
                    .promptTemplate("test")
                    .modelURI("model:test")
                    .build();

            assertTrue(withThreshold.hasThreshold());
            assertEquals(0.85, withThreshold.getThreshold(), 0.001);

            assertFalse(noThreshold.hasThreshold());
            assertNull(noThreshold.getThreshold());
        }

        @Test
        @DisplayName("toGenOpSyntax conversion")
        void testToGenOpSyntax() {
            ElementGenerate element = ElementGenerate.builder()
                    .addOutputVariable("translation")
                    .promptTemplate("Translate: {?text}")
                    .modelURI("model:openai:gpt-4")
                    .threshold(0.9)
                    .build();

            String genopSyntax = element.toGenOpSyntax();

            assertTrue(genopSyntax.startsWith("GENOP("));
            assertTrue(genopSyntax.contains("?translation"));
            assertTrue(genopSyntax.contains("<model:openai:gpt-4>"));
            assertTrue(genopSyntax.contains("0.9"));
        }
    }

}
