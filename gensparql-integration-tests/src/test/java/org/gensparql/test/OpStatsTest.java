package org.gensparql.test;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.core.Var;
import org.gensparql.engine.cost.KgStats;
import org.gensparql.engine.cost.OpStats;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the C4-wiring machinery (#1): a triple pattern serialized by OpStats.buildPattern is
 * valid SPARQL that KgStats can count, yielding the real binding count N and distinct-value
 * count D the planner feeds into the cost model.
 */
public class OpStatsTest {

    private static final String NS = "http://example.org/";

    private static Model model() {
        Model m = ModelFactory.createDefaultModel();
        String[][] rows = {{"p1", "Physics"}, {"p2", "Physics"}, {"p3", "Chemistry"}};
        for (String[] r : rows) {
            m.add(ResourceFactory.createResource(NS + r[0]),
                  ResourceFactory.createProperty(NS + "researchField"),
                  ResourceFactory.createPlainLiteral(r[1]));
        }
        return m;
    }

    @Test
    void testBuiltPatternIsCountable() {
        // ?s <researchField> ?field
        Triple t = Triple.create(
                Var.alloc("s"),
                NodeFactory.createURI(NS + "researchField"),
                Var.alloc("field"));
        List<Triple> triples = new ArrayList<>(List.of(t));

        String pattern = OpStats.buildPattern(triples);
        assertTrue(pattern.contains("?s"));
        assertTrue(pattern.contains("<" + NS + "researchField>"));

        Model m = model();
        long n = KgStats.bindings(m, "", pattern);
        long d = KgStats.distinctBindings(m, "", pattern, "field");

        assertEquals(3, n, "3 researchField triples");
        assertEquals(2, d, "2 distinct fields (Physics, Chemistry)");
        assertEquals(2.0 / 3.0, KgStats.dedupRatio(m, "", pattern, "field"), 1e-9);
    }

    @Test
    void testEmptyPattern() {
        assertEquals("", OpStats.buildPattern(new ArrayList<>()));
    }
}
