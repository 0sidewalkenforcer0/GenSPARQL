package org.gensparql.test;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResourceFactory;
import org.gensparql.engine.GenSPARQLConfig;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.llm.LLMProviderRegistry;
import org.gensparql.llm.provider.MockLLMProvider;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Grounding memo (review finding #10): grounding is deterministic for a fixed value/relation/
 * threshold, so the resolved node is cached per value. This verifies the cache is
 * result-consistent and does not break execution — a value repeated across bindings resolves
 * to the same node every time (whether grounding succeeds or falls back to the literal).
 */
public class GroundingMemoTest {

    private MockLLMProvider mock;

    @BeforeAll
    static void init() {
        GenSPARQL.init();
    }

    @BeforeEach
    void setUp() {
        // Same raw value for every binding, so the grounding cache is exercised across rows.
        mock = new MockLLMProvider().withDefaultResponse("Quantum Physics");
        LLMProviderRegistry.setDefault(mock);
    }

    @AfterEach
    void tearDown() {
        GenSPARQLConfig.reset();
    }

    private static Model model() {
        Model m = ModelFactory.createDefaultModel();
        String ns = "http://example.org/";
        // Three subjects sharing a field -> three bindings.
        for (String s : new String[]{"s1", "s2", "s3"}) {
            m.add(ResourceFactory.createResource(ns + s),
                  ResourceFactory.createProperty(ns + "field"),
                  ResourceFactory.createPlainLiteral("physics"));
        }
        // A couple of labeled entities as grounding candidates.
        m.add(ResourceFactory.createResource(ns + "qm"),
              ResourceFactory.createProperty("http://www.w3.org/2000/01/rdf-schema#label"),
              ResourceFactory.createPlainLiteral("Quantum Mechanics"));
        m.add(ResourceFactory.createResource(ns + "cm"),
              ResourceFactory.createProperty("http://www.w3.org/2000/01/rdf-schema#label"),
              ResourceFactory.createPlainLiteral("Classical Mechanics"));
        return m;
    }

    private static final String QUERY = """
            PREFIX ex: <http://example.org/>
            SELECT ?s ?g WHERE {
              ?s ex:field ?f .
              GENOP("Name a topic in {?f}.", ?g, <model:mock:test>)
            }
            """;

    @Test
    void testGroundingCacheIsConsistentAcrossRepeatedValue() {
        GenSPARQLConfig.setGroundingEnabled(true);
        GenSPARQLConfig.setGroundingThreshold(0.0); // ground to nearest if possible

        Query q = GenSPARQLQueryFactory.create(QUERY);
        List<String> gValues = new ArrayList<>();
        try (QueryExecution qe = QueryExecutionFactory.create(q, model())) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                gValues.add(String.valueOf(rs.next().get("g")));
            }
        }

        assertEquals(3, gValues.size(), "three bindings -> three rows");
        // The cache must resolve the same raw value to the same node every time.
        assertEquals(1, gValues.stream().distinct().count(),
                "a value repeated across bindings must ground to one consistent node");
    }

    @Test
    void testGroundingDisabledStillWorks() {
        GenSPARQLConfig.setGroundingEnabled(false);
        Query q = GenSPARQLQueryFactory.create(QUERY);
        int n = 0;
        try (QueryExecution qe = QueryExecutionFactory.create(q, model())) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                rs.next();
                n++;
            }
        }
        assertEquals(3, n);
    }
}
