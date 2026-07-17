package org.gensparql.test;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.gensparql.engine.GenSPARQLConfig;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.llm.LLMProviderRegistry;
import org.gensparql.llm.provider.MockLLMProvider;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C3 measurement on a realistic workload: the bundled scientists KG has ~40
 * scientist->researchField edges but only a handful of distinct fields, so a
 * context-mode GENOP over the field produces many identical prompts. Cross-binding
 * dedup collapses those to one LLM call per distinct field, losslessly.
 *
 * Runs against the mock provider (deterministic, no API key), so the headline is the
 * call-count reduction (logical GENOP invocations -> actual LLM calls); wall-clock/$
 * gains follow directly since latency is LLM-bound (see docs/EVALUATION.md E4).
 */
public class C3DedupWorkloadTest {

    private static Model scientists;

    private static final String FIELD_QUERY = """
            PREFIX ex: <http://example.org/>
            SELECT ?scientist ?field ?desc WHERE {
              ?scientist ex:researchField ?field .
              GENOP("Describe the research field {?field} in one sentence.",
                    ?desc, <model:mock:test>)
            }
            """;

    static boolean dataAvailable() {
        return locateTtl() != null;
    }

    private static File locateTtl() {
        String[] candidates = {
            "../gensparql-example/data/scientists_awards_large.ttl",
            "gensparql-example/data/scientists_awards_large.ttl",
            "../../gensparql-example/data/scientists_awards_large.ttl",
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.exists()) return f;
        }
        return null;
    }

    @BeforeAll
    static void setUpClass() {
        GenSPARQL.init();
        File ttl = locateTtl();
        if (ttl != null) {
            scientists = RDFDataMgr.loadModel(ttl.toURI().toString());
        }
    }

    private MockLLMProvider mockProvider;

    @BeforeEach
    void setUp() {
        mockProvider = new MockLLMProvider();
        mockProvider.withDefaultResponse("A field of scientific study.");
        LLMProviderRegistry.setDefault(mockProvider);
    }

    @AfterEach
    void tearDown() {
        GenSPARQLConfig.reset();
    }

    private List<String> runWorkload() {
        Query query = GenSPARQLQueryFactory.create(FIELD_QUERY);
        List<String> rows = new ArrayList<>();
        try (QueryExecution qexec = QueryExecutionFactory.create(query, scientists)) {
            ResultSet rs = qexec.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                rows.add(s.get("scientist") + "|" + s.get("field") + "|" + s.get("desc"));
            }
        }
        return rows;
    }

    @Test
    @EnabledIf("dataAvailable")
    void testDedupReducesCallsOnScientistsKG() {
        // Baseline: one LLM call per binding.
        GenSPARQLConfig.setBatchDedupEnabled(false);
        List<String> baseline = runWorkload();
        int baselineCalls = mockProvider.getRequestHistory().size();

        mockProvider.clearHistory();

        // Dedup: one call per distinct field prompt.
        GenSPARQLConfig.setBatchDedupEnabled(true);
        List<String> deduped = runWorkload();
        int dedupCalls = mockProvider.getRequestHistory().size();

        System.out.printf(
            "[C3-DEMO] scientists KG researchField workload: %d logical GENOP invocations "
            + "-> %d actual LLM calls with dedup (%.0f%% reduction); result rows: %d (unchanged)%n",
            baselineCalls, dedupCalls,
            100.0 * (baselineCalls - dedupCalls) / baselineCalls, deduped.size());

        assertEquals(baselineCalls, baseline.size(),
            "Baseline should make one LLM call per binding");
        assertTrue(dedupCalls < baselineCalls,
            "Dedup must reduce the number of LLM calls on a repeated-prompt workload");

        Collections.sort(baseline);
        Collections.sort(deduped);
        assertEquals(baseline, deduped, "Dedup must be lossless: identical result rows");
    }
}
