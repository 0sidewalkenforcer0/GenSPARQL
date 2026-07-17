package org.gensparql.test;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResourceFactory;
import org.gensparql.core.model.GenerateRequest;
import org.gensparql.core.model.GenerateResponse;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GenSPARQL query execution using GENOP syntax.
 */
public class GenSPARQLExecutionTest {

    private static Model testModel;
    private MockLLMProvider mockProvider;

    @BeforeAll
    static void setUpClass() {
        // Initialize GenSPARQL
        GenSPARQL.init();

        // Create test model
        testModel = ModelFactory.createDefaultModel();
        String ns = "http://example.org/";

        // Add test data
        testModel.add(
            ResourceFactory.createResource(ns + "person1"),
            ResourceFactory.createProperty(ns + "name"),
            ResourceFactory.createPlainLiteral("Alice")
        );
        testModel.add(
            ResourceFactory.createResource(ns + "person2"),
            ResourceFactory.createProperty(ns + "name"),
            ResourceFactory.createPlainLiteral("Bob")
        );
        testModel.add(
            ResourceFactory.createResource(ns + "doc1"),
            ResourceFactory.createProperty(ns + "content"),
            ResourceFactory.createPlainLiteral("This is a test document about AI.")
        );
    }

    @BeforeEach
    void setUp() {
        // Set up mock provider for each test
        mockProvider = new MockLLMProvider();
        LLMProviderRegistry.setDefault(mockProvider);
    }

    @AfterEach
    void tearDown() {
        // C3 dedup is a global static flag; keep tests isolated.
        GenSPARQLConfig.reset();
    }

    // ---- C3: cross-binding prompt deduplication ----------------------------------

    /** Three bindings; two resolve to the same prompt ("Physics"), one to "Chemistry". */
    private static final String FIELD_QUERY = """
            PREFIX ex: <http://example.org/>
            SELECT ?field ?tool WHERE {
              ?p ex:field ?field .
              GENOP("List one tool used in {?field}", ?tool, <model:mock:test>)
            }
            """;

    private static Model fieldModel() {
        Model m = ModelFactory.createDefaultModel();
        String ns = "http://example.org/";
        String[][] rows = {{"p1", "Physics"}, {"p2", "Physics"}, {"p3", "Chemistry"}};
        for (String[] r : rows) {
            m.add(ResourceFactory.createResource(ns + r[0]),
                  ResourceFactory.createProperty(ns + "field"),
                  ResourceFactory.createPlainLiteral(r[1]));
        }
        return m;
    }

