package org.gensparql.test;

import org.apache.jena.query.Query;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementGroup;
import org.gensparql.engine.GenSPARQLConfig;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.engine.op.OpGenerate;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.gensparql.parser.element.ElementGenerate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The theta written on a GENOP must be the threshold grounding applies.
 *
 * <p>{@code GENOP(prompt, Y, M, theta)} is sugar for a GenOp followed by a similarity join at
 * theta. Theta used to reach only the ThresholdRegistry, which serves SimJoin and the SimScore
 * filter; neither runs for a context-mode GENOP, so grounding fell back to the global default
 * and the author's theta was silently ignored.
 */
@DisplayName("GENOP grounding threshold")
public class GenOpThresholdTest {

    private static final String PREFIXES =
            "PREFIX ex: <http://example.org/>\n"
          + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n";

    @BeforeAll
    static void init() {
        GenSPARQL.init();
    }

    @AfterEach
    void reset() {
        GenSPARQLConfig.reset();
    }

    private static OpGenerate genOpOf(String queryString) {
        Query query = GenSPARQLQueryFactory.create(queryString);
        for (Element e : ((ElementGroup) query.getQueryPattern()).getElements()) {
            if (e instanceof ElementGenerate) {
                return OpGenerate.fromElement((ElementGenerate) e);
            }
        }
        throw new AssertionError("no GENOP in query");
    }

    @Test
    @DisplayName("positional theta drives grounding in context mode")
    void positionalThetaDrivesGrounding() {
        OpGenerate op = genOpOf(PREFIXES
                + "SELECT ?pos WHERE { ?p rdfs:label ?n . "
                + "GENOP(\"position of {?n}\", (?pos), <model:mock:m>, 0.42) }");

        assertFalse(op.isBaseMode(), "context mode: SimJoin never fires, so theta must reach grounding");
        assertNotEquals(GenSPARQLConfig.getGroundingThreshold(), 0.42,
                "test is only meaningful while 0.42 differs from the global default");
        assertEquals(0.42, op.getEffectiveGroundingThreshold(), 1e-9,
                "grounding must use the theta the query author wrote, not the global default");
    }

    @Test
    @DisplayName("explicit grounding_threshold wins over positional theta")
    void explicitOptionWins() {
        OpGenerate op = genOpOf(PREFIXES
                + "SELECT ?pos WHERE { ?p rdfs:label ?n . "
                + "GENOP(\"position of {?n}\", (?pos), <model:mock:m>, 0.42, "
                + "grounding_threshold: 0.9) }");

        assertEquals(0.9, op.getEffectiveGroundingThreshold(), 1e-9);
    }

    @Test
    @DisplayName("without any theta, the global default applies")
    void fallsBackToGlobalDefault() {
        OpGenerate op = genOpOf(PREFIXES
                + "SELECT ?pos WHERE { ?p rdfs:label ?n . "
                + "GENOP(\"position of {?n}\", (?pos), <model:mock:m>) }");

        GenSPARQLConfig.setGroundingThreshold(0.55);
        assertEquals(0.55, op.getEffectiveGroundingThreshold(), 1e-9);
    }

    @Test
    @DisplayName("base-mode theta resolves the same way")
    void baseModeSameRule() {
        OpGenerate op = genOpOf(PREFIXES
                + "SELECT ?t WHERE { ?t a ex:Team . "
                + "GENOP(\"list teams\", (?t), <model:mock:m>, 0.7) }");

        assertTrue(op.isBaseMode());
        assertEquals(0.7, op.getEffectiveGroundingThreshold(), 1e-9,
                "base and context mode must agree on what theta means");
    }

    @Test
    @DisplayName("a non-numeric grounding_threshold falls through instead of failing the query")
    void nonNumericOptionFallsThrough() {
        OpGenerate op = OpGenerate.builder()
                .addOutputVariable("x")
                .promptTemplate("q {?n}")
                .modelURI("model:mock:m")
                .option("grounding_threshold", "not-a-number")
                .option("threshold", 0.33)
                .build();

        assertEquals(0.33, op.getEffectiveGroundingThreshold(), 1e-9);
    }
}
