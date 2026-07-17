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
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end verification that wiring the cost-based planner (C4) into the engine preserves
 * results (correctness axis E4a) — the reordered plan must return exactly what the default
 * plan returns. Runs through the real GenSPARQLQueryEngine with the mock provider.
 */
public class CostBasedPlanningTest {

    private static final String QUERY = """
            PREFIX ex: <http://example.org/>
            SELECT ?s ?field ?desc WHERE {
              ?s ex:researchField ?field .
              GENOP("Describe the field {?field}.", ?desc, <model:mock:test>)
            }
            """;

    private MockLLMProvider mock;

    @BeforeAll
    static void initEngine() {
        GenSPARQL.init();
    }

    @BeforeEach
    void setUp() {
        mock = new MockLLMProvider()
                .withResponse("Physics", "the study of matter and energy")
                .withResponse("Chemistry", "the study of substances")
                .withDefaultResponse("a field of study");
        LLMProviderRegistry.setDefault(mock);
    }

    @AfterEach
    void tearDown() {
        GenSPARQLConfig.reset();
    }

    private static Model model() {
        Model m = ModelFactory.createDefaultModel();
        String ns = "http://example.org/";
        String[][] rows = {{"p1", "Physics"}, {"p2", "Physics"}, {"p3", "Chemistry"}};
        for (String[] r : rows) {
            m.add(ResourceFactory.createResource(ns + r[0]),
                  ResourceFactory.createProperty(ns + "researchField"),
                  ResourceFactory.createPlainLiteral(r[1]));
        }
        return m;
    }

    private List<String> run(Model model) {
        Query query = GenSPARQLQueryFactory.create(QUERY);
        List<String> rows = new ArrayList<>();
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet rs = qexec.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                rows.add(s.get("s") + "|" + s.get("field") + "|" + s.get("desc"));
            }
        }
        Collections.sort(rows);
        return rows;
    }

    @Test
    void testCostBasedPlanningPreservesResults() {
        Model data = model();

        GenSPARQLConfig.setCostBasedPlanningEnabled(false);
        List<String> baseline = run(data);

        GenSPARQLConfig.setCostBasedPlanningEnabled(true);
        List<String> planned = run(data);

        assertEquals(3, baseline.size(), "sanity: 3 bindings");
        assertEquals(baseline, planned,
                "cost-based reorder must preserve results exactly (E4a)");
    }

    @Test
    void testCostBasedWithDedupPreservesResults() {
        Model data = model();

        GenSPARQLConfig.setCostBasedPlanningEnabled(false);
        GenSPARQLConfig.setBatchDedupEnabled(false);
        List<String> baseline = run(data);

        // Both C3 and C4 on together must still return the same rows.
        GenSPARQLConfig.setCostBasedPlanningEnabled(true);
        GenSPARQLConfig.setBatchDedupEnabled(true);
        List<String> planned = run(data);

        assertEquals(baseline, planned,
                "cost-based reorder + dedup must jointly preserve results");
    }
}
