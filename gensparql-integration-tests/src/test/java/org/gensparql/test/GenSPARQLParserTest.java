package org.gensparql.test;

import org.apache.jena.query.Query;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementGroup;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.gensparql.parser.element.ElementGenerate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GenSPARQL parser using GENOP syntax.
 */
public class GenSPARQLParserTest {

    @Test
    void testParseSimpleGenOp() {
        String queryString = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            SELECT ?name ?translation WHERE {
              ?person foaf:name ?name .
              GENOP("Translate '{?name}' to Japanese", ?translation, <model:openai:gpt-4>)
            }
            """;

        Query query = GenSPARQLQueryFactory.create(queryString);

        assertNotNull(query);
        assertTrue(query.isSelectType());

        // Check that the query pattern contains ElementGenerate
        Element pattern = query.getQueryPattern();
        assertNotNull(pattern);
        assertTrue(containsElementGenerate(pattern));
    }

    @Test
    void testParseMultipleOutputVariables() {
        String queryString = """
            SELECT ?summary ?keywords WHERE {
              ?doc <http://ex.org/content> ?content .
              GENOP("Summarize and extract keywords: {?content}", (?summary, ?keywords), <model:anthropic:claude-3-opus>)
            }
            """;

        Query query = GenSPARQLQueryFactory.create(queryString);
        assertNotNull(query);

        ElementGenerate genElement = findElementGenerate(query.getQueryPattern());
        assertNotNull(genElement);
        assertEquals(2, genElement.getOutputVariables().size());
    }

    @Test
    void testParseWithThreshold() {
        String queryString = """
            SELECT ?answer WHERE {
              ?q <http://ex.org/text> ?question .
              GENOP("Answer: {?question}", ?answer, <model:openai:gpt-4>, 0.85)
            }
            """;

        Query query = GenSPARQLQueryFactory.create(queryString);
        assertNotNull(query);

        ElementGenerate genElement = findElementGenerate(query.getQueryPattern());
        assertNotNull(genElement);
        assertTrue(genElement.hasThreshold());
        assertEquals(0.85, genElement.getThreshold(), 0.001);
    }

    @Test
    void testParseBaseMode() {
        // Base mode: no input variables in prompt
        String queryString = """
            SELECT ?fact WHERE {
              GENOP("Tell me a random historical fact", ?fact, <model:openai:gpt-4>)
            }
            """;

        Query query = GenSPARQLQueryFactory.create(queryString);
        assertNotNull(query);

        ElementGenerate genElement = findElementGenerate(query.getQueryPattern());
        assertNotNull(genElement);
        assertTrue(genElement.isBaseMode());
        assertTrue(genElement.getInputVariables().isEmpty());
    }

    @Test
    void testParseContextMode() {
        // Context mode: has input variables
        String queryString = """
            SELECT ?name ?bio WHERE {
              ?person <http://ex.org/name> ?name .
              GENOP("Write a brief bio for {?name}", ?bio, <model:openai:gpt-4>)
            }
            """;

        Query query = GenSPARQLQueryFactory.create(queryString);
        assertNotNull(query);

        ElementGenerate genElement = findElementGenerate(query.getQueryPattern());
        assertNotNull(genElement);
        assertFalse(genElement.isBaseMode());
        assertEquals(1, genElement.getInputVariables().size());
        assertTrue(genElement.getInputVariables().stream()
                .anyMatch(v -> v.getName().equals("name")));
    }

    @Test
    void testStandardSPARQLQuery() {
        // Standard SPARQL without GENOP should still work
        String queryString = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            SELECT ?name WHERE {
              ?person foaf:name ?name .
            }
            """;

        Query query = GenSPARQLQueryFactory.create(queryString);
        assertNotNull(query);
        assertTrue(query.isSelectType());
    }

    private boolean containsElementGenerate(Element element) {
        if (element instanceof ElementGenerate) {
            return true;
        }
        if (element instanceof ElementGroup) {
            for (Element e : ((ElementGroup) element).getElements()) {
                if (containsElementGenerate(e)) {
                    return true;
                }
            }
        }
        return false;
    }

    private ElementGenerate findElementGenerate(Element element) {
        if (element instanceof ElementGenerate) {
            return (ElementGenerate) element;
        }
        if (element instanceof ElementGroup) {
            for (Element e : ((ElementGroup) element).getElements()) {
                ElementGenerate found = findElementGenerate(e);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