    private List<String> runFieldQuery(Model model) {
        Query query = GenSPARQLQueryFactory.create(FIELD_QUERY);
        List<String> out = new ArrayList<>();
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet rs = qexec.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                out.add(s.get("field") + "|" + s.get("tool"));
            }
        }
        return out;
    }

    @Test
    void testDedupCollapsesIdenticalPromptCalls() {
        mockProvider.withResponse("Physics", "telescope");
        mockProvider.withResponse("Chemistry", "beaker");

        GenSPARQLConfig.setBatchDedupEnabled(true);
        List<String> rows = runFieldQuery(fieldModel());

        assertEquals(3, rows.size(), "Dedup must not change the number of result rows");
        assertEquals(2, mockProvider.getRequestHistory().size(),
                "Two Physics bindings must share one LLM call -> 2 distinct prompts, 2 calls");
    }

    @Test
    void testEmptyOutputFieldKeepsPartialRow() {
        // Provider returns a multi-var binding with one present-but-empty field.
        MockLLMProvider p = new MockLLMProvider() {
            @Override
            public CompletableFuture<GenerateResponse> generate(GenerateRequest request) {
                Map<String, String> b = new HashMap<>();
                b.put("director", "Nolan");
                b.put("cowriter", "");
                return CompletableFuture.completedFuture(GenerateResponse.success("raw", List.of(b)));
            }
        };
        LLMProviderRegistry.setDefault(p);

        String q = """
                SELECT ?director ?cowriter WHERE {
                  GENOP("Name the director and co-writer.", (?director, ?cowriter), <model:mock:test>)
                }
                """;
        Query query = GenSPARQLQueryFactory.create(q);
        int count = 0;
        try (QueryExecution qe = QueryExecutionFactory.create(query, testModel)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                assertEquals("Nolan", s.get("director").toString());
                assertNull(s.get("cowriter"), "empty field must be unbound, not drop the whole row");
                count++;
            }
        }
        assertEquals(1, count, "a row with one valid field must survive an empty sibling field");
    }

    @Test
    void testDedupDoesNotMemoizeFailedCalls() {
        AtomicInteger calls = new AtomicInteger();
        MockLLMProvider failing = new MockLLMProvider() {
            @Override
            public CompletableFuture<GenerateResponse> generate(GenerateRequest request) {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(GenerateResponse.error("transient failure"));
            }
        };
        LLMProviderRegistry.setDefault(failing);
        GenSPARQLConfig.setBatchDedupEnabled(true);

        List<String> rows = runFieldQuery(fieldModel()); // 3 bindings, Physics x2 + Chemistry

        assertTrue(rows.isEmpty(), "all calls failed -> no rows");
        assertEquals(3, calls.get(),
                "failed calls must NOT be memoized; each binding retries (would be 2 if memoized)");
    }

    @Test
    void testDedupIsLossless() {
        mockProvider.withResponse("Physics", "telescope");
        mockProvider.withResponse("Chemistry", "beaker");

        // Baseline: dedup OFF -> one LLM call per binding.
        GenSPARQLConfig.setBatchDedupEnabled(false);
        List<String> baseline = runFieldQuery(fieldModel());
        assertEquals(3, mockProvider.getRequestHistory().size(),
                "Without dedup, every binding calls the LLM");

        mockProvider.clearHistory();

        // Dedup ON -> fewer calls, identical results.
        GenSPARQLConfig.setBatchDedupEnabled(true);
        List<String> deduped = runFieldQuery(fieldModel());
        assertEquals(2, mockProvider.getRequestHistory().size(),
                "With dedup, identical prompts collapse to one call");

        Collections.sort(baseline);
        Collections.sort(deduped);
        assertEquals(baseline, deduped, "Dedup must be lossless: identical (field,tool) rows");
    }

    @Test
    void testSimpleGenOpExecution() {
        // Configure mock response
        mockProvider.withResponse("Alice", "アリス");
        mockProvider.withResponse("Bob", "ボブ");

        String queryString = """
            PREFIX ex: <http://example.org/>
            SELECT ?name ?translation WHERE {
              ?person ex:name ?name .
              GENOP("Translate '{?name}' to Japanese", ?translation, <model:mock:test>)
            }
            """;

        Query query = GenSPARQLQueryFactory.create(queryString);

        try (QueryExecution qexec = QueryExecutionFactory.create(query, testModel)) {
            ResultSet results = qexec.execSelect();

            int count = 0;
            while (results.hasNext()) {
                QuerySolution soln = results.next();
                assertNotNull(soln.get("name"));
                assertNotNull(soln.get("translation"));
                count++;
            }

            assertEquals(2, count, "Should have 2 results");
        }
    }

    @Test
    void testBaseModeExecution() {
        mockProvider.withDefaultResponse("The sky is blue because of Rayleigh scattering.");

        String queryString = """
            SELECT ?fact WHERE {
              GENOP("Tell me a random science fact", ?fact, <model:mock:test>)
            }
            """;

        Query query = GenSPARQLQueryFactory.create(queryString);

        try (QueryExecution qexec = QueryExecutionFactory.create(query, testModel)) {
            ResultSet results = qexec.execSelect();

            assertTrue(results.hasNext(), "Should have at least one result");
            QuerySolution soln = results.next();
            assertNotNull(soln.get("fact"));
            assertTrue(soln.get("fact").toString().contains("Rayleigh"));
        }
    }

    @Test
    void testCombinedWithFilter() {
        mockProvider.withResponse("AI", "artificial_intelligence");
        mockProvider.withDefaultResponse("other_topic");

        String queryString = """
            PREFIX ex: <http://example.org/>
            SELECT ?content ?topic WHERE {
              ?doc ex:content ?content .
              GENOP("Extract main topic from: {?content}", ?topic, <model:mock:test>)
              FILTER(CONTAINS(?content, "AI"))
            }
            """;

        Query query = GenSPARQLQueryFactory.create(queryString);

        try (QueryExecution qexec = QueryExecutionFactory.create(query, testModel)) {
            ResultSet results = qexec.execSelect();

            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            assertNotNull(soln.get("topic"));
        }
    }

    @Test
    void testMockProviderRequestHistory() {
        mockProvider.withDefaultResponse("test response");

        String queryString = """
            PREFIX ex: <http://example.org/>
            SELECT ?name ?result WHERE {
              ?person ex:name ?name .
              GENOP("Process: {?name}", ?result, <model:mock:test>)
            }
            """;

        Query query = GenSPARQLQueryFactory.create(queryString);

        try (QueryExecution qexec = QueryExecutionFactory.create(query, testModel)) {
            ResultSet results = qexec.execSelect();
            ResultSetFormatter.consume(results);
        }

        // Verify requests were made
        assertFalse(mockProvider.getRequestHistory().isEmpty(),
                "Should have recorded requests");
    }
}
