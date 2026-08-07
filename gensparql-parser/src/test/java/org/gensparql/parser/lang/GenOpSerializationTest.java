package org.gensparql.parser.lang;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementGroup;
import org.apache.jena.sparql.syntax.ElementVisitorBase;
import org.apache.jena.sparql.syntax.ElementWalker;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.gensparql.parser.element.ElementGenerate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GENOP must survive serialization.
 *
 * <p>Jena's element formatter has no case for {@code ElementGenerate}, so before the GenSPARQL
 * serializer was registered {@code Query.toString()} dropped GENOP silently: the query still
 * re-parsed, just as plain SPARQL with different semantics. These tests pin the round trip.
 */
@DisplayName("GENOP serialization")
public class GenOpSerializationTest {

    private static final String PREFIXES =
            "PREFIX ex: <http://example.org/>\n"
          + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n";

    private static List<ElementGenerate> genOpsIn(Query q) {
        List<ElementGenerate> found = new ArrayList<>();
        ElementWalker.walk(q.getQueryPattern(), new ElementVisitorBase() {
            @Override
            public void visit(ElementGroup group) {
                for (Element el : group.getElements()) {
                    if (el instanceof ElementGenerate) {
                        found.add((ElementGenerate) el);
                    }
                }
            }
        });
        return found;
    }

    /** Parse, serialize, re-parse. */
    private static Query roundTrip(String queryString) {
        Query parsed = GenSPARQLQueryFactory.create(queryString);
        return GenSPARQLQueryFactory.create(parsed.toString());
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        @DisplayName("context-mode GENOP with threshold survives parse -> toString -> parse")
        void contextModeWithThreshold() {
            String q = PREFIXES
                    + "SELECT ?pos WHERE {\n"
                    + "  ?p rdfs:label ?n .\n"
                    + "  GENOP(\"position of {?n}\", (?pos), <model:mock:m>, 0.85)\n"
                    + "}";

            Query parsed = GenSPARQLQueryFactory.create(q);
            assertTrue(parsed.toString().contains("GENOP"),
                    "serialized query must still contain GENOP");

            List<ElementGenerate> after = genOpsIn(roundTrip(q));
            assertEquals(1, after.size(), "GENOP must survive the round trip");

            ElementGenerate g = after.get(0);
            assertEquals("position of {?n}", g.getPromptTemplate());
            assertEquals(1, g.getOutputVariables().size());
            assertEquals("pos", g.getOutputVariables().get(0).getName());
            assertEquals("model:mock:m", g.getModelNode().getURI());
            assertEquals(0.85, g.getThreshold(), 1e-9);
            assertEquals(1, g.getInputVariables().size(), "?n is an input variable");
        }

        @Test
        @DisplayName("base-mode GENOP without threshold survives")
        void baseModeNoThreshold() {
            String q = PREFIXES
                    + "SELECT ?t WHERE { ?t a ex:Team . GENOP(\"list teams\", ?t, <model:mock:m>) }";

            List<ElementGenerate> after = genOpsIn(roundTrip(q));
            assertEquals(1, after.size());
            assertNull(after.get(0).getThreshold(), "absent threshold must stay absent");
            assertTrue(after.get(0).getInputVariables().isEmpty(), "base mode has no input vars");
        }

        @Test
        @DisplayName("multi-variable output survives")
        void multiVariableOutput() {
            String q = PREFIXES
                    + "SELECT ?a ?b WHERE { ?p rdfs:label ?n . "
                    + "GENOP(\"split {?n}\", (?a, ?b), <model:mock:m>) }";

            ElementGenerate g = genOpsIn(roundTrip(q)).get(0);
            assertEquals(List.of("a", "b"),
                    g.getOutputVariables().stream().map(v -> v.getName()).toList());
        }

        @Test
        @DisplayName("key-value options survive")
        void keyValueOptions() {
            String q = PREFIXES
                    + "SELECT ?x WHERE { ?p rdfs:label ?n . "
                    + "GENOP(\"q {?n}\", (?x), <model:mock:m>, "
                    + "grounding_relation: \"http://example.org/playsFor\", "
                    + "grounding_threshold: 0.8) }";

            ElementGenerate g = genOpsIn(roundTrip(q)).get(0);
            assertEquals("http://example.org/playsFor",
                    g.getOptions().get("grounding_relation"),
                    "grounding_relation must not be lost when the query is serialized");
            assertEquals(0.8, ((Number) g.getOptions().get("grounding_threshold")).doubleValue(), 1e-9);
        }

        @Test
        @DisplayName("GENOP between two triple blocks keeps its position")
        void genOpBetweenPatterns() {
            String q = PREFIXES
                    + "SELECT ?player WHERE {\n"
                    + "  ?p a ex:Athlete ; rdfs:label ?player .\n"
                    + "  GENOP(\"position of {?player}\", (?pos), <model:mock:m>)\n"
                    + "  ?p ex:playsFor ?t .\n"
                    + "}";

            Query reparsed = roundTrip(q);
            assertEquals(1, genOpsIn(reparsed).size());

            ElementGroup group = (ElementGroup) reparsed.getQueryPattern();
            int genOpIndex = -1;
            for (int i = 0; i < group.getElements().size(); i++) {
                if (group.getElements().get(i) instanceof ElementGenerate) {
                    genOpIndex = i;
                }
            }
            assertTrue(genOpIndex > 0 && genOpIndex < group.getElements().size() - 1,
                    "GENOP must stay between the two patterns, not drift to an end");
        }

        @Test
        @DisplayName("a prompt containing quotes and backslashes survives")
        void escapingInPrompt() {
            String q = PREFIXES
                    + "SELECT ?x WHERE { ?p rdfs:label ?n . "
                    + "GENOP(\"say \\\"hi\\\" to {?n}\", (?x), <model:mock:m>) }";

            assertEquals("say \"hi\" to {?n}",
                    genOpsIn(roundTrip(q)).get(0).getPromptTemplate());
        }
    }

    @Nested
    @DisplayName("no effect on plain SPARQL")
    class PlainSparql {

        @Test
        @DisplayName("a GENOP-free query serializes exactly as stock Jena serializes it")
        void plainQueryUnchanged() {
            String q = PREFIXES + "SELECT ?s WHERE { ?s a ex:Team ; rdfs:label ?l . FILTER(?l != \"x\") }";

            assertEquals(QueryFactory.create(q).toString(),
                    GenSPARQLQueryFactory.create(q).toString(),
                    "registering the GenSPARQL serializer must not change plain SPARQL output");
        }
    }
}
